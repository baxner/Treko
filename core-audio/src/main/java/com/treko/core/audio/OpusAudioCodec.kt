package com.treko.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor nativo de codificación y decodificación Opus acelerado por hardware (MediaCodec / c2.android.opus).
 * - Codificador: 16000 Hz mono (micrófono -> paquetes Opus a 60ms).
 * - Decodificador: Decodifica paquetes Opus a 48000 Hz PCM (tasa nativa de salida de c2.android.opus.decoder).
 * - Reproductor: AudioTrack a 48000 Hz con control de flujo para reproducción natural a velocidad 1.0x.
 */
class OpusAudioCodec(
    val inputSampleRate: Int = 16000,
    var outputSampleRate: Int = 48000
) {
    private val TAG = "TrekoOpus"

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val inputFrameSamples = inputSampleRate * 60 / 1000 // 960 muestras para 60ms a 16kHz
    private val sampleBuffer = ShortArray(inputFrameSamples)
    private var sampleCount = 0
    private var encoderPtsUs: Long = 0L

    private val lock = Any()

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
            Log.i(TAG, "Codificador Opus inicializado exitosamente a $inputSampleRate Hz.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando codificador Opus: ${e.message}", e)
        }
    }

    private fun initDecoder() {
        try {
            val dec = MediaCodec.createDecoderByType("audio/opus")
            // Android Opus decoder nativo opera y emite a 48000 Hz
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
            decFormat.setByteBuffer("csd-1", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).array()))
            decFormat.setByteBuffer("csd-2", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).array()))

            dec.configure(decFormat, null, null, 0)
            dec.start()
            decoder = dec
            Log.i(TAG, "Decodificador Opus inicializado con salida a 48000 Hz.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando decodificador Opus: ${e.message}", e)
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        try {
            audioTrack?.release()
            outputSampleRate = sampleRate

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                .build()

            track.play()
            audioTrack = track
            Log.i(TAG, "AudioTrack configurado y reproduciendo a $sampleRate Hz (velocidad normal 1.0x).")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando AudioTrack a $sampleRate Hz: ${e.message}", e)
        }
    }

    /**
     * Reinicia el buffer y los timestamps del codificador al iniciar una nueva interacción de voz.
     */
    fun resetEncoder() {
        synchronized(lock) {
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
        synchronized(lock) {
            val enc = encoder ?: return result
            var offset = 0
            while (offset < read) {
                val needed = inputFrameSamples - sampleCount
                val available = read - offset
                val toCopy = minOf(needed, available)

                System.arraycopy(samples, offset, sampleBuffer, sampleCount, toCopy)
                sampleCount += toCopy
                offset += toCopy

                if (sampleCount == inputFrameSamples) {
                    queueFrame(enc, sampleBuffer, inputFrameSamples)
                    sampleCount = 0
                }
            }
            drainEncoder(enc, result)
        }
        return result
    }

    private fun queueFrame(enc: MediaCodec, frameSamples: ShortArray, count: Int) {
        try {
            val inIdx = enc.dequeueInputBuffer(10_000)
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
        synchronized(lock) {
            val enc = encoder ?: return result
            if (sampleCount > 0) {
                java.util.Arrays.fill(sampleBuffer, sampleCount, inputFrameSamples, 0.toShort())
                queueFrame(enc, sampleBuffer, inputFrameSamples)
                sampleCount = 0
            }
            drainEncoder(enc, result)
        }
        return result
    }

    /**
     * Decodifica un paquete Opus recibido del servidor Xiaozhi y lo envía de inmediato al altavoz a 48000 Hz.
     */
    fun decodeAndPlay(opusPacket: ByteArray) {
        synchronized(lock) {
            val dec = decoder ?: return
            val track = audioTrack ?: return

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
                            outBuf.get(pcmBytes)
                            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = dec.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en decodeAndPlay: ${e.message}")
            }
        }
    }

    /**
     * Detiene la reproducción y limpia buffers de audio.
     */
    fun stopPlayback() {
        synchronized(lock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (e: Exception) {
                Log.w(TAG, "Error en stopPlayback: ${e.message}")
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) { /* ignore */ }
            encoder = null

            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) { /* ignore */ }
            decoder = null

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) { /* ignore */ }
            audioTrack = null

            Log.i(TAG, "Recursos OpusAudioCodec liberados.")
        }
    }
}
