package com.treko.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class TrekoAecEngineTest {

    @Test
    fun `decimator 48k to 16k preserves low frequency speech tone amplitude`() {
        // 1 kHz sine wave @ 48 kHz -> 1 kHz sine wave @ 16 kHz with near-unity gain in passband
        val in48k = ShortArray(2880) { i ->
            (10000.0 * sin(2.0 * PI * 1000.0 * i / 48000.0)).toInt().toShort()
        }
        val out16k = ShortArray(in48k.size / 3)
        val written = TrekoAecEngine.Decimator48To16.downsample3x(in48k, in48k.size, out16k)
        assertEquals(960, written)

        var rmsSum = 0.0
        // Skip first/last 20 boundary samples
        for (i in 20 until written - 20) {
            val s = out16k[i].toDouble()
            rmsSum += s * s
        }
        val rms = sqrt(rmsSum / (written - 40))
        val expectedRms = 10000.0 / sqrt(2.0)
        assertTrue("Expected passband RMS near $expectedRms, got $rms", abs(rms - expectedRms) < 500.0)
    }

    @Test
    fun `decimator attenuates high frequency aliasing content above 8 kHz`() {
        // 16 kHz tone @ 48 kHz would alias to 0 Hz without anti-alias FIR filter; FIR must strongly attenuate it
        val in48k = ShortArray(1440) { i ->
            (12000.0 * sin(2.0 * PI * 16000.0 * i / 48000.0)).toInt().toShort()
        }
        val out16k = ShortArray(in48k.size / 3)
        val written = TrekoAecEngine.Decimator48To16.downsample3x(in48k, in48k.size, out16k)
        assertEquals(480, written)

        var maxAbs = 0
        for (i in 20 until written - 20) {
            val v = abs(out16k[i].toInt())
            if (v > maxAbs) maxAbs = v
        }
        assertTrue("16 kHz alias energy should be attenuated below 600, got $maxAbs", maxAbs < 600)
    }

    @Test
    fun `stateful decimator handles odd chunk sizes continuously`() {
        val full = ShortArray(300) { i -> (8000.0 * sin(2.0 * PI * 440.0 * i / 48000.0)).toInt().toShort() }
        val stateful = TrekoAecEngine.StatefulDecimator48To16()
        val outA = ShortArray(100)
        val outB = ShortArray(100)

        // Chunk split not divisible by 3 (100 + 200 samples)
        val nA = stateful.downsample3x(full.copyOfRange(0, 100), 100, outA)
        val nB = stateful.downsample3x(full.copyOfRange(100, 300), 200, outB)
        assertEquals(33, nA)
        assertEquals(67, nB)
    }

    @Test
    fun `uninitialized engine safely bypasses capture on JVM without crashing`() {
        val engine = TrekoAecEngine()
        assertFalse(engine.isInitialized())

        val input = ShortArray(960) { i -> ((i * 37) % 1000).toShort() }
        val output = ShortArray(960)
        engine.feedRender48k(input, input.size, 80)
        val res = engine.processCapture16k(input, input.size, output, 80)

        assertFalse(res.aecApplied)
        assertArrayEquals(input, output)
        engine.reset()
        engine.release()
    }
}
