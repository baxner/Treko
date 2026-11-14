package com.treko.core.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeakerBiometricTest {

    private fun makeUnitBasis(dim: Int, dominantIdx: Int, secondaryIdx: Int, secondaryWeight: Float): FloatArray {
        val vec = FloatArray(dim)
        vec[dominantIdx] = 1.0f
        vec[secondaryIdx] = secondaryWeight
        return SpeakerBiometricProfile.l2Normalize(vec)
    }

    @Test
    fun testProfileBuildsCentroidLeaveOneOutAndJsonRoundTrip() {
        val e1 = makeUnitBasis(192, 0, 1, 0.10f)
        val e2 = makeUnitBasis(192, 0, 2, 0.12f)
        val e3 = makeUnitBasis(192, 0, 3, 0.08f)

        val profile = SpeakerBiometricProfile.fromEmbeddings(listOf(e1, e2, e3))
        assertNotNull(profile)
        val p = profile!!
        assertEquals(192, p.dim)
        assertEquals(3, p.sampleEmbeddings.size)
        assertTrue("Leave-one-out self similarity (${p.minSelfSimilarity}) should be high", p.minSelfSimilarity > 0.90f)
        assertTrue(p.recommendedThreshold in SpeakerBiometricProfile.MIN_RECOMMENDED_THRESHOLD..SpeakerBiometricProfile.MAX_RECOMMENDED_THRESHOLD)

        val sameSpeaker = makeUnitBasis(192, 0, 4, 0.15f)
        val otherSpeaker = makeUnitBasis(192, 10, 11, 0.25f)
        assertTrue("Same speaker score must pass", p.score(sameSpeaker) > 0.90f)
        assertTrue("Different speaker score must be near zero", p.score(otherSpeaker) < 0.10f)
        assertTrue(p.isMatch(sameSpeaker))
        assertFalse(p.isMatch(otherSpeaker))

        val restored = SpeakerBiometricProfile.fromJson(p.toJson())
        assertEquals(p, restored)
    }

    @Test
    fun testBiometricTurnGatePreBuffersOpusAndFlushesOnVerify() {
        val gate = BiometricTurnGate()
        gate.startTurn(enabled = true)
        assertEquals(BiometricTurnGate.TurnAuthState.COLLECTING, gate.state)

        // Feed 0.48s voiced speech -> not enough yet for 0.80s trigger during active speech
        val chunk1 = ShortArray(7680) { 1000 }
        gate.onSpeechPcm(listOf(chunk1))
        val pkt1 = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertTrue("Opus must stay buffered prior to verification", gate.bufferOrPassOpus(pkt1).isEmpty())
        assertNull(gate.pollSnapshotForCheck(silenceDurationMs = 0L, isTurnEnding = false))

        // Feed +0.35s voiced speech -> crosses 0.80s threshold
        val chunk2 = ShortArray(5600) { 1200 }
        gate.onSpeechPcm(listOf(chunk2))
        val pkt2 = listOf(byteArrayOf(5, 6))
        assertTrue(gate.bufferOrPassOpus(pkt2).isEmpty())

        val snapshot = gate.pollSnapshotForCheck(silenceDurationMs = 0L, isTurnEnding = false)
        assertNotNull(snapshot)
        assertEquals(13280, snapshot!!.size)
        assertEquals(BiometricTurnGate.TurnAuthState.CHECKING, gate.state)

        // Simulate high speaker match
        val outcome = gate.applyCheckResult(similarity = 0.72f, threshold = 0.56f, isTurnEnding = false)
        assertEquals(BiometricTurnGate.CheckOutcome.VERIFIED, outcome)
        assertEquals(BiometricTurnGate.TurnAuthState.VERIFIED, gate.state)

        val flushed = gate.drainPendingOpus()
        assertEquals("All buffered pre-verify Opus packets must flush in order", 3, flushed.size)
        assertEquals(1.toByte(), flushed[0][0])
        assertEquals(5.toByte(), flushed[2][0])

        // Live packets pass straight through afterwards
        val pkt3 = listOf(byteArrayOf(7, 8))
        assertEquals(1, gate.bufferOrPassOpus(pkt3).size)
    }

    @Test
    fun testBiometricTurnGateRejectsTvVoiceAndRetriesBorderline() {
        val gate = BiometricTurnGate()
        gate.startTurn(enabled = true)

        gate.onSpeechPcm(listOf(ShortArray(13000) { 900 }))
        gate.bufferOrPassOpus(listOf(byteArrayOf(10, 11)))

        val snap1 = gate.pollSnapshotForCheck(silenceDurationMs = 0L, isTurnEnding = false)
        assertNotNull(snap1)

        // Borderline similarity (0.50 with threshold 0.56 -> within 0.08 band) asks for more speech up to 1.40s
        val outcome1 = gate.applyCheckResult(similarity = 0.50f, threshold = 0.56f, isTurnEnding = false)
        assertEquals(BiometricTurnGate.CheckOutcome.NEED_MORE_SPEECH, outcome1)
        assertEquals(BiometricTurnGate.TurnAuthState.COLLECTING, gate.state)

        // Add more voiced samples past 1.40s
        gate.onSpeechPcm(listOf(ShortArray(10000) { 900 }))
        val snap2 = gate.pollSnapshotForCheck(silenceDurationMs = 0L, isTurnEnding = false)
        assertNotNull(snap2)

        // Confirms TV actor mismatch -> REJECTED & discards queued packets
        val outcome2 = gate.applyCheckResult(similarity = 0.24f, threshold = 0.56f, isTurnEnding = false)
        assertEquals(BiometricTurnGate.CheckOutcome.REJECTED, outcome2)
        assertEquals(BiometricTurnGate.TurnAuthState.REJECTED, gate.state)
        assertTrue(gate.drainPendingOpus().isEmpty())
    }

    @Test
    fun testBiometricTurnGateUltraShortPolicy() {
        val gate = BiometricTurnGate()

        // Cinema auto-follow-up rejects tiny <0.45s ambient blip
        gate.startTurn(enabled = true)
        gate.onSpeechPcm(listOf(ShortArray(3200) { 500 }))
        assertEquals(BiometricTurnGate.CheckOutcome.REJECTED, gate.finalizeUltraShortTurn(strictBlockShortAmbient = true))

        // Manual / direct command allows tiny short reply like "Sí"
        gate.startTurn(enabled = true)
        gate.onSpeechPcm(listOf(ShortArray(3200) { 500 }))
        gate.bufferOrPassOpus(listOf(byteArrayOf(42)))
        assertEquals(BiometricTurnGate.CheckOutcome.VERIFIED, gate.finalizeUltraShortTurn(strictBlockShortAmbient = false))
        assertEquals(1, gate.drainPendingOpus().size)
    }

    @Test
    fun testTrainerAnalyzeTakeQuality() {
        val trainer = BiometricVoiceTrainer()
        fun tone(amp: Double, durationMs: Int): ShortArray {
            val n = 16000 * durationMs / 1000
            return ShortArray(n) { i -> (sin(2.0 * PI * 360.0 * i / 16000.0) * amp * 32767.0).toInt().toShort() }
        }

        assertEquals(BiometricVoiceTrainer.TakeQuality.OK, trainer.analyzeTake(tone(0.18, 1500), 0.005f).quality)
        assertEquals(BiometricVoiceTrainer.TakeQuality.TOO_SHORT, trainer.analyzeTake(tone(0.18, 400), 0.005f).quality)
        assertEquals(BiometricVoiceTrainer.TakeQuality.TOO_QUIET, trainer.analyzeTake(tone(0.015, 1500), 0.006f).quality)
    }
}
