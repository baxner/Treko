package com.treko.core.audio

import com.treko.core.common.model.FullDuplexIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDuplexComplementaryQueueTest {

    private fun speechMetrics(rms: Float = 0.08f, snrDb: Float = 15f): AdaptiveVad.FrameMetrics =
        AdaptiveVad.FrameMetrics(
            rms = rms,
            zcr = 0.08f,
            noiseFloorRms = 0.006f,
            snrDb = snrDb,
            isSpeech = true,
            recommendedGain = 1.0f
        )

    private fun silenceMetrics(): AdaptiveVad.FrameMetrics =
        AdaptiveVad.FrameMetrics(
            rms = 0.003f,
            zcr = 0.04f,
            noiseFloorRms = 0.005f,
            snrDb = 0f,
            isSpeech = false,
            recommendedGain = 1.0f
        )

    private fun frame(amp: Short = 1500): ShortArray = ShortArray(960) { amp }

    @Test
    fun `short backchannel below 420ms is discarded when silence follows`() {
        val queue = FullDuplexComplementaryQueue(minComplementarySpeechMs = 420L)
        queue.reset(biometricEnabled = false, nowMs = 0L)

        // 4 speech frames = 240ms ("sí" / "ajá")
        var t = 60L
        repeat(4) {
            queue.onSpeakingFrame(frame(), 960, speechMetrics(), playbackRms = 0.02f, nowMs = t)
            t += 60L
        }
        // >900ms silence closes burst
        repeat(20) {
            queue.onSpeakingFrame(frame(10), 960, silenceMetrics(), playbackRms = 0.02f, nowMs = t)
            t += 60L
        }

        val plan = queue.onPlaybackFinished()
        assertEquals(FullDuplexComplementaryQueue.FlushDecision.NONE, plan.decision)
        assertTrue(plan.pcmFrames.isEmpty())
    }

    @Test
    fun `completed complementary comment during TTS queues without abort and flushes complete utterance`() {
        val queue = FullDuplexComplementaryQueue(minComplementarySpeechMs = 420L)
        queue.reset(biometricEnabled = false, nowMs = 0L)

        // Initial silence -> pre-roll population
        var t = 60L
        repeat(3) {
            queue.onSpeakingFrame(frame(20), 960, silenceMetrics(), playbackRms = 0.02f, nowMs = t)
            t += 60L
        }
        // 10 speech frames = 600ms complementary addendum ("y revisa el portón")
        var sawCapture = false
        repeat(10) {
            val snap = queue.onSpeakingFrame(frame(), 960, speechMetrics(), playbackRms = 0.03f, nowMs = t)
            if (snap.indicator == FullDuplexIndicator.CAPTURING_COMPLEMENTARY) sawCapture = true
            t += 60L
        }
        assertTrue(sawCapture)

        // Silence until burst closes before TTS ends
        var sawQueued = false
        repeat(20) {
            val snap = queue.onSpeakingFrame(frame(15), 960, silenceMetrics(), playbackRms = 0.02f, nowMs = t)
            if (snap.indicator == FullDuplexIndicator.QUEUED_COMPLEMENTARY) sawQueued = true
            t += 60L
        }
        assertTrue(sawQueued)

        val plan = queue.onPlaybackFinished()
        assertEquals(FullDuplexComplementaryQueue.FlushDecision.SEND_COMPLETE_UTTERANCE, plan.decision)
        assertTrue("Expected pre-roll + voiced frames", plan.pcmFrames.size >= 10)
        assertEquals(600L, plan.totalVoicedMs)
    }

    @Test
    fun `ongoing speech when TTS finishes triggers seamless live handoff`() {
        val queue = FullDuplexComplementaryQueue(minComplementarySpeechMs = 420L)
        queue.reset(biometricEnabled = false, nowMs = 0L)

        // User started speaking only 240ms before TTS drains (4 frames < 420ms yet!)
        var t = 60L
        repeat(4) {
            queue.onSpeakingFrame(frame(), 960, speechMetrics(), playbackRms = 0.02f, nowMs = t)
            t += 60L
        }

        val plan = queue.onPlaybackFinished()
        assertEquals(FullDuplexComplementaryQueue.FlushDecision.HANDOFF_LIVE_LISTENING, plan.decision)
        assertEquals(4, plan.pcmFrames.size)
    }

    @Test
    fun `biometric rejection drops third party speech cleanly`() {
        val queue = FullDuplexComplementaryQueue(minComplementarySpeechMs = 420L)
        queue.reset(biometricEnabled = true, nowMs = 0L)

        val gen = queue.currentGeneration()
        var t = 60L
        var needsCheck = false
        repeat(12) {
            val snap = queue.onSpeakingFrame(frame(), 960, speechMetrics(), playbackRms = 0.02f, nowMs = t)
            if (snap.needsBiometricCheck) needsCheck = true
            t += 60L
        }
        assertTrue(needsCheck)
        assertFalse(queue.snapshotBiometricPcm(gen) == null)

        queue.setBiometricVerdict(gen, verified = false)
        val plan = queue.onPlaybackFinished()
        assertEquals(FullDuplexComplementaryQueue.FlushDecision.NONE, plan.decision)
        assertTrue(plan.pcmFrames.isEmpty())
    }
}
