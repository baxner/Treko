package com.treko.core.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Motor nativo de codificación y decodificación Opus acelerado por hardware (MediaCodec / c2.android.opus).
 * - Codificador: 16000 Hz mono (micrófono -> paquetes Opus a 60ms).
 * - Decodificador: Decodifica paquetes Opus a 48000 Hz PCM (tasa nativa de salida de c2.android.opus.decoder).
 * - Reproductor: AudioTrack a 48000 Hz con locks separados para evitar bloqueo de barge-in y nivel RMS reactivo.
 */
class OpusAudioCodec(
    val inputSampleRate: Int = 16000,
    var outputSampleRate: Int = 48000,
    audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
) {
    private val TAG = "TrekoOpus"

    @Volatile
    private var currentAudioSessionId: Int = audioSessionId

    @Volatile
    private var preferredOutputDevice: AudioDeviceInfo? = null

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val inputFrameSamples = inputSampleRate * 60 / 1000 // 960 muestras para 60ms a 16kHz
    private val sampleBuffer = ShortArray(inputFrameSamples)
    private var sampleCount = 0
    private var encoderPtsUs: Long = 0L

    private val encoderLock = Any()
    private val decoderLock = Any()
    private val trackLock = Any()

    @Volatile
    private var playbackGeneration: Long = 0L

    @Volatile
    private var isReleased: Boolean = false

    private val _playbackLevel = MutableStateFlow(AudioLevel())
    val playbackLevel: StateFlow<AudioLevel> = _playbackLevel.asStateFlow()

    @Volatile
    var onRenderPcmWritten: ((pcm48k: ShortArray, count: Int, playoutDelayMs: Int) -> Unit)? = null

    private var writtenPcmFrames: Long = 0L
    private val audioTimestamp = AudioTimestamp()
    private var renderShortScratch = ShortArray(4096)

    init {
        initEncoder()
        initDecoder()
        initAudioTrack(outputSampleRate)
    }

    private fun initEncoder() {
        try {
            val enc = MediaCodec.createEncoderByType("audio/opus")
            val format = MediaFormat.createAudioFormat("audio/opus", inputSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 24000)
                setInteger(MediaFormat.KEY_COMPLEXITY, 5)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc
            TrekoLogBuffer.i(TAG, "Codificador Opus inicializado a $inputSampleRate Hz.")
        } catch (e: Exception) {
            TrekoLogBuffer.e(TAG, "Error inicializando codificador Opus", e)
        }
    }

    private fun initDecoder() {
        try {
            val dec = MediaCodec.createDecoderByType("audio/opus")
            val decFormat = MediaFormat.createAudioFormat("audio/opus", 48000, 1)

            // CSD-0: OpusHead RFC 7845 a 48000 Hz (48000 LE = 0x80, 0xBB, 0x00, 0x00)
            val opusHead = byteArrayOf(
                'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
                'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
                1, // Versión 1
                1, // 1 canal mono
                0, 0, // Pre-skip
                0x80.toByte(), 0xBB.toByte(), 0, 0, // 48000 Hz LE
                0, 0, // Ganancia 0
                0 // Mapeo de canal
            )

            decFormat.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
            decFormat.setByteBuffer("csd-1", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0).array()))
            decFormat.setByteBuffer("csd-2", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0).array()))

            dec.configure(decFormat, null, null, 0)
            dec.start()
            decoder = dec
            TrekoLogBuffer.i(TAG, "Decodificador Opus inicializado a 48000 Hz.")
        } catch (e: Exception) {
            TrekoLogBuffer.e(TAG, "Error inicializando decodificador Opus", e)
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        synchronized(trackLock) {
            try {
                audioTrack?.release()
                outputSampleRate = sampleRate

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)

                val usage = if (preferredOutputDevice != null) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_ASSISTANT
                }

                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (currentAudioSessionId > 0) {
                    trackBuilder.setSessionId(currentAudioSessionId)
                }

                val track = trackBuilder.build()
                preferredOutputDevice?.let { dev ->
                    track.setPreferredDevice(dev)
                }
                track.setVolume(1.0f)
                track.play()
                audioTrack = track
                writtenPcmFrames = 0L
                TrekoLogBuffer.i(TAG, "AudioTrack configurado a $sampleRate Hz (sessionId=$currentAudioSessionId).")
            } catch (e: Exception) {
                TrekoLogBuffer.e(TAG, "Error iniciando AudioTrack a $sampleRate Hz", e)
            }
        }
    }

    /**
     * Reasocia el AudioTrack al sessionId de AudioRecord tras desmutear para mantener el AEC por hardware.
     */
    fun updateAudioSessionId(newSessionId: Int) {
        if (isReleased || newSessionId <= 0 || newSessionId == currentAudioSessionId) return
        currentAudioSessionId = newSessionId
        initAudioTrack(outputSampleRate)
    }

    /**
     * Fija o limpia el dispositivo de salida preferido (ej. audífono BLE/SCO/USB) y ajusta AudioAttributes.
     */
    fun setPreferredOutputDevice(device: AudioDeviceInfo?) {
        if (isReleased) return
        val wasHeadset = preferredOutputDevice != null
        val nowHeadset = device != null
        val prevId = preferredOutputDevice?.id
        preferredOutputDevice = device
        synchronized(trackLock) {
            val track = audioTrack ?: return
            if (wasHeadset != nowHeadset) {
                initAudioTrack(outputSampleRate)
            } else if (prevId != device?.id) {
                track.setPreferredDevice(device)
            }
        }
    }

    /**
     * Reinicia el buffer y los timestamps del codificador al iniciar una nueva interacción de voz.
     */
    fun resetEncoder() {
        synchronized(encoderLock) {
            sampleCount = 0
            encoderPtsUs = 0L
            try {
                encoder?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Excepción reseteando encoder: ${e.message}")
            }
        }
    }

    /**
     * Acumula muestras PCM de 16-bit a 16kHz y genera paquetes Opus de 60ms con timestamps estrictos.
     */
    fun encodePcm(samples: ShortArray, read: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        if (isReleased || read <= 0) return result

        synchronized(encoderLock) {
            val enc = encoder ?: return result
            val safeRead = minOf(read, samples.size)
            var offset = 0
            while (offset < safeRead) {
                val needed = inputFrameSamples - sampleCount
                val available = safeRead - offset
                val toCopy = minOf(needed, available)

                System.arraycopy(samples, offset, sampleBuffer, sampleCount, toCopy)
                sampleCount += toCopy
                offset += toCopy

                if (sampleCount == inputFrameSamples) {
                    queueFrame(enc, sampleBuffer, inputFrameSamples, result)
                    sampleCount = 0
                }
            }
            drainEncoder(enc, result)
        }
        return result
    }

    private fun queueFrame(enc: MediaCodec, frameSamples: ShortArray, count: Int, outResult: MutableList<ByteArray>) {
        try {
            var inIdx = enc.dequeueInputBuffer(10_000)
            if (inIdx < 0) {
                drainEncoder(enc, outResult)
                inIdx = enc.dequeueInputBuffer(10_000)
            }
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx) ?: return
                inBuf.clear()
                val shortBuf = inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                shortBuf.put(frameSamples, 0, count)
                val bytesSize = count * 2
                val pts = encoderPtsUs
                encoderPtsUs += (count * 1_000_000L) / inputSampleRate
                enc.queueInputBuffer(inIdx, 0, bytesSize, pts, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando frame al encoder Opus: ${e.message}")
        }
    }

    private fun drainEncoder(enc: MediaCodec, result: MutableList<ByteArray>) {
        try {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = enc.dequeueOutputBuffer(info, 0)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue
                }
                if (outIdx >= 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val packet = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(packet)
                        result.add(packet)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drenando encoder Opus: ${e.message}")
        }
    }

    /**
     * Envía las muestras restantes rellenadas con ceros para no cortar la última sílaba hablada.
     */
    fun flushEncoder(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        if (isReleased) return result

        synchronized(encoderLock) {
            val enc = encoder ?: return result
            if (sampleCount > 0) {
                java.util.Arrays.fill(sampleBuffer, sampleCount, inputFrameSamples, 0.toShort())
                queueFrame(enc, sampleBuffer, inputFrameSamples, result)
                sampleCount = 0
            }
            drainEncoder(enc, result)
        }
        return result
    }

    /**
     * Decodifica un paquete Opus recibido del servidor Xiaozhi y lo envía al altavoz a 48000 Hz.
     * Usa locks independientes del codificador para no bloquear el micrófono ni el barge-in.
     */
    fun decodeAndPlay(opusPacket: ByteArray) {
        if (isReleased || opusPacket.isEmpty()) return
        val genAtStart = playbackGeneration
        val pcmChunks = mutableListOf<ByteArray>()

        synchronized(decoderLock) {
            if (isReleased || genAtStart != playbackGeneration) return
            val dec = decoder ?: return

            try {
                val inIdx = dec.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val inBuf = dec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(opusPacket)
                        dec.queueInputBuffer(inIdx, 0, opusPacket.size, 0, 0)
                    }
                }

                val info = MediaCodec.BufferInfo()
                var outIdx = dec.dequeueOutputBuffer(info, 20_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val realRate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        Log.i(TAG, "Formato decodificador actualizado: $realRate Hz")
                        if (realRate != outputSampleRate) {
                            initAudioTrack(realRate)
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val pcmBytes = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(pcmBytes)
                            pcmChunks.add(pcmBytes)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = dec.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en decodeAndPlay: ${e.message}")
            }
        }

        if (pcmChunks.isEmpty() || genAtStart != playbackGeneration) return

        for (chunk in pcmChunks) {
            if (isReleased || genAtStart != playbackGeneration) break
            updatePlaybackLevelFromPcmBytes(chunk)
            var wroteSamples = 0
            var delayMsSnapshot = 80
            synchronized(trackLock) {
                if (genAtStart == playbackGeneration) {
                    val track = audioTrack
                    if (track != null) {
                        val writtenBytes = track.write(chunk, 0, chunk.size)
                        if (writtenBytes > 0) {
                            wroteSamples = writtenBytes / 2
                            writtenPcmFrames += wroteSamples.toLong()
                            delayMsSnapshot = computePlayoutDelayMsLocked(track)
                        }
                    }
                }
            }
            val renderCb = onRenderPcmWritten
            if (renderCb != null && wroteSamples > 0 && genAtStart == playbackGeneration) {
                val shorts = synchronized(trackLock) {
                    if (renderShortScratch.size < wroteSamples) {
                        renderShortScratch = ShortArray(wroteSamples)
                    }
                    for (i in 0 until wroteSamples) {
                        val lo = chunk[i * 2].toInt() and 0xFF
                        val hi = chunk[i * 2 + 1].toInt()
                        renderShortScratch[i] = ((hi shl 8) or lo).toShort()
                    }
                    renderShortScratch.copyOf(wroteSamples)
                }
                renderCb.invoke(shorts, wroteSamples, delayMsSnapshot)
            }
        }
    }

    private fun computePlayoutDelayMsLocked(track: AudioTrack): Int {
        val rate = outputSampleRate.coerceAtLeast(16000)
        val playedFrames = try {
            if (track.getTimestamp(audioTimestamp)) {
                audioTimestamp.framePosition.coerceAtLeast(0L)
            } else {
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }
        } catch (_: Exception) {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        val queuedFrames = (writtenPcmFrames - playedFrames).coerceAtLeast(0L)
        val rawDelayMs = ((queuedFrames * 1000L) / rate).toInt() + 25
        return rawDelayMs.coerceIn(20, 300)
    }

    fun getPlayoutDelayMs(): Int = synchronized(trackLock) {
        val track = audioTrack ?: return@synchronized 80
        computePlayoutDelayMsLocked(track)
    }

    /**
     * Espera a que el AudioTrack termine de drenar los frames pendientes antes de cerrar TTS.
     */
    suspend fun waitUntilPlaybackDrained(maxWaitMs: Long = 1400L) {
        val genAtStart = playbackGeneration
        val deadlineMs = System.currentTimeMillis() + maxWaitMs
        var lastHead = -1L
        var lastAdvanceMs = System.currentTimeMillis()
        val thresholdFrames = (outputSampleRate / 50).toLong() // <=20ms restante

        while (!isReleased && genAtStart == playbackGeneration) {
            val (written, head) = synchronized(trackLock) {
                val track = audioTrack
                val h = track?.let { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL } ?: writtenPcmFrames
                writtenPcmFrames to h
            }
            if (written <= 0L || (written - head) <= thresholdFrames) {
                break
            }
            val now = System.currentTimeMillis()
            if (head != lastHead) {
                lastHead = head
                lastAdvanceMs = now
            } else if (now - lastAdvanceMs >= 160L) {
                break
            }
            if (now >= deadlineMs) {
                break
            }
            delay(25L)
        }
        if (!isReleased && genAtStart == playbackGeneration) {
            delay(65L) // Cola acústica breve del altavoz antes de volcar complemento
        }
    }

    private fun updatePlaybackLevelFromPcmBytes(pcmBytes: ByteArray) {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount <= 0) return
        var sum = 0.0
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort() / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / sampleCount).toFloat()
        _playbackLevel.value = NativeAudioEngine.buildAudioLevel(rms, rms > 0.015f)
    }

    /**
     * Detiene la reproducción instantáneamente y limpia buffers de audio (para barge-in).
     */
    fun stopPlayback() {
        playbackGeneration++
        _playbackLevel.value = AudioLevel()

        synchronized(trackLock) {
            writtenPcmFrames = 0L
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (e: Exception) {
                Log.w(TAG, "Error en stopPlayback (AudioTrack): ${e.message}")
            }
        }
        synchronized(decoderLock) {
            try {
                decoder?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Error en stopPlayback (Decoder): ${e.message}")
            }
        }
    }

    fun release() {
        isReleased = true
        playbackGeneration++
        _playbackLevel.value = AudioLevel()

        synchronized(encoderLock) {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) { }
            encoder = null
        }

        synchronized(decoderLock) {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) { }
            decoder = null
        }

        synchronized(trackLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) { }
            audioTrack = null
        }

        TrekoLogBuffer.i(TAG, "Recursos OpusAudioCodec liberados.")
    }
}
