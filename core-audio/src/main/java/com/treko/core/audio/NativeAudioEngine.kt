package com.treko.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Motor de captura de audio y análisis espectral en tiempo real para Treko.
 * Combina AudioRecord de Android (VOICE_RECOGNITION / VOICE_COMMUNICATION), efectos AEC/NS/AGC,
 * VAD adaptativo por piso de ruido y enlace nativo AAudio.
 */
object NativeAudioEngine {

    private const val TAG = "TrekoAudio"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_SAMPLES = 960 // 60ms a 16kHz para alineación Opus perfecta

    data class AudioDiagnostics(
        val isRecording: Boolean,
        val sampleRate: Int,
        val audioSource: Int,
        val audioSessionId: Int,
        val aecActive: Boolean,
        val nsActive: Boolean,
        val agcActive: Boolean,
        val noiseFloorRms: Float,
        val lastRms: Float,
        val lastSnrDb: Float,
        val readErrorCount: Int,
        val swAec3Active: Boolean = false,
        val aecDelayMs: Int = 0,
        val erleDb: Float = 0f
    )

    init {
        try {
            System.loadLibrary("treko_audio")
        } catch (e: UnsatisfiedLinkError) {
            TrekoLogBuffer.w(TAG, "Librería nativa treko_audio no disponible en este entorno: ${e.message}")
        }
    }

    private val stateLock = Any()
    private val _audioLevel = MutableStateFlow(AudioLevel())
    val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()

    val adaptiveVad = AdaptiveVad()

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var captureAudioSessionId: Int = 0
        private set

    @Volatile
    private var activeAudioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    @Volatile
    private var readErrorCount: Int = 0

    @Volatile
    private var lastSnrDb: Float = 0f

    @Volatile
    var lastFrameMetrics: AdaptiveVad.FrameMetrics = AdaptiveVad.FrameMetrics(0f, 0f, 0.005f, 0f, false, 1f)
        private set

    private val aecEngine = TrekoAecEngine()

    @Volatile
    var softwareAecEnabled: Boolean = true

    @Volatile
    private var lastPlayoutDelayMs: Int = 80

    @Volatile
    private var lastErleDb: Float = 0f

    @Volatile
    private var lastSwAecActive: Boolean = false

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile
    var onPreferredOutputDeviceChanged: ((AudioDeviceInfo?) -> Unit)? = null

    private val headsetRouter = HeadsetAudioRouter { route ->
        onPreferredOutputDeviceChanged?.invoke(route.outputDevice)
    }

    val isHeadsetRouted: Boolean
        get() = headsetRouter.currentRoute.isHeadsetActive

    val activeHeadsetName: String?
        get() = headsetRouter.currentRoute.deviceLabel

    val preferredOutputDevice: AudioDeviceInfo?
        get() = headsetRouter.currentRoute.outputDevice

    /** Which connected devices may be used as a headset (see [HeadsetAudioRouter.deviceFilter]). */
    var headsetDeviceFilter: (AudioDeviceInfo) -> Boolean
        get() = headsetRouter.deviceFilter
        set(value) {
            headsetRouter.deviceFilter = value
        }

    fun attachAudioManager(manager: AudioManager?) {
        headsetRouter.attachAudioManager(manager)
    }

    // Callback principal para entregar muestras de audio a detectores KWS o red
    @Volatile
    var onPcmData: ((ShortArray, Int) -> Unit)? = null

    // Listeners adicionales thread-safe (ej. entrenamiento de voz, diagnóstico)
    private val pcmListeners = CopyOnWriteArrayList<(ShortArray, Int) -> Unit>()

    fun addPcmListener(listener: (ShortArray, Int) -> Unit) {
        pcmListeners.addIfAbsent(listener)
    }

    fun removePcmListener(listener: (ShortArray, Int) -> Unit) {
        pcmListeners.remove(listener)
    }

    fun setPlaybackActive(active: Boolean) {
        adaptiveVad.isPlaybackActive = active
    }

    fun feedSoftwareAecRender(pcm48k: ShortArray, count: Int, playoutDelayMs: Int) {
        lastPlayoutDelayMs = playoutDelayMs.coerceIn(20, 300)
        if (softwareAecEnabled && aecEngine.isInitialized()) {
            aecEngine.feedRender48k(pcm48k, count, lastPlayoutDelayMs)
        }
    }

    fun resetSoftwareAec() {
        aecEngine.reset()
        lastErleDb = 0f
    }

    fun getDiagnosticsSnapshot(): AudioDiagnostics {
        return AudioDiagnostics(
            isRecording = isRecording,
            sampleRate = SAMPLE_RATE,
            audioSource = activeAudioSource,
            audioSessionId = captureAudioSessionId,
            aecActive = aec?.enabled == true,
            nsActive = ns?.enabled == true,
            agcActive = agc?.enabled == true,
            noiseFloorRms = adaptiveVad.noiseFloorRms,
            lastRms = _audioLevel.value.rms,
            lastSnrDb = lastSnrDb,
            readErrorCount = readErrorCount,
            swAec3Active = lastSwAecActive && softwareAecEnabled,
            aecDelayMs = lastPlayoutDelayMs,
            erleDb = lastErleDb
        )
    }

    external fun nativeInitAudio(sampleRate: Int, channels: Int): Boolean
    external fun nativeStartRecording(): Boolean
    external fun nativeReadPcm(buffer: ShortArray, numSamples: Int): Int
    external fun nativeCalculateRms(buffer: ShortArray, length: Int): Float
    external fun nativeStopRecording()

    /**
     * Inicia la captura continua de audio desde el micrófono en un hilo IO dedicado.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope, preferVoiceCommunication: Boolean = false) {
        synchronized(stateLock) {
            if (isRecording) {
                Log.d(TAG, "La captura de audio ya se encuentra activa.")
                return
            }

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            ).coerceAtLeast(FRAME_SAMPLES * 2)
            val targetBufferBytes = maxOf(minBufferSize * 2, FRAME_SAMPLES * 2 * 4)

            try {
                val headsetConnected = headsetRouter.hasHeadsetConnected()
                val primarySource = if (preferVoiceCommunication || headsetConnected) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }

                var record = AudioRecord(
                    primarySource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    targetBufferBytes
                )

                if (record.state != AudioRecord.STATE_INITIALIZED && primarySource != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                    TrekoLogBuffer.w(TAG, "VOICE_COMMUNICATION no disponible, intentando VOICE_RECOGNITION.")
                    record.release()
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        targetBufferBytes
                    )
                }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    TrekoLogBuffer.w(TAG, "VOICE_RECOGNITION no disponible, intentando con MIC estándar.")
                    record.release()
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        targetBufferBytes
                    )
                }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    TrekoLogBuffer.e(TAG, "No fue posible inicializar AudioRecord.")
                    record.release()
                    return
                }

                val sessionId = record.audioSessionId
                captureAudioSessionId = sessionId
                activeAudioSource = record.audioSource
                readErrorCount = 0

                val swReady = if (softwareAecEnabled) aecEngine.create() else false
                lastSwAecActive = swReady
                attachEffectsLocked(sessionId, skipHwAec = swReady)

                val route = headsetRouter.startRouting(record)
                record.startRecording()
                audioRecord = record
                isRecording = true
                TrekoLogBuffer.i(
                    TAG,
                    "Captura iniciada a $SAMPLE_RATE Hz (Fuente: ${record.audioSource}, SessionId: $sessionId, SW-AEC3: $swReady, Audífono: ${route.deviceLabel ?: "no"})."
                )

                recordingJob = scope.launch(Dispatchers.IO) {
                    val buffer = ShortArray(FRAME_SAMPLES)
                    val aecOutBuffer = ShortArray(FRAME_SAMPLES)
                    var consecutiveErrors = 0

                    while (isActive && isRecording) {
                        val activeRecord = audioRecord ?: break
                        val read = activeRecord.read(buffer, 0, buffer.size)

                        if (read > 0) {
                            consecutiveErrors = 0
                            val processedFrame = if (softwareAecEnabled && aecEngine.isInitialized()) {
                                val aecRes = aecEngine.processCapture16k(buffer, read, aecOutBuffer, lastPlayoutDelayMs)
                                lastSwAecActive = aecRes.aecApplied
                                if (aecRes.aecApplied) {
                                    lastErleDb = aecRes.erleDb
                                    aecOutBuffer
                                } else {
                                    buffer
                                }
                            } else {
                                lastSwAecActive = false
                                buffer
                            }
                            val metrics = adaptiveVad.process(processedFrame, read)
                            lastFrameMetrics = metrics
                            lastSnrDb = metrics.snrDb
                            updateLevel(metrics.rms, metrics.isSpeech, metrics.snrDb)

                            onPcmData?.invoke(processedFrame, read)
                            if (pcmListeners.isNotEmpty()) {
                                val copy = processedFrame.copyOf(read)
                                for (listener in pcmListeners) {
                                    listener(copy, read)
                                }
                            }
                        } else if (read < 0) {
                            consecutiveErrors++
                            readErrorCount++
                            TrekoLogBuffer.w(TAG, "AudioRecord.read error ($read), intento $consecutiveErrors")
                            if (consecutiveErrors >= 10) {
                                TrekoLogBuffer.e(TAG, "Demasiados errores consecutivos de AudioRecord ($read). Deteniendo captura.")
                                stopRecording()
                                break
                            }
                            delay(25L)
                        }
                    }
                }
            } catch (e: Exception) {
                TrekoLogBuffer.e(TAG, "Excepción al iniciar captura de micrófono", e)
                releaseResourcesLocked()
                isRecording = false
            }
        }
    }

    private fun attachEffectsLocked(sessionId: Int, skipHwAec: Boolean) {
        if (!skipHwAec && AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                TrekoLogBuffer.i(TAG, "AcousticEchoCanceler habilitado (sessionId=$sessionId).")
            } catch (e: Exception) {
                TrekoLogBuffer.w(TAG, "No fue posible habilitar AcousticEchoCanceler: ${e.message}")
            }
        } else if (skipHwAec) {
            TrekoLogBuffer.i(TAG, "AEC hardware omitido para ceder el control a WebRTC AEC3 por software.")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                TrekoLogBuffer.i(TAG, "NoiseSuppressor habilitado (sessionId=$sessionId).")
            } catch (e: Exception) {
                TrekoLogBuffer.w(TAG, "No fue posible habilitar NoiseSuppressor: ${e.message}")
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                TrekoLogBuffer.i(TAG, "AutomaticGainControl habilitado (sessionId=$sessionId).")
            } catch (e: Exception) {
                TrekoLogBuffer.w(TAG, "No fue posible habilitar AutomaticGainControl: ${e.message}")
            }
        }
    }

    /**
     * Detiene la captura de audio y libera los recursos del micrófono.
     */
    fun stopRecording() {
        synchronized(stateLock) {
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
            releaseResourcesLocked()
            captureAudioSessionId = 0
            lastSnrDb = 0f
            lastFrameMetrics = AdaptiveVad.FrameMetrics(0f, 0f, adaptiveVad.noiseFloorRms, 0f, false, 1f)
            _audioLevel.value = AudioLevel()
            TrekoLogBuffer.i(TAG, "Captura de micrófono detenida.")
        }
    }

    private fun releaseResourcesLocked() {
        try {
            headsetRouter.stopRouting()
        } catch (_: Exception) {
        }
        try {
            aecEngine.release()
        } catch (_: Exception) {
        }
        lastSwAecActive = false
        lastErleDb = 0f
        try {
            aec?.release()
            aec = null
            ns?.release()
            ns = null
            agc?.release()
            agc = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando efectos de audio: ${e.message}")
        }
        try {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    /**
     * Actualiza las bandas visuales del ecualizador reactivo en Canvas.
     */
    fun updateLevel(rms: Float, isSpeechOverride: Boolean? = null, snrDb: Float = 0f) {
        val level = buildAudioLevel(rms, isSpeechOverride ?: (rms > 0.025f), snrDb)
        _audioLevel.value = level
    }

    internal fun buildAudioLevel(rms: Float, isSpeaking: Boolean, snrDb: Float = 0f): AudioLevel {
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
        return AudioLevel(
            rms = rms,
            isSpeaking = isSpeaking,
            bands = bands,
            snrDb = snrDb
        )
    }
}
