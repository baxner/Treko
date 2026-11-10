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
 * - Decodificador: 24000 Hz mono (paquetes Opus del servidor Xiaozhi -> PCM 16-bit).
 * - Reproductor: AudioTrack de baja latencia a 24000 Hz.
 */
class OpusAudioCodec(
    val inputSampleRate: Int = 16000,
    val outputSampleRate: Int = 24000
) {
    private val TAG = "TrekoOpus"

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val inputFrameSamples = inputSampleRate * 60 / 1000 // 960 muestras para 60ms a 16kHz
    private val sampleBuffer = ShortArray(inputFrameSamples)
    private var sampleCount = 0

    private val lock = Any()

    init {
        initEncoder()
        initDecoder()
        initAudioTrack()
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
            val decFormat = MediaFormat.createAudioFormat("audio/opus", outputSampleRate, 1)

            // CSD-0: OpusHead RFC 7845 con tasa de muestreo de salida (24000 Hz LE = 0xC0, 0x5D, 0x00, 0x00)
            val rateByte0 = (outputSampleRate and 0xFF).toByte()
            val rateByte1 = ((outputSampleRate shr 8) and 0xFF).toByte()
            val rateByte2 = ((outputSampleRate shr 16) and 0xFF).toByte()
            val rateByte3 = ((outputSampleRate shr 24) and 0xFF).toByte()

            val opusHead = byteArrayOf(
                'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
                'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
                1, // Versión 1
                1, // 1 canal mono
                0, 0, // Pre-skip
                rateByte0, rateByte1, rateByte2, rateByte3,
                0, 0, // Ganancia 0
                0 // Mapeo de canal
            )

            decFormat.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
            decFormat.setByteBuffer("csd-1", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).array()))
            decFormat.setByteBuffer("csd-2", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).array()))

            dec.configure(decFormat, null, null, 0)
            dec.start()
            decoder = dec
            Log.i(TAG, "Decodificador Opus inicializado exitosamente a $outputSampleRate Hz.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando decodificador Opus: ${e.message}", e)
        }
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                outputSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outputSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track
            Log.i(TAG, "Reproductor AudioTrack iniciado a $outputSampleRate Hz.")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando AudioTrack: ${e.message}", e)
        }
    }

    /**
     * Acumula muestras PCM de 16-bit a 16kHz y genera paquetes Opus de 60ms según se completen.
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
                    val opusPacket = encodeSingleFrame(enc, sampleBuffer)
                    if (opusPacket != null) {
                        result.add(opusPacket)
                    }
                    sampleCount = 0
                }
            }
        }
        return result
    }

    private fun encodeSingleFrame(enc: MediaCodec, frameSamples: ShortArray): ByteArray? {
        try {
            val inIdx = enc.dequeueInputBuffer(20_000)
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx) ?: return null
                inBuf.clear()
                val byteBuffer = inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                byteBuffer.put(frameSamples)
                val bytesSize = frameSamples.size * 2
                enc.queueInputBuffer(inIdx, 0, bytesSize, 0, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = enc.dequeueOutputBuffer(info, 20_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outIdx = enc.dequeueOutputBuffer(info, 20_000)
            }

            if (outIdx >= 0) {
                val outBuf = enc.getOutputBuffer(outIdx)
                val packet = if (outBuf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    outBuf.get(data)
                    data
                } else null
                enc.releaseOutputBuffer(outIdx, false)
                return packet
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en encodeSingleFrame: ${e.message}")
        }
        return null
    }

    /**
     * Decodifica un paquete Opus recibido del servidor Xiaozhi y lo envía de inmediato al altavoz.
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
                    if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val pcmBytes = ByteArray(info.size)
                            outBuf.get(pcmBytes)
                            track.write(pcmBytes, 0, pcmBytes.size)
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
