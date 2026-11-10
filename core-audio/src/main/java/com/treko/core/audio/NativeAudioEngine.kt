package com.treko.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.treko.core.common.model.AudioLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Motor de captura de audio y análisis espectral en tiempo real para Treko.
 * Combina AudioRecord de Android (VOICE_RECOGNITION) y enlace nativo AAudio/Oboe.
 */
object NativeAudioEngine {

    private const val TAG = "TrekoAudio"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    init {
        try {
            System.loadLibrary("treko_audio")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Librería nativa treko_audio no disponible en este entorno: ${e.message}")
        }
    }

    private val _audioLevel = MutableStateFlow(AudioLevel())
    val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()

    @Volatile
    var isRecording: Boolean = false
        private set

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    // Callback para entregar muestras de audio a detectores KWS o red
    var onPcmData: ((ShortArray, Int) -> Unit)? = null

    external fun nativeInitAudio(sampleRate: Int, channels: Int): Boolean
    external fun nativeStartRecording(): Boolean
    external fun nativeReadPcm(buffer: ShortArray, numSamples: Int): Int
    external fun nativeCalculateRms(buffer: ShortArray, length: Int): Float
    external fun nativeStopRecording()

    /**
     * Inicia la captura continua de audio desde el micrófono en un hilo IO dedicado.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope) {
        if (isRecording) {
            Log.d(TAG, "La captura de audio ya se encuentra activa.")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ).coerceAtLeast(1024)

        try {
            // Usar AudioSource.MIC estándar para audio prístino lineal a 16kHz
            // sin los filtros agresivos de puerta de ruido/beamforming de Samsung Bixby
            var record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "MIC estándar no disponible, intentando con VOICE_RECOGNITION.")
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize * 2
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "No fue posible inicializar AudioRecord.")
                record.release()
                return
            }

            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "AcousticEchoCanceler de hardware habilitado con éxito (sessionId=$sessionId).")
                } catch (e: Exception) {
                    Log.w(TAG, "No fue posible habilitar AcousticEchoCanceler: ${e.message}")
                }
            } else {
                Log.d(TAG, "AcousticEchoCanceler no disponible en este hardware.")
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "NoiseSuppressor de hardware habilitado con éxito (sessionId=$sessionId).")
                } catch (e: Exception) {
                    Log.w(TAG, "No fue posible habilitar NoiseSuppressor: ${e.message}")
                }
            }

            record.startRecording()
            audioRecord = record
            isRecording = true
            Log.i(TAG, "Captura de micrófono iniciada exitosamente a $SAMPLE_RATE Hz (Fuente: ${record.audioSource}).")

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(960) // 960 muestras = exactamente 60ms a 16kHz para alineación Opus perfecta
                while (isActive && isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Calcular RMS de volumen
                        var sum = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i] / 32768.0
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / read).toFloat()
                        updateLevel(rms)

                        // Despachar a oyentes KWS
                        onPcmData?.invoke(buffer, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al iniciar captura de micrófono: ${e.message}", e)
            isRecording = false
        }
    }

    /**
     * Detiene la captura de audio y libera los recursos del micrófono.
     */
    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            aec?.release()
            aec = null
            ns?.release()
            ns = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando efectos de audio: ${e.message}")
        }
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
        }
        audioRecord = null
        _audioLevel.value = AudioLevel()
        Log.i(TAG, "Captura de micrófono detenida.")
    }

    /**
     * Actualiza las bandas visuales del ecualizador reactivo en Canvas.
     */
    fun updateLevel(rms: Float) {
        val amplified = (rms * 4.0f).coerceIn(0f, 1f)
        val bands = FloatArray(5) { i ->
            val weight = when (i) {
                0 -> 0.8f
                1 -> 1.4f
                2 -> 1.8f
                3 -> 1.3f
                else -> 0.9f
            }
            (amplified * weight).coerceIn(0.08f, 1.0f)
        }

        _audioLevel.value = AudioLevel(
            rms = rms,
            isSpeaking = rms > 0.025f,
            bands = bands
        )
    }
}
