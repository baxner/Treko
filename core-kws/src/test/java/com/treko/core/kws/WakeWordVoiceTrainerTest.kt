package com.treko.core.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeWordVoiceTrainerTest {

    private fun synthTone(freqHz: Double, amplitude: Double, durationMs: Int, sampleRate: Int = 16000): ShortArray {
        val count = (sampleRate * durationMs) / 1000
        return ShortArray(count) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * amplitude * 32767.0).toInt().toShort()
        }
    }

    @Test
    fun testSampleQualityChecks() {
        val trainer = WakeWordVoiceTrainer(targetSamples = 3)
        val floor = 0.005f

        val okSample = synthTone(380.0, 0.18, 600)
        assertEquals(WakeWordVoiceTrainer.SampleQuality.OK, trainer.analyzeSample(okSample, floor).quality)

        val quietSample = synthTone(380.0, 0.003, 600)
        assertEquals(WakeWordVoiceTrainer.SampleQuality.TOO_QUIET, trainer.analyzeSample(quietSample, floor).quality)

        val shortSample = synthTone(380.0, 0.18, 80)
        assertEquals(WakeWordVoiceTrainer.SampleQuality.TOO_SHORT, trainer.analyzeSample(shortSample, floor).quality)

        val longSample = synthTone(380.0, 0.18, 2800)
        assertEquals(WakeWordVoiceTrainer.SampleQuality.TOO_LONG, trainer.analyzeSample(longSample, floor).quality)

        val clippedSample = ShortArray(9600) { 32700 }
        assertEquals(WakeWordVoiceTrainer.SampleQuality.CLIPPED, trainer.analyzeSample(clippedSample, floor).quality)

        assertEquals(WakeWordVoiceTrainer.SampleQuality.TOO_NOISY, trainer.analyzeSample(okSample, 0.10f).quality)
    }

    @Test
    fun testMultiSnrAugmentationProduces4xSamples() {
        val trainer = WakeWordVoiceTrainer()
        val samples = listOf(synthTone(420.0, 0.15, 500), synthTone(440.0, 0.18, 550))
        val augmented = trainer.buildAugmentedDataset(samples, noiseFloorRms = 0.005f)
        assertEquals(8, augmented.size)
    }

    @Test
    fun testEmpiricalCalibrationSelectsOptimalThresholdWithoutFalseTriggers() {
        val trainer = WakeWordVoiceTrainer()
        val takes = listOf(
            synthTone(400.0, 0.15, 500),
            synthTone(410.0, 0.16, 520),
            synthTone(395.0, 0.14, 480)
        )
        val noise = synthTone(120.0, 0.004, 1200)

        // Synthetic evaluator: triggers speech if threshold <= 0.07f; triggers noise if threshold <= 0.04f
        val result = trainer.calibrate(
            keyword = "Treko",
            noiseFloorRms = 0.004f,
            recordedSamples = takes,
            noiseSample = noise
        ) { batch, _, _, threshold ->
            if (batch.size == 1 && batch[0].size == noise.size) {
                if (threshold <= 0.04f) 1 else 0
            } else {
                if (threshold <= 0.07f) batch.size else 0
            }
        }

        assertEquals(1.0f, result.augmentedRecall, 0.01f)
        assertEquals(0, result.falseTriggerCount)
        assertEquals(0.07f, result.recommendedThreshold, 0.001f)
        assertNotNull(result.voicePrint)
    }

    @Test
    fun testVoicePrintSimilarityAndJsonRoundTrip() {
        val s1 = synthTone(350.0, 0.15, 500)
        val s2 = synthTone(355.0, 0.16, 510)
        val distinct = synthTone(2800.0, 0.15, 500)

        val vp = WakeWordVoicePrint.fromSamples("Treko", listOf(s1, s2), 0.11f)
        assertNotNull(vp)
        val simSame = vp!!.similarityToPcm(s1)
        val simOther = vp.similarityToPcm(distinct)
        assertTrue("Similar voice sample ($simSame) must score higher than distinct ($simOther)", simSame > simOther)

        val restored = WakeWordVoicePrint.fromJson(vp.toJson())
        assertNotNull(restored)
        assertEquals(vp.keyword, restored!!.keyword)
        assertEquals(vp.sampleCount, restored.sampleCount)
        assertEquals(1.0f, vp.similarityTo(restored), 0.001f)
    }

    @Test
    fun testTrainerSessionStateMachine() {
        val trainer = WakeWordVoiceTrainer(targetSamples = 2)
        trainer.startSession("Treko")
        assertEquals(WakeWordVoiceTrainer.TrainingStage.MEASURING_NOISE_FLOOR, trainer.state.value.stage)

        trainer.onPcmFrame(synthTone(100.0, 0.003, 1500))
        val floor = trainer.finishNoiseFloorMeasurement()
        assertTrue(floor < 0.01f)
        assertEquals(WakeWordVoiceTrainer.TrainingStage.RECORDING_SAMPLE, trainer.state.value.stage)

        trainer.onPcmFrame(synthTone(420.0, 0.15, 500))
        assertEquals(WakeWordVoiceTrainer.SampleQuality.OK, trainer.finishSampleCapture().quality)
        assertEquals(1, trainer.state.value.currentSampleIndex)

        trainer.beginSampleCapture()
        trainer.onPcmFrame(synthTone(430.0, 0.16, 520))
        assertEquals(WakeWordVoiceTrainer.SampleQuality.OK, trainer.finishSampleCapture().quality)
        assertEquals(WakeWordVoiceTrainer.TrainingStage.CALIBRATING, trainer.state.value.stage)

        val cal = trainer.runCalibration()
        assertEquals(WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE, trainer.state.value.stage)
        assertTrue(cal.recommendedBoost in 1.0f..5.0f)

        trainer.markCompleted()
        assertEquals(WakeWordVoiceTrainer.TrainingStage.COMPLETED, trainer.state.value.stage)
    }

    @Test
    fun testCalibrationNeverDegradesWhenZipformerHasZeroHits() {
        val trainer = WakeWordVoiceTrainer()
        fun synthFormantWord(f0: Double, durationMs: Int, amp: Double): ShortArray {
            val count = (16000 * durationMs) / 1000
            return ShortArray(count) { i ->
                val t = i.toDouble() / 16000.0
                val half = i < count / 2
                val f1 = if (half) 500.0 else 440.0
                val f2 = if (half) 1850.0 else 880.0
                var acc = 0.0
                for (h in 1..16) {
                    val freq = f0 * h
                    if (freq > 4400.0) break
                    val g1 = kotlin.math.exp(-0.5 * ((freq - f1) / 180.0) * ((freq - f1) / 180.0))
                    val g2 = kotlin.math.exp(-0.5 * ((freq - f2) / 260.0) * ((freq - f2) / 260.0))
                    acc += (0.2 + g1 + 0.8 * g2) / kotlin.math.sqrt(h.toDouble()) * sin(2.0 * PI * freq * t)
                }
                (acc * 0.30 * amp * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        val takes = listOf(
            synthFormantWord(135.0, 480, 0.20),
            synthFormantWord(137.0, 500, 0.19),
            synthFormantWord(133.0, 460, 0.21)
        )
        val noise = synthTone(120.0, 0.004, 1200)

        // Simulate Spanish "treco" on English Zipformer: Zipformer gets 0 hits at every setting
        var evalCalls = 0
        val result = trainer.calibrate(
            keyword = "Treko",
            noiseFloorRms = 0.004f,
            recordedSamples = takes,
            noiseSample = noise
        ) { _, _, _, _ ->
            evalCalls++
            0
        }

        // Fast 8-point schedule should evaluate at most 8 times
        assertTrue("Expected <= 8 Zipformer sweep calls, got $evalCalls", evalCalls <= 8)
        // Must keep safe Spanish baseline (3.5f, 0.04f) and never degrade to (2.6f, 0.12f)
        assertEquals(3.5f, result.recommendedBoost, 0.001f)
        assertEquals(0.04f, result.recommendedThreshold, 0.001f)
        assertNotNull(result.acousticMatcher)
        assertTrue("Combined DTW recall must be > 0", result.augmentedRecall > 0.5f)
    }
}
