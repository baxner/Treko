package com.treko.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AdaptiveVadTest {

    private fun generateSine(freqHz: Double, amplitude: Double, samples: Int = 960, sampleRate: Int = 16000): ShortArray {
        return ShortArray(samples) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * amplitude * 32767.0).toInt().toShort()
        }
    }

    @Test
    fun testSilenceDoesNotTriggerSpeech() {
        val vad = AdaptiveVad(initialNoiseFloorRms = 0.005f, hangoverFrames = 3)
        val silence = ShortArray(960) { 0 }
        val metrics = vad.process(silence)
        assertFalse(metrics.isSpeech)
        assertEquals(0f, metrics.rms, 0.001f)
    }

    @Test
    fun testSpeechToneTriggersVadAndHangover() {
        val vad = AdaptiveVad(initialNoiseFloorRms = 0.005f, hangoverFrames = 2)
        val speech = generateSine(freqHz = 440.0, amplitude = 0.15)
        val silence = ShortArray(960) { 0 }

        val active = vad.process(speech)
        assertTrue("Speech frame must trigger VAD", active.isSpeech)
        assertTrue("SNR must be positive", active.snrDb > 10f)

        // Hangover frames keep VAD active briefly to avoid clipping trailing syllables
        assertTrue(vad.process(silence).isSpeech)
        assertTrue(vad.process(silence).isSpeech)
        assertFalse("After hangover expires, silence returns to non-speech", vad.process(silence).isSpeech)
    }

    @Test
    fun testNoiseFloorFreezesDuringPlayback() {
        val vad = AdaptiveVad(initialNoiseFloorRms = 0.004f)
        vad.isPlaybackActive = true
        val initialFloor = vad.noiseFloorRms
        val mildEcho = generateSine(freqHz = 200.0, amplitude = 0.015)

        repeat(10) { vad.process(mildEcho) }
        assertEquals("Noise floor must stay frozen while TTS playback is active", initialFloor, vad.noiseFloorRms, 1e-6f)
    }

    @Test
    fun testBuildAudioLevelBandsWithinRange() {
        val level = NativeAudioEngine.buildAudioLevel(0.25f, true)
        assertTrue(level.isSpeaking)
        assertEquals(5, level.bands.size)
        assertTrue(level.bands.all { it in 0.08f..1.0f })
    }

    @Test
    fun testCrispConsonantOnsetZcrIsAcceptedAsSpeech() {
        val vad = AdaptiveVad(initialNoiseFloorRms = 0.004f, hangoverFrames = 0)
        // ~4150 Hz has ZCR ≈ 0.52 (within widened 0.012..0.58 band for crisp Spanish t/tr/ch onsets)
        val consonantOnset = generateSine(freqHz = 4150.0, amplitude = 0.08)
        assertTrue("Crisp consonant onset ZCR must pass VAD gate", vad.process(consonantOnset).isSpeech)

        // Alternating Nyquist buzz (ZCR ≈ 1.0) should still be rejected
        val nyquistNoise = ShortArray(960) { i -> if (i % 2 == 0) 6000 else -6000 }
        assertFalse("Ultrasonic/Nyquist artifact must be rejected by ZCR guard", vad.process(nyquistNoise).isSpeech)
    }

    @Test
    fun testNearFieldGateSuppressesLowSnrTvAndFlushesPreRollOnCloseVoice() {
        val gate = NearFieldListeningGate(
            nearFieldEnabled = true,
            minSnrDb = 8.0f,
            preRollFramesCapacity = 3,
            hangoverFramesCount = 1,
            endOfSpeechSilenceMs = 950L
        )
        gate.reset(nowMs = 1000L)

        // Room/TV ambient murmur (~0.018 RMS over 0.010 noise floor -> ~5.1 dB SNR) should NOT start turn
        val tvChatter = generateSine(freqHz = 320.0, amplitude = 0.025)
        val tvMetrics = AdaptiveVad.FrameMetrics(
            rms = 0.018f,
            zcr = 0.05f,
            noiseFloorRms = 0.010f,
            snrDb = 5.1f,
            isSpeech = true,
            recommendedGain = 1.0f
        )

        val d1 = gate.evaluateFrame(tvChatter, tvChatter.size, tvMetrics, nowMs = 1060L)
        assertFalse("Low-SNR TV chatter must not open near-field speech", gate.speechStarted)
        assertFalse(d1.shouldEmitAudio)
        assertFalse(d1.shouldCloseTurnOnSilence)

        val d2 = gate.evaluateFrame(tvChatter, tvChatter.size, tvMetrics, nowMs = 1120L)
        assertFalse(gate.speechStarted)
        assertFalse(d2.shouldEmitAudio)

        // Close user speech (~0.18 RMS over 0.010 floor -> +25 dB SNR) opens turn & flushes pre-roll
        val userVoice = generateSine(freqHz = 400.0, amplitude = 0.25)
        val userMetrics = AdaptiveVad.FrameMetrics(
            rms = 0.18f,
            zcr = 0.06f,
            noiseFloorRms = 0.010f,
            snrDb = 25.0f,
            isSpeech = true,
            recommendedGain = 1.0f
        )
        val d3 = gate.evaluateFrame(userVoice, userVoice.size, userMetrics, nowMs = 1180L)
        assertTrue(gate.speechStarted)
        assertTrue(d3.speechStartedJustNow)
        assertTrue(d3.shouldEmitAudio)
        assertEquals("Must flush 2 pre-roll frames plus live frame", 3, d3.framesToEmit.size)

        // After 1s of only TV chatter, turn must close cleanly instead of hanging
        val d4 = gate.evaluateFrame(tvChatter, tvChatter.size, tvMetrics, nowMs = 2200L)
        assertTrue("Silence beyond 950ms must close turn despite low-SNR TV chatter", d4.shouldCloseTurnOnSilence)
    }

    @Test
    fun testBuildAudioLevelPreservesSnrDb() {
        val level = NativeAudioEngine.buildAudioLevel(rms = 0.12f, isSpeaking = true, snrDb = 16.5f)
        assertEquals(16.5f, level.snrDb, 0.001f)
    }
}
