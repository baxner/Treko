package com.treko.core.common

import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.model.TimerMode
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.util.TrekoLogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrekoCommonTest {

    @Test
    fun testGeneratedMacIsValidUnicastAndUnique() {
        val macRegex = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        val mac1 = TrekoPreferences.generateRandomMacAddress()
        val mac2 = TrekoPreferences.generateRandomMacAddress()

        assertTrue("MAC must match xx:xx:xx:xx:xx:xx", macRegex.matches(mac1))
        assertTrue("MAC must match xx:xx:xx:xx:xx:xx", macRegex.matches(mac2))
        assertNotEquals("Each call should produce a distinct MAC", mac1, mac2)

        val firstByte = mac1.substringBefore(":").toInt(16)
        assertTrue("Locally-administered bit (0x02) must be set", (firstByte and 0x02) != 0)
        assertEquals("Multicast bit (0x01) must be cleared", 0, firstByte and 0x01)
    }

    @Test
    fun testGeneratedHmacKeyIs256BitHexAndUnique() {
        val hexRegex = Regex("^[0-9a-f]{64}$")
        val k1 = TrekoPreferences.generateRandomHmacKey()
        val k2 = TrekoPreferences.generateRandomHmacKey()

        assertTrue(hexRegex.matches(k1))
        assertTrue(hexRegex.matches(k2))
        assertNotEquals(k1, k2)
    }

    @Test
    fun testTimerModeLocalizedTitles() {
        assertEquals("Pomodoro (25m)", TimerMode.POMODORO.localizedTitle(isSpanish = false))
        assertEquals("Short Break (5m)", TimerMode.SHORT_BREAK.localizedTitle(isSpanish = false))
        assertEquals("Descanso (5m)", TimerMode.SHORT_BREAK.localizedTitle(isSpanish = true))
        assertEquals("Long Break (15m)", TimerMode.LONG_BREAK.localizedTitle(isSpanish = false))
        assertEquals("Custom", TimerMode.CUSTOM.localizedTitle(isSpanish = false))
    }

    @Test
    fun testTimerManagerStateTransitions() {
        TrekoTimerManager.setMode(TimerMode.SHORT_BREAK)
        assertEquals(TimerMode.SHORT_BREAK, TrekoTimerManager.timerState.value.selectedMode)
        assertEquals(5 * 60, TrekoTimerManager.timerState.value.remainingSeconds)
        assertFalse(TrekoTimerManager.timerState.value.isRunning)

        TrekoTimerManager.start()
        assertTrue(TrekoTimerManager.timerState.value.isRunning)

        TrekoTimerManager.pause()
        assertFalse(TrekoTimerManager.timerState.value.isRunning)

        TrekoTimerManager.reset()
        assertEquals(5 * 60, TrekoTimerManager.timerState.value.remainingSeconds)
        assertFalse(TrekoTimerManager.timerState.value.isRunning)
    }

    @Test
    fun testLogBufferBoundedAndClearable() {
        TrekoLogBuffer.clear()
        for (i in 1..250) {
            TrekoLogBuffer.i("TestTag", "Message $i")
        }
        val entries = TrekoLogBuffer.entries.value
        assertEquals(200, entries.size)
        assertEquals("Message 51", entries.first().message)
        assertEquals("Message 250", entries.last().message)

        TrekoLogBuffer.clear()
        assertTrue(TrekoLogBuffer.entries.value.isEmpty())
    }

    @Test
    fun testEmotionFromStringFallback() {
        assertEquals(TrekoEmotion.FELIZ_ABIERTO, TrekoEmotion.fromString("feliz_abierto"))
        assertEquals(TrekoEmotion.HAPPY, TrekoEmotion.fromString("HAPPY.gif"))
        assertEquals(TrekoEmotion.RELAX, TrekoEmotion.fromString("nonexistent"))
    }

    @Test
    fun testCinemaModeOverridesFollowUpBargeInAndSnr() {
        assertTrue(TrekoPreferences.resolveEffectiveAutoFollowUp(autoFollowUp = true, cinemaMode = false))
        assertFalse(TrekoPreferences.resolveEffectiveAutoFollowUp(autoFollowUp = true, cinemaMode = true))
        assertTrue(TrekoPreferences.resolveEffectiveVoiceBargeIn(voiceBargeIn = true, cinemaMode = false))
        assertFalse(TrekoPreferences.resolveEffectiveVoiceBargeIn(voiceBargeIn = true, cinemaMode = true))
        assertEquals(7.5f, TrekoPreferences.resolveEffectiveNearFieldMinSnrDb(7.5f, cinemaMode = false), 0.001f)
        assertEquals(10.0f, TrekoPreferences.resolveEffectiveNearFieldMinSnrDb(7.5f, cinemaMode = true), 0.001f)
        assertEquals(
            com.treko.core.common.model.FullDuplexMode.COMPLEMENTARY,
            TrekoPreferences.resolveEffectiveFullDuplexMode(
                com.treko.core.common.model.FullDuplexMode.COMPLEMENTARY,
                cinemaMode = false
            )
        )
        assertEquals(
            com.treko.core.common.model.FullDuplexMode.HALF_DUPLEX,
            TrekoPreferences.resolveEffectiveFullDuplexMode(
                com.treko.core.common.model.FullDuplexMode.COMPLEMENTARY,
                cinemaMode = true
            )
        )
        assertEquals(
            com.treko.core.common.model.FullDuplexMode.BARGE_IN_INTERRUPT,
            com.treko.core.common.model.FullDuplexMode.fromStored("barge_in_interrupt")
        )
        assertEquals(
            com.treko.core.common.model.FullDuplexMode.COMPLEMENTARY,
            com.treko.core.common.model.FullDuplexMode.fromStored("unknown_value")
        )
    }

    @Test
    fun testAudioLevelIncludesSnrInEquality() {
        val a = AudioLevel(rms = 0.1f, isSpeaking = true, snrDb = 12.0f)
        val b = AudioLevel(rms = 0.1f, isSpeaking = true, snrDb = 12.0f)
        val c = AudioLevel(rms = 0.1f, isSpeaking = true, snrDb = 4.0f)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun cameraSentinelInheritsTheOldCoupledSettingOnce() {
        // Before it had its own switch, the sentinel needed both the proactive check and the scan.
        assertTrue(TrekoPreferences.initialCameraSentinelEnabled(proactiveEnabled = true, cameraScanEnabled = true))
        assertFalse(TrekoPreferences.initialCameraSentinelEnabled(proactiveEnabled = false, cameraScanEnabled = true))
        assertFalse(TrekoPreferences.initialCameraSentinelEnabled(proactiveEnabled = true, cameraScanEnabled = false))
    }
}
