package com.treko.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentinelCooldownTest {

    @Test
    fun restDependsOnTheOutcome() {
        assertEquals(SentinelCooldown.AFTER_NO_NEWS_MS, SentinelCooldown.afterOutcome("normal", ran = true))
        assertEquals(SentinelCooldown.AFTER_ALERT_MS, SentinelCooldown.afterOutcome("alert", ran = true))
        assertEquals(SentinelCooldown.AFTER_ALERT_MS, SentinelCooldown.afterOutcome("alert_spoken", ran = true))
        assertEquals(SentinelCooldown.AFTER_ALERT_MS, SentinelCooldown.afterOutcome("alert_notified", ran = true))
        // A repeated alert that was not announced again still means someone is there.
        assertEquals(SentinelCooldown.AFTER_ALERT_MS, SentinelCooldown.afterOutcome("suppressed", ran = true))
        assertEquals(SentinelCooldown.AFTER_ALERT_MS, SentinelCooldown.afterOutcome(SentinelCooldown.DECISION_WATCH_ALERTED, ran = true))
        // Errors or no verdict: a short rest, never ten minutes of blindness.
        assertEquals(SentinelCooldown.AFTER_NO_VERDICT_MS, SentinelCooldown.afterOutcome("error", ran = true))
        // Vision gave no answer: a short retry rest, shorter than "nothing new".
        assertEquals(SentinelCooldown.AFTER_NO_VERDICT_MS, SentinelCooldown.afterOutcome("no_verdict", ran = true))
        assert(SentinelCooldown.AFTER_NO_VERDICT_MS < SentinelCooldown.AFTER_NO_NEWS_MS)
        assertEquals(SentinelCooldown.AFTER_NO_NEWS_MS, SentinelCooldown.afterOutcome(null, ran = true))
        // It did not run: no rest at all.
        assertNull(SentinelCooldown.afterOutcome("skipped", ran = false))
    }

    @Test
    fun noNewsRestIsAboutAMinute() {
        val seconds = SentinelCooldown.AFTER_NO_NEWS_MS / 1000
        assert(seconds in 60..90)
        assertEquals(10 * 60_000L, SentinelCooldown.AFTER_ALERT_MS)
    }
}
