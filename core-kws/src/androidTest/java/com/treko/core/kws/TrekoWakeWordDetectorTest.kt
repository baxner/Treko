package com.treko.core.kws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
}
