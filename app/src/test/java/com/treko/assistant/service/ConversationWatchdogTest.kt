package com.treko.assistant.service

import com.treko.assistant.service.ConversationWatchdog.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWatchdogTest {

    private val wd = ConversationWatchdog()

    @Test
    fun disarmedByDefault() {
        assertEquals(Phase.DISARMED, wd.phase)
        assertNull(wd.remainingMs(0))
        assertFalse(wd.checkExpired(1_000_000, "s"))
    }

    @Test
    fun responseTimeoutExpiresAfter12s() {
        wd.onTurnSent(0)
        assertEquals(Phase.AWAITING_RESPONSE, wd.phase)
        assertEquals(12_000L, wd.remainingMs(0))
        assertFalse(wd.checkExpired(11_999, "s"))
        assertTrue(wd.checkExpired(12_000, "s"))
        assertEquals(Phase.DISARMED, wd.phase)
    }

    @Test
    fun slowToolGetsSixtySecondsFromItsStart() {
        wd.onTurnSent(0)
        val id = wd.onToolStarted("analyze_camera", 5_000)
        assertEquals(Phase.TOOL_RUNNING, wd.phase)
        assertFalse(wd.checkExpired(40_000, "s"))
        assertTrue(wd.checkExpired(65_000, "s"))
        wd.onToolFinished(id, 65_001) // no rearma un turno ya cerrado
        assertEquals(Phase.DISARMED, wd.phase)
    }

    @Test
    fun regularToolGetsTwentySeconds() {
        wd.onTurnSent(0)
        wd.onToolStarted("control_home_assistant", 1_000)
        assertEquals(20_000L, wd.remainingMs(1_000))
        assertTrue(wd.checkExpired(21_000, "s"))
    }

    @Test
    fun slowestRunningToolWins() {
        wd.onTurnSent(0)
        val fast = wd.onToolStarted("control_home_assistant", 0)
        val slow = wd.onToolStarted("view_camera", 0)
        wd.onToolFinished(fast, 1_000)
        assertEquals(60_000L, wd.remainingMs(1_000))
        wd.onToolFinished(slow, 30_000)
        assertEquals(Phase.AWAITING_RESPONSE, wd.phase)
        assertEquals(12_000L, wd.remainingMs(30_000))
    }

    @Test
    fun llmWithoutAudioMovesToAudioDeadline() {
        wd.onTurnSent(0)
        wd.onLlmReceived(3_000)
        assertEquals(Phase.AWAITING_AUDIO, wd.phase)
        assertFalse(wd.checkExpired(10_000, "s"))
        assertTrue(wd.checkExpired(10_500, "s"))
    }

    @Test
    fun toolAfterLlmUsesToolDeadlineThenAudioDeadline() {
        wd.onTurnSent(0)
        wd.onLlmReceived(1_000)
        val id = wd.onToolStarted("analyze_camera", 2_000)
        assertEquals(Phase.TOOL_RUNNING, wd.phase)
        wd.onToolFinished(id, 42_000)
        assertEquals(Phase.AWAITING_AUDIO, wd.phase)
        assertEquals(7_500L, wd.remainingMs(42_000))
    }

    @Test
    fun disarmStopsTimeout() {
        wd.onTurnSent(0)
        wd.disarm()
        assertFalse(wd.checkExpired(100_000, "s"))
    }

    @Test
    fun toolsOutsideATurnDoNotArm() {
        val id = wd.onToolStarted("analyze_camera", 0)
        assertEquals(Phase.DISARMED, wd.phase)
        wd.onToolFinished(id, 10)
        assertNull(wd.remainingMs(10))
    }

    @Test
    fun lateTtsAcceptedOnlyInGraceWindowOfSameSession() {
        wd.onTurnSent(0)
        assertTrue(wd.checkExpired(12_000, "s1"))
        assertTrue(wd.acceptsLateTts(20_000, "s1"))
        assertTrue(wd.acceptsLateTts(32_000, "s1"))
        assertFalse(wd.acceptsLateTts(32_001, "s1"))
        assertFalse(wd.acceptsLateTts(20_000, "s2"))
        assertFalse(wd.acceptsLateTts(20_000, null))
        wd.consumeLateTtsGrace()
        assertFalse(wd.acceptsLateTts(20_000, "s1"))
    }

    @Test
    fun newTurnClearsGrace() {
        wd.onTurnSent(0)
        wd.checkExpired(12_000, "s1")
        wd.onTurnSent(13_000)
        assertFalse(wd.acceptsLateTts(14_000, "s1"))
    }
}
