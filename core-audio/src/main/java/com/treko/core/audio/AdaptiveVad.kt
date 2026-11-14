package com.treko.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Adaptive Voice Activity Detector (VAD) + noise-floor tracker for 16 kHz mono PCM frames.
 *
 * Combines:
 * - DC-offset removal
 * - Asymmetric exponential noise-floor estimation (fast downward decay, slow upward adaptation)
 * - Zero-Crossing Rate (ZCR) guard to reject low-frequency bumps and broadband white hiss
 * - Configurable hangover frames to prevent choppy end-of-word dropouts
 * - Playback-aware freeze so assistant TTS echo doesn't inflate the ambient noise floor
 */
class AdaptiveVad(
    initialNoiseFloorRms: Float = 0.005f,
    private val hangoverFrames: Int = 4
) {
    data class FrameMetrics(
        val rms: Float,
        val zcr: Float,
        val noiseFloorRms: Float,
        val snrDb: Float,
        val isSpeech: Boolean,
        val recommendedGain: Float
    )

    @Volatile
    var noiseFloorRms: Float = initialNoiseFloorRms.coerceIn(0.0005f, 0.08f)
        private set

    @Volatile
    var isPlaybackActive: Boolean = false

    @Volatile
    var adaptiveEnabled: Boolean = true

    private var hangoverRemaining: Int = 0
    private var dcMean: Float = 0f

    fun reset(initialNoiseFloor: Float = 0.005f) {
        noiseFloorRms = initialNoiseFloor.coerceIn(0.0005f, 0.08f)
        hangoverRemaining = 0
        dcMean = 0f
    }

    /**
     * Removes DC offset in-place (optional) or non-destructively and evaluates speech activity.
     */
    fun process(samples: ShortArray, count: Int = samples.size): FrameMetrics {
        if (count <= 0 || samples.isEmpty()) {
            return FrameMetrics(0f, 0f, noiseFloorRms, 0f, false, 1.0f)
        }
        val n = minOf(count, samples.size)

        // 1. Estimate frame mean for DC offset removal
        var sum = 0.0
        for (i in 0 until n) {
            sum += samples[i] / 32768.0
        }
        val frameMean = (sum / n).toFloat()
        dcMean = 0.85f * dcMean + 0.15f * frameMean

        // 2. Compute AC-coupled RMS and Zero-Crossing Rate (ZCR)
        var energySum = 0.0
        var zeroCrossings = 0
        var prevSign = 0
        for (i in 0 until n) {
            val v = (samples[i] / 32768.0f) - dcMean
            energySum += v * v
            val sign = if (v >= 0f) 1 else -1
            if (prevSign != 0 && sign != prevSign) {
                zeroCrossings++
            }
            prevSign = sign
        }

        val rms = sqrt(energySum / n).toFloat()
        val zcr = if (n > 1) zeroCrossings.toFloat() / (n - 1) else 0f

        if (!adaptiveEnabled) {
            val fixedSpeech = rms > 0.025f
            val snr = computeSnrDb(rms, noiseFloorRms)
            return FrameMetrics(rms, zcr, noiseFloorRms, snr, fixedSpeech, computeGain(rms))
        }

        // 3. Adaptive speech threshold above tracked ambient noise floor
        // Speech generally has ZCR in [0.012, 0.58] (allowing crisp t/tr/ch onsets) and RMS >= max(0.011, noiseFloor * 2.4)
        val speechThreshold = max(0.011f, noiseFloorRms * 2.4f)
        val plausibleSpeechZcr = zcr in 0.012f..0.58f
        val instantSpeech = rms > speechThreshold && plausibleSpeechZcr

        val isSpeech = if (instantSpeech) {
            hangoverRemaining = hangoverFrames
            true
        } else if (hangoverRemaining > 0) {
            hangoverRemaining--
            true
        } else {
            false
        }

        // 4. Update noise floor only during confirmed silence and when TTS is not playing
        if (!instantSpeech && !isPlaybackActive) {
            val alpha = if (rms < noiseFloorRms) 0.18f else 0.03f
            noiseFloorRms = ((1f - alpha) * noiseFloorRms + alpha * rms).coerceIn(0.0005f, 0.08f)
        }

        val snrDb = computeSnrDb(rms, noiseFloorRms)
        return FrameMetrics(
            rms = rms,
            zcr = zcr,
            noiseFloorRms = noiseFloorRms,
            snrDb = snrDb,
            isSpeech = isSpeech,
            recommendedGain = computeGain(rms)
        )
    }

    private fun computeSnrDb(rms: Float, floor: Float): Float {
        val safeRms = max(rms, 1e-6f)
        val safeFloor = max(floor, 1e-5f)
        return (20f * log10(safeRms / safeFloor)).coerceIn(-20f, 60f)
    }

    /**
     * Soft software AGC target gain: gently lifts soft voices toward ~0.12 RMS while avoiding
     * amplifying pure silence or clipping loud close speech.
     */
    private fun computeGain(rms: Float): Float {
        if (rms <= noiseFloorRms * 1.4f) return 1.0f
        val targetRms = 0.12f
        return (targetRms / max(rms, 0.015f)).coerceIn(0.75f, 2.5f)
    }
}
