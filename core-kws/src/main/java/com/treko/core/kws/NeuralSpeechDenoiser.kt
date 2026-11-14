package com.treko.core.kws

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.treko.core.common.util.TrekoLogBuffer
import kotlin.math.tanh

/**
 * Supresor neuronal de ruido en tiempo real basado en Sherpa-ONNX OnlineSpeechDenoiser (GTCRN).
 *
 * Limpia ruido estacionario, música de fondo y reverberación de sala en el dispositivo
 * antes de codificar Opus o verificar identidad biométrica.
 */
class NeuralSpeechDenoiser(private val context: Context) {

    data class DenoiserDiagnostics(
        val isInitialized: Boolean,
        val isEnabled: Boolean,
        val sampleRate: Int,
        val frameShiftSamples: Int,
        val totalFramesProcessed: Long
    )

    companion object {
        private const val TAG = "TrekoDenoiser"
        const val MODEL_ASSET_PATH = "models/enhance/gtcrn_simple.onnx"
        private const val EXPECTED_SAMPLE_RATE = 16000
    }

    private val lock = Any()
    private var denoiser: OnlineSpeechDenoiser? = null
    private var frameShiftSamples: Int = 256
    private var modelSampleRate: Int = EXPECTED_SAMPLE_RATE

    private var pendingBuffer = FloatArray(1024)
    private var pendingCount = 0

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    private var totalFramesProcessed: Long = 0L

    fun initialize(): Boolean {
        synchronized(lock) {
            if (isInitialized && denoiser != null) return true
            return try {
                val config = OnlineSpeechDenoiserConfig(
                    model = OfflineSpeechDenoiserModelConfig(
                        gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = MODEL_ASSET_PATH),
                        numThreads = 1,
                        debug = false,
                        provider = "cpu"
                    )
                )
                val instance = OnlineSpeechDenoiser(context.assets, config)
                modelSampleRate = instance.sampleRate.takeIf { it > 0 } ?: EXPECTED_SAMPLE_RATE
                frameShiftSamples = instance.frameShiftInSamples.coerceIn(64, 2048)
                denoiser = instance
                pendingCount = 0
                isInitialized = true
                TrekoLogBuffer.i(
                    TAG,
                    "GTCRN OnlineSpeechDenoiser initialized (${modelSampleRate}Hz, shift=$frameShiftSamples samples)."
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NeuralSpeechDenoiser", e)
                TrekoLogBuffer.e(TAG, "Failed to initialize GTCRN denoiser", e)
                isInitialized = false
                false
            }
        }
    }

    /**
     * Procesa un bloque PCM16 (16 kHz) manteniendo alineación exacta por frameShiftInSamples.
     * Si está deshabilitado o aún no inicializado, retorna una copia segura del PCM original.
     */
    fun processPcm(samples: ShortArray, count: Int = samples.size): ShortArray {
        if (count <= 0 || samples.isEmpty()) return ShortArray(0)
        val safeCount = minOf(count, samples.size)
        if (!isEnabled) return samples.copyOf(safeCount)

        synchronized(lock) {
            val active = denoiser ?: return samples.copyOf(safeCount)

            ensureCapacity(pendingCount + safeCount)
            for (i in 0 until safeCount) {
                pendingBuffer[pendingCount + i] = samples[i] / 32768.0f
            }
            pendingCount += safeCount

            val fullBlocks = pendingCount / frameShiftSamples
            if (fullBlocks <= 0) {
                return ShortArray(0)
            }

            val processCount = fullBlocks * frameShiftSamples
            val chunk = pendingBuffer.copyOfRange(0, processCount)

            val leftover = pendingCount - processCount
            if (leftover > 0) {
                System.arraycopy(pendingBuffer, processCount, pendingBuffer, 0, leftover)
            }
            pendingCount = leftover

            val enhanced = try {
                active.run(chunk, modelSampleRate).samples
            } catch (e: Exception) {
                Log.w(TAG, "Denoiser run error: ${e.message}")
                chunk
            }

            totalFramesProcessed++
            return floatToShortArray(enhanced)
        }
    }

    fun flush(): ShortArray {
        synchronized(lock) {
            val active = denoiser ?: return ShortArray(0)
            val resultFloats = ArrayList<Float>(pendingCount + frameShiftSamples)

            try {
                if (pendingCount > 0) {
                    val padded = FloatArray(frameShiftSamples)
                    val copyLen = minOf(pendingCount, frameShiftSamples)
                    System.arraycopy(pendingBuffer, 0, padded, 0, copyLen)
                    val tailOut = active.run(padded, modelSampleRate).samples
                    for (v in tailOut) resultFloats.add(v)
                    pendingCount = 0
                }
                val flushed = active.flush().samples
                for (v in flushed) resultFloats.add(v)
            } catch (e: Exception) {
                Log.w(TAG, "Denoiser flush error: ${e.message}")
            }

            return floatListToShortArray(resultFloats)
        }
    }

    fun reset() {
        synchronized(lock) {
            pendingCount = 0
            try {
                denoiser?.reset()
            } catch (_: Exception) {
            }
        }
    }

    fun getDiagnostics(): DenoiserDiagnostics {
        return DenoiserDiagnostics(
            isInitialized = isInitialized,
            isEnabled = isEnabled,
            sampleRate = modelSampleRate,
            frameShiftSamples = frameShiftSamples,
            totalFramesProcessed = totalFramesProcessed
        )
    }

    fun release() {
        synchronized(lock) {
            isInitialized = false
            pendingCount = 0
            try {
                denoiser?.release()
            } catch (_: Exception) {
            }
            denoiser = null
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= pendingBuffer.size) return
        var newSize = pendingBuffer.size * 2
        while (newSize < required) newSize *= 2
        pendingBuffer = pendingBuffer.copyOf(newSize)
    }

    private fun floatToShortArray(floats: FloatArray): ShortArray {
        return ShortArray(floats.size) { i ->
            val v = floats[i]
            val limited = if (v > 0.96f || v < -0.96f) tanh(v.toDouble()).toFloat() else v
            (limited * 32767.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun floatListToShortArray(floats: List<Float>): ShortArray {
        return ShortArray(floats.size) { i ->
            val v = floats[i]
            val limited = if (v > 0.96f || v < -0.96f) tanh(v.toDouble()).toFloat() else v
            (limited * 32767.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
