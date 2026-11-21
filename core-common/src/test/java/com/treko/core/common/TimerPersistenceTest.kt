package com.treko.core.common

import com.treko.core.common.data.TimerPersistence
import com.treko.core.common.data.TimerScheduler
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.model.TimerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimerPersistenceTest {

    private class MemoryPersistence(var value: String? = null) : TimerPersistence {
        override fun save(encoded: String) { value = encoded }
        override fun load(): String? = value
    }

    private class RecordingScheduler : TimerScheduler {
        val scheduled = mutableMapOf<Int, Long>()
        override fun schedule(key: Int, atEpochMs: Long, label: String, totalSeconds: Int) { scheduled[key] = atEpochMs }
        override fun cancel(key: Int) { scheduled.remove(key) }
    }

    private var now = 1_000_000L

    @Before
    fun setUp() {
        TrekoTimerManager.resetForTests()
        TrekoTimerManager.clock = { now }
    }

    @After
    fun tearDown() = TrekoTimerManager.resetForTests()

    @Test
    fun namedTimerIsScheduledPersistedAndSurvivesProcessDeath() {
        val store = MemoryPersistence()
        val alarms = RecordingScheduler()
        TrekoTimerManager.attach(store, alarms)
        val t = TrekoTimerManager.addNamedTimer(minutes = 2, label = "Pasta | al dente")
        assertEquals(now + 120_000L, alarms.scheduled[t.id])

        // El proceso muere 30 s después y vuelve: se recupera con la hora real.
        TrekoTimerManager.resetForTests()
        now += 30_000L
        TrekoTimerManager.clock = { now }
        val newAlarms = RecordingScheduler()
        TrekoTimerManager.attach(store, newAlarms)
        val restored = TrekoTimerManager.namedTimers.value.single()
        assertEquals("Pasta | al dente", restored.label)
        assertEquals(90, restored.remainingSeconds)
        assertEquals(1_000_000L + 120_000L, newAlarms.scheduled[restored.id])
        // Los ids siguen sin repetirse.
        assertTrue(TrekoTimerManager.addNamedTimer(seconds = 5).id > restored.id)
    }

    @Test
    fun cancelRemovesAlarm() {
        val alarms = RecordingScheduler()
        TrekoTimerManager.attach(MemoryPersistence(), alarms)
        val t = TrekoTimerManager.addNamedTimer(minutes = 1)
        assertTrue(TrekoTimerManager.cancelNamedTimer(t.id))
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun alarmFiredClosesTimerAndCountsPomodoro() {
        val alarms = RecordingScheduler()
        TrekoTimerManager.attach(MemoryPersistence(), alarms)
        TrekoTimerManager.setMode(TimerMode.POMODORO)
        TrekoTimerManager.start()
        assertEquals(now + TimerMode.POMODORO.seconds * 1000L, alarms.scheduled[TrekoTimerManager.MAIN_TIMER_KEY])

        now += TimerMode.POMODORO.seconds * 1000L
        assertTrue(TrekoTimerManager.onAlarmFired(TrekoTimerManager.MAIN_TIMER_KEY))
        val s = TrekoTimerManager.timerState.value
        assertFalse(s.isRunning)
        assertEquals(1, s.completedSessions)
        assertFalse(TrekoTimerManager.onAlarmFired(TrekoTimerManager.MAIN_TIMER_KEY))
    }

    @Test
    fun pauseKeepsRemainingAndCancelsAlarm() {
        val alarms = RecordingScheduler()
        TrekoTimerManager.attach(MemoryPersistence(), alarms)
        TrekoTimerManager.setCustomMinutes(10)
        now += 61_000L
        TrekoTimerManager.pause()
        assertEquals(539, TrekoTimerManager.timerState.value.remainingSeconds)
        assertFalse(alarms.scheduled.containsKey(TrekoTimerManager.MAIN_TIMER_KEY))
    }

    @Test
    fun corruptOrOldStateIsIgnored() {
        TrekoTimerManager.attach(MemoryPersistence("{\"old\":true}"), RecordingScheduler())
        assertTrue(TrekoTimerManager.namedTimers.value.isEmpty())
        assertFalse(TrekoTimerManager.timerState.value.isRunning)
    }

    @Test
    fun statusLeavesOutTheUntouchedPomodoro() {
        val idle = com.treko.core.common.data.TimerState()
        assertEquals("No hay temporizadores activos.", TrekoTimerManager.describeStatus(idle, emptyList()))
        val pasta = TrekoTimerManager.addNamedTimer(minutes = 8, label = "Pasta")
        val onlyNamed = TrekoTimerManager.describeStatus(idle, listOf(pasta))
        assertTrue(onlyNamed, onlyNamed.contains("Pasta") && !onlyNamed.contains("Pomodoro"))
        val paused = idle.copy(remainingSeconds = 600)
        assertTrue(TrekoTimerManager.describeStatus(paused, emptyList()).contains("en pausa"))
    }
}
