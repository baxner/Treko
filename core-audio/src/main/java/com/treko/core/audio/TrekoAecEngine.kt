package com.treko.core.audio

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Wraps the native WebRTC AEC3 (EchoCanceller3) engine in `libtreko_audio.so`.
 *
 * Audio contract:
 * - Far-end TTS playback arrives at 48 kHz mono (decoded Opus) and is anti-alias decimated
 *   3:1 to 16 kHz mono before entering the native AEC3 render FIFO.
 * - Near-end mic capture arrives at 16 kHz mono (60 ms = 960 samples = 6 x 10 ms sub-frames).
 * - Safe JVM/desktop bypass: if the native library is unavailable (unit tests), initialization
 *   returns `false` and capture passes through unmodified.
 */
class TrekoAecEngine {

    data class ProcessResult(
        val aecApplied: Boolean,
        val erleDb: Float,
        val delayMs: Int
    )

    object Decimator48To16 {
        // 15-tap linear-phase Hamming-windowed low-pass FIR, cutoff = 7.2 kHz @ 48 kHz (fc = 0.15)
        internal val TAPS: FloatArray = buildLowPassTaps(numTaps = 15, cutoffNorm = 0.15)

        private fun buildLowPassTaps(numTaps: Int, cutoffNorm: Double): FloatArray {
            val half = (numTaps - 1) / 2
            val raw = DoubleArray(numTaps)
            var sum = 0.0
            for (i in 0 until numTaps) {
                val n = i - half
                val sinc = if (n == 0) {
                    2.0 * cutoffNorm
                } else {
                    sin(2.0 * PI * cutoffNorm * n) / (PI * n)
                }
                val window = 0.54 - 0.46 * cos(2.0 * PI * i / (numTaps - 1))
                val v = sinc * window
                raw[i] = v
                sum += v
            }
            return FloatArray(numTaps) { idx -> (raw[idx] / sum).toFloat() }
        }

        /**
         * Stateless 3:1 anti-aliased decimation (48 kHz -> 16 kHz).
         * Returns number of 16 kHz output samples written (`floor(count / 3)`).
         */
        fun downsample3x(pcm48k: ShortArray, count: Int, out16k: ShortArray): Int {
            val safeCount = minOf(count, pcm48k.size).coerceAtLeast(0)
            val outCount = minOf(safeCount / 3, out16k.size)
            if (outCount <= 0) return 0

            val taps = TAPS
            val half = (taps.size - 1) / 2
            for (outIdx in 0 until outCount) {
                val center = outIdx * 3
                var acc = 0f
                for (k in taps.indices) {
                    val srcIdx = (center + k - half).coerceIn(0, safeCount - 1)
                    acc += pcm48k[srcIdx] * taps[k]
                }
                out16k[outIdx] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return outCount
        }
    }

    /**
     * Stateful 48->16 kHz FIR decimator preserving inter-frame boundary continuity across Opus chunks.
     */
    internal class StatefulDecimator48To16 {
        private val taps = Decimator48To16.TAPS
        private val history = ShortArray(taps.size - 1)
        private var residual48k = ShortArray(2)
        private var residualLen = 0

        fun reset() {
            history.fill(0)
            residualLen = 0
        }

        fun downsample3x(pcm48k: ShortArray, count: Int, out16k: ShortArray): Int {
            val safeCount = minOf(count, pcm48k.size).coerceAtLeast(0)
            if (safeCount == 0 || out16k.isEmpty()) return 0

            val total = residualLen + safeCount
            val outCount = minOf(total / 3, out16k.size)
            if (outCount == 0) {
                for (i in 0 until safeCount) {
                    if (residualLen < residual48k.size) {
                        residual48k[residualLen++] = pcm48k[i]
                    }
                }
                return 0
            }

            val half = (taps.size - 1) / 2
            for (outIdx in 0 until outCount) {
                val center = outIdx * 3
                var acc = 0f
                for (k in taps.indices) {
                    val virtualIdx = center + k - half
                    val sample = when {
                        virtualIdx < 0 -> {
                            val hIdx = history.size + virtualIdx
                            if (hIdx in history.indices) history[hIdx] else history[0]
                        }
                        virtualIdx < residualLen -> residual48k[virtualIdx]
                        else -> {
                            val srcIdx = virtualIdx - residualLen
                            if (srcIdx < safeCount) pcm48k[srcIdx] else pcm48k[safeCount - 1]
                        }
                    }
                    acc += sample * taps[k]
                }
                out16k[outIdx] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            // Update FIR tail history with the last `history.size` input samples consumed
            val consumedTotal = outCount * 3
            val tailCopy = minOf(history.size, consumedTotal)
            if (tailCopy < history.size) {
                System.arraycopy(history, tailCopy, history, 0, history.size - tailCopy)
            }
            for (i in 0 until tailCopy) {
                val vIdx = consumedTotal - tailCopy + i
                history[history.size - tailCopy + i] = if (vIdx < residualLen) {
                    residual48k[vIdx]
                } else {
                    pcm48k[(vIdx - residualLen).coerceIn(0, safeCount - 1)]
                }
            }

            // Keep any leftover (<3) samples at the end of the block for the next chunk
            val newResidual = total - consumedTotal
            for (i in 0 until newResidual) {
                val vIdx = consumedTotal + i
                residual48k[i] = if (vIdx < residualLen) residual48k[vIdx] else pcm48k[vIdx - residualLen]
            }
            residualLen = newResidual
            return outCount
        }
    }

    private val lock = ReentrantReadWriteLock()
    private val renderDecimator = StatefulDecimator48To16()
    private var render16kScratch = ShortArray(2048)
    private val metricsScratch = FloatArray(2)

    @Volatile
    private var nativeHandle: Long = 0L

    fun create(): Boolean = lock.write {
        if (nativeHandle != 0L) return@write true
        if (!ensureNativeLoaded()) return@write false
        return@write try {
            nativeHandle = nativeCreate()
            renderDecimator.reset()
            nativeHandle != 0L
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "AEC3 JNI symbols unavailable; running bypass: ${e.message}")
            nativeHandle = 0L
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create WebRTC AEC3 instance: ${e.message}", e)
            nativeHandle = 0L
            false
        }
    }

    fun isInitialized(): Boolean = nativeHandle != 0L

    fun feedRender48k(pcm48k: ShortArray, count: Int, playoutDelayMs: Int) {
        val safeCount = minOf(count, pcm48k.size).coerceAtLeast(0)
        if (safeCount == 0) return

        lock.read {
            val handle = nativeHandle
            if (handle == 0L) return

            val maxOut = safeCount / 3 + 2
            val out16k = synchronized(renderDecimator) {
                if (render16kScratch.size < maxOut) {
                    render16kScratch = ShortArray(maxOut)
                }
                val n16 = renderDecimator.downsample3x(pcm48k, safeCount, render16kScratch)
                if (n16 <= 0) return
                try {
                    nativeFeedRender16k(handle, render16kScratch, n16, playoutDelayMs)
                } catch (_: UnsatisfiedLinkError) {
                } catch (e: Throwable) {
                    Log.w(TAG, "feedRender48k error: ${e.message}")
                }
            }
        }
    }

    fun processCapture16k(
        capture16k: ShortArray,
        count: Int,
        outClean16k: ShortArray,
        delayMs: Int
    ): ProcessResult {
        val safeCount = minOf(count, capture16k.size, outClean16k.size).coerceAtLeast(0)
        if (safeCount == 0) return ProcessResult(aecApplied = false, erleDb = 0f, delayMs = delayMs)

        return lock.read {
            val handle = nativeHandle
            if (handle == 0L) {
                System.arraycopy(capture16k, 0, outClean16k, 0, safeCount)
                return@read ProcessResult(aecApplied = false, erleDb = 0f, delayMs = delayMs)
            }

            try {
                metricsScratch[0] = 0f
                metricsScratch[1] = delayMs.toFloat()
                val applied = nativeProcessCapture16k(
                    handle = handle,
                    capture16k = capture16k,
                    count = safeCount,
                    delayMs = delayMs,
                    outClean16k = outClean16k,
                    outMetrics = metricsScratch
                )
                ProcessResult(
                    aecApplied = applied,
                    erleDb = metricsScratch[0],
                    delayMs = metricsScratch[1].roundToInt()
                )
            } catch (_: UnsatisfiedLinkError) {
                System.arraycopy(capture16k, 0, outClean16k, 0, safeCount)
                ProcessResult(aecApplied = false, erleDb = 0f, delayMs = delayMs)
            } catch (e: Throwable) {
                Log.w(TAG, "processCapture16k error; bypassing frame: ${e.message}")
                System.arraycopy(capture16k, 0, outClean16k, 0, safeCount)
                ProcessResult(aecApplied = false, erleDb = 0f, delayMs = delayMs)
            }
        }
    }

    fun reset() {
        lock.read {
            synchronized(renderDecimator) {
                renderDecimator.reset()
            }
            val handle = nativeHandle
            if (handle != 0L) {
                try {
                    nativeReset(handle)
                } catch (_: UnsatisfiedLinkError) {
                }
            }
        }
    }

    fun release() {
        lock.write {
            val handle = nativeHandle
            nativeHandle = 0L
            synchronized(renderDecimator) {
                renderDecimator.reset()
            }
            if (handle != 0L) {
                try {
                    nativeDestroy(handle)
                } catch (_: UnsatisfiedLinkError) {
                }
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeFeedRender16k(handle: Long, render16k: ShortArray, count: Int, playoutDelayMs: Int)
    private external fun nativeProcessCapture16k(
        handle: Long,
        capture16k: ShortArray,
        count: Int,
        delayMs: Int,
        outClean16k: ShortArray,
        outMetrics: FloatArray
    ): Boolean

    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "TrekoAecEngine"

        @Volatile
        private var nativeLibLoaded = false

        private fun ensureNativeLoaded(): Boolean {
            if (nativeLibLoaded) return true
            return try {
                System.loadLibrary("treko_audio")
                nativeLibLoaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Throwable) {
                false
            }
        }
    }
}
