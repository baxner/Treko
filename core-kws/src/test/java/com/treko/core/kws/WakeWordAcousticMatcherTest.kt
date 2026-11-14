package com.treko.core.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class WakeWordAcousticMatcherTest {

    /**
     * Synthesizes a realistic harmonic human-like 2-syllable utterance (F0 pitch + harmonics 1..18
     * shaped by F1/F2 formant Gaussians, with consonant onset burst & intervocalic stop gap).
     */
    private fun synthesizeSpeechWord(
        f0Hz: Double,
        syl1Formants: Pair<Double, Double>,
        syl2Formants: Pair<Double, Double>,
        durationMs: Int = 460,
        amplitude: Double = 0.22,
        leadingSilenceMs: Int = 80,
        trailingSilenceMs: Int = 80,
        noiseFloorRms: Double = 0.002,
        seed: Long = 101L,
        sampleRate: Int = 16000
    ): ShortArray {
        val lead = (sampleRate * leadingSilenceMs) / 1000
        val speech = (sampleRate * durationMs) / 1000
        val trail = (sampleRate * trailingSilenceMs) / 1000
        val total = lead + speech + trail
        val raw = DoubleArray(total)
        val rng = Random(seed)

        for (i in 0 until speech) {
            val tNorm = i.toDouble() / speech
            val tSec = i.toDouble() / sampleRate
            val env = when {
                tNorm < 0.08 -> tNorm / 0.08
                tNorm in 0.44..0.54 -> 0.12
                tNorm > 0.90 -> (1.0 - tNorm) / 0.10
                else -> 1.0
            }
            val sig = if (tNorm < 0.07) {
                rng.nextGaussian() * 0.45
            } else {
                val (f1, f2) = if (tNorm < 0.48) syl1Formants else syl2Formants
                var acc = 0.0
                for (h in 1..18) {
                    val freq = f0Hz * h
                    if (freq > 4400.0) break
                    val g1 = exp(-0.5 * ((freq - f1) / 180.0) * ((freq - f1) / 180.0))
                    val g2 = exp(-0.5 * ((freq - f2) / 260.0) * ((freq - f2) / 260.0))
                    val rolloff = 1.0 / sqrt(h.toDouble())
                    acc += (0.18 + g1 + 0.8 * g2) * rolloff * sin(2.0 * PI * freq * tSec)
                }
                acc * 0.32
            }
            raw[lead + i] = sig * env * amplitude
        }

        return ShortArray(total) { idx ->
            val v = raw[idx] + rng.nextGaussian() * noiseFloorRms
            (v * 32767.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    @Test
    fun testFft512DetectsExpectedPeakBin() {
        val re = FloatArray(WakeWordAcousticMatcher.FFT_SIZE)
        val im = FloatArray(WakeWordAcousticMatcher.FFT_SIZE)
        val targetFreq = 1000.0 // bin k = 1000 * 512 / 16000 = 32
        for (i in 0 until WakeWordAcousticMatcher.FFT_SIZE) {
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (WakeWordAcousticMatcher.FFT_SIZE - 1))).toFloat()
            re[i] = (sin(2.0 * PI * targetFreq * i / 16000.0) * w).toFloat()
        }
        WakeWordAcousticMatcher.fft512(re, im)

        var maxBin = 0
        var maxMag = -1.0
        for (k in 0..WakeWordAcousticMatcher.FFT_SIZE / 2) {
            val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
            if (mag > maxMag) {
                maxMag = mag
                maxBin = k
            }
        }
        assertEquals(32, maxBin)
    }

    @Test
    fun testEnrollmentBuildsFourTemplatesAndMatchesSpanishStyleVariants() {
        // Enrolled "treco": /e/ (F1=500, F2=1850) -> /o/ (F1=440, F2=880)
        val take1 = synthesizeSpeechWord(135.0, 500.0 to 1850.0, 440.0 to 880.0, durationMs = 450, amplitude = 0.22, seed = 1L)
        val take2 = synthesizeSpeechWord(138.0, 510.0 to 1830.0, 445.0 to 900.0, durationMs = 470, amplitude = 0.19, seed = 2L)
        val take3 = synthesizeSpeechWord(132.0, 490.0 to 1870.0, 435.0 to 870.0, durationMs = 435, amplitude = 0.25, seed = 3L)

        val matcher = WakeWordAcousticMatcher.fromEnrolledSamples(
            keyword = "Treko",
            samplesList = listOf(take1, take2, take3),
            noiseFloorRms = 0.005f
        )
        assertNotNull(matcher)
        matcher!!

        // Expect 3 real takes + 1 synthetic noisy copy
        assertEquals(4, matcher.templates.size)
        assertTrue(
            "Threshold (${matcher.threshold}) must stay in [0.072, 0.088]",
            matcher.threshold in 0.072f..0.088f
        )

        // Quiet/noisy (~12dB SNR)/pitch-varied positive embedded inside 1.4s sliding window buffer
        val positiveInNoise = synthesizeSpeechWord(
            f0Hz = 140.0,
            syl1Formants = 505.0 to 1840.0,
            syl2Formants = 442.0 to 890.0,
            durationMs = 460,
            amplitude = 0.18,
            leadingSilenceMs = 420,
            trailingSilenceMs = 520,
            noiseFloorRms = 0.007,
            seed = 77L
        )
        val posDist = matcher.bestDistance(positiveInNoise, noiseFloorRms = 0.007f)
        assertTrue(
            "Positive in 1.4s sliding window should match (dist=$posDist <= thr=${matcher.threshold})",
            matcher.matches(positiveInNoise, noiseFloorRms = 0.007f)
        )

        // Near-homophone negative pattern (/a/ F1=780, F2=1250 -> /i/ F1=320, F2=2350)
        val negativeWord = synthesizeSpeechWord(
            f0Hz = 135.0,
            syl1Formants = 780.0 to 1250.0,
            syl2Formants = 320.0 to 2350.0,
            durationMs = 520,
            amplitude = 0.22,
            leadingSilenceMs = 200,
            trailingSilenceMs = 200,
            noiseFloorRms = 0.002,
            seed = 99L
        )
        val negDist = matcher.bestDistance(negativeWord, noiseFloorRms = 0.005f)
        assertTrue(
            "Distinct negative word ($negDist) should score above positive ($posDist) and threshold (${matcher.threshold})",
            negDist > matcher.threshold && negDist > posDist
        )
        assertFalse(matcher.matches(negativeWord, noiseFloorRms = 0.005f))
    }

    @Test
    fun testSnrGateRejectsAmbientNoiseAndJsonRoundTripWorks() {
        val take1 = synthesizeSpeechWord(135.0, 500.0 to 1850.0, 440.0 to 880.0, durationMs = 450, seed = 11L)
        val take2 = synthesizeSpeechWord(137.0, 505.0 to 1840.0, 442.0 to 890.0, durationMs = 460, seed = 12L)
        val take3 = synthesizeSpeechWord(133.0, 495.0 to 1860.0, 438.0 to 875.0, durationMs = 440, seed = 13L)

        val matcher = WakeWordAcousticMatcher.fromEnrolledSamples("Treko", listOf(take1, take2, take3))!!

        // Quiet background hiss below SNR gate must not match
        val quietHiss = ShortArray(16000) { i -> (sin(i * 0.3) * 120.0).toInt().toShort() }
        assertFalse(matcher.matches(quietHiss, noiseFloorRms = 0.005f))

        // Pure Kotlin JSON serialization round-trip preserves threshold & matching behavior
        val json = matcher.toJson()
        val restored = WakeWordAcousticMatcher.fromJson(json)
        assertNotNull(restored)
        restored!!
        assertEquals(matcher.keyword, restored.keyword)
        assertEquals(matcher.templates.size, restored.templates.size)
        assertEquals(matcher.threshold, restored.threshold, 1e-4f)
        assertEquals(
            matcher.bestDistance(take1, 0.005f),
            restored.bestDistance(take1, 0.005f),
            0.002f
        )
    }
}
