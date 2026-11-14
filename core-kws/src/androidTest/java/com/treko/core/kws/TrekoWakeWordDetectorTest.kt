package com.treko.core.kws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TrekoWakeWordDetectorTest {

    @Test
    fun testInitializeAndRecreateStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = TrekoWakeWordDetector(context)

        val initialized = detector.initialize("Treko", 3.0f, 0.05f)
        assertTrue("Detector must initialize successfully from assets", initialized)
        assertTrue(detector.isInitialized)

        // Hot-switch to custom wake word
        detector.updateKeywords("Jarvis", 3.5f, 0.08f)
        assertTrue(detector.isInitialized)

        // Hot-switch to accented custom wake word
        detector.updateKeywords("María", 2.5f, 0.04f)
        assertTrue(detector.isInitialized)

        detector.release()
        assertFalse(detector.isInitialized)
    }

    @Test
    fun testAcceptWaveformWithSyntheticAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = TrekoWakeWordDetector(context)
        detector.initialize("Treko", 3.0f, 0.05f)

        // Feed 500ms of 16kHz audio (8000 samples)
        val dummySamples = ShortArray(640) { 0 }
        for (i in 0 until 12) {
            detector.acceptWaveform(dummySamples, dummySamples.size)
        }

        detector.release()
    }

    @Test
    fun testConcurrentWaveformAndKeywordUpdateStress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = TrekoWakeWordDetector(context)
        detector.initialize("Treko", 3.0f, 0.05f)

        val running = AtomicBoolean(true)
        val latch = CountDownLatch(2)

        // Thread 1: audio capture simulator
        val audioThread = Thread {
            val buffer = ShortArray(640)
            while (running.get()) {
                detector.acceptWaveform(buffer, buffer.size)
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    break
                }
            }
            latch.countDown()
        }

        // Thread 2: rapid settings update simulator
        val updateThread = Thread {
            val keywords = listOf("Treko", "Jarvis", "Alexa", "María", "Niño")
            for (i in 0 until 40) {
                val kw = keywords[i % keywords.size]
                detector.updateKeywords(kw, 3.0f + (i % 3) * 0.5f, 0.05f)
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
            }
            running.set(false)
            latch.countDown()
        }

        audioThread.start()
        updateThread.start()

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent stress test must complete without deadlock or crash", completed)

        detector.release()
        assertFalse(detector.isInitialized)
    }

    @Test
    fun testNeuralSpeechDenoiserInitializesProcessesAndFlushes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denoiser = NeuralSpeechDenoiser(context)
        assertTrue("GTCRN denoiser must initialize from assets", denoiser.initialize())
        assertTrue(denoiser.isInitialized)

        val pcm = synthesizeVoicedSignal(samples = 16000, f0Hz = 140.0, f1Hz = 560.0, noiseAmp = 0.03)
        var emittedSamples = 0
        var offset = 0
        while (offset < pcm.size) {
            val chunkLen = minOf(960, pcm.size - offset)
            val chunk = pcm.copyOfRange(offset, offset + chunkLen)
            val out = denoiser.processPcm(chunk, chunk.size)
            emittedSamples += out.size
            offset += chunkLen
        }
        val tail = denoiser.flush()
        emittedSamples += tail.size

        assertTrue("Denoiser should emit aligned audio samples (got $emittedSamples)", emittedSamples >= 14000)
        assertTrue("Denoiser diagnostics should record processed frames", denoiser.getDiagnostics().totalFramesProcessed > 0L)

        denoiser.reset()
        denoiser.release()
        assertFalse(denoiser.isInitialized)
    }

    @Test
    fun testSpeakerBiometricEngineExtractsAndScoresEmbeddings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = SpeakerBiometricEngine(context)
        assertTrue("CAM++ speaker model must initialize from assets", engine.initialize())
        assertTrue(engine.isInitialized)
        assertEquals(192, engine.embeddingDim)

        val ownerTake1 = synthesizeVoicedSignal(samples = 22400, f0Hz = 128.0, f1Hz = 540.0, phaseShift = 0.0)
        val ownerTake2 = synthesizeVoicedSignal(samples = 22400, f0Hz = 129.5, f1Hz = 545.0, phaseShift = 0.6)
        val ownerTest = synthesizeVoicedSignal(samples = 19200, f0Hz = 128.5, f1Hz = 542.0, phaseShift = 1.2)
        val otherSpeaker = synthesizeVoicedSignal(samples = 19200, f0Hz = 270.0, f1Hz = 890.0, phaseShift = 0.3)

        val emb1 = engine.extractEmbedding(ownerTake1)
        val emb2 = engine.extractEmbedding(ownerTake2)
        assertNotNull("First owner embedding must be extracted", emb1)
        assertNotNull("Second owner embedding must be extracted", emb2)
        assertEquals(192, emb1!!.size)

        val profile = SpeakerBiometricProfile.fromEmbeddings(listOf(emb1, emb2!!))
        assertNotNull("Biometric profile must build from embeddings", profile)

        val sameResult = engine.verifyPcm(
            samples = ownerTest,
            profileOverride = profile,
            thresholdOverride = 0.50f,
            recordTurnStats = false
        )
        val diffResult = engine.verifyPcm(
            samples = otherSpeaker,
            profileOverride = profile,
            thresholdOverride = 0.50f,
            recordTurnStats = false
        )

        assertTrue(sameResult.isAvailable)
        assertTrue("Same speaker verification should pass (${sameResult.similarity})", sameResult.matched)
        assertTrue(
            "Same speaker similarity (${sameResult.similarity}) must exceed distinct speaker (${diffResult.similarity})",
            sameResult.similarity > diffResult.similarity
        )

        engine.release()
        assertFalse(engine.isInitialized)
    }

    private fun synthesizeVoicedSignal(
        samples: Int,
        f0Hz: Double,
        f1Hz: Double,
        phaseShift: Double = 0.0,
        noiseAmp: Double = 0.005
    ): ShortArray {
        val sr = 16000.0
        return ShortArray(samples) { n ->
            val t = n / sr
            val env = 0.75 + 0.25 * sin(2.0 * PI * 3.0 * t + phaseShift)
            val s1 = 0.50 * sin(2.0 * PI * f0Hz * t + phaseShift)
            val s2 = 0.30 * sin(2.0 * PI * (f0Hz * 2.0) * t + phaseShift)
            val s3 = 0.20 * sin(2.0 * PI * f1Hz * t)
            val pseudoNoise = (((n * 1103515245 + 12345) ushr 16 and 0x7FFF) / 32768.0 - 0.5) * 2.0 * noiseAmp
            val valNorm = ((s1 + s2 + s3) * env * 0.45 + pseudoNoise).coerceIn(-0.98, 0.98)
            (valNorm * 32767.0).toInt().toShort()
        }
    }
}
