package com.treko.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInsightQueueTest {

    private fun insight(id: String, category: ProactiveCategory, at: Long) = ProactiveInsight(
        id = id,
        category = category,
        title = id,
        summaryText = id,
        spokenPrompt = id,
        fingerprint = id,
        createdAtMs = at
    )

    @Test
    fun cameraAlertIsNeverOverwrittenByALaterBriefing() {
        val q = PendingInsightQueue()
        q.offer(insight("cam", ProactiveCategory.CAMERA_ANOMALY, 1))
        q.offer(insight("water", ProactiveCategory.HABITS_AND_WATER, 2))
        assertEquals("cam", q.peek()?.id)
        assertEquals(2, q.size)
        assertEquals("cam", q.poll()?.id)
        assertEquals("water", q.poll()?.id)
        assertNull(q.poll())
    }

    @Test
    fun newerBriefingReplacesSameTopicButCameraAlertsAccumulateNewestFirst() {
        val q = PendingInsightQueue()
        q.offer(insight("home1", ProactiveCategory.HOME_STATUS, 1))
        q.offer(insight("home2", ProactiveCategory.HOME_STATUS, 2))
        q.offer(insight("cam1", ProactiveCategory.CAMERA_ANOMALY, 3))
        q.offer(insight("cam2", ProactiveCategory.CAMERA_ANOMALY, 4))
        assertEquals(listOf("cam2", "cam1", "home2"), q.snapshot().map { it.id })
    }

    @Test
    fun overCapacityTheLeastImportantGoesNotTheCameraAlert() {
        val q = PendingInsightQueue(maxSize = 2)
        q.offer(insight("cam", ProactiveCategory.CAMERA_ANOMALY, 1))
        q.offer(insight("tasks", ProactiveCategory.TASKS_AND_REMINDERS, 2))
        q.offer(insight("memory", ProactiveCategory.MEMORY_FOLLOWUP, 3))
        assertEquals(listOf("cam", "tasks"), q.snapshot().map { it.id })
        q.offer(insight("home", ProactiveCategory.HOME_STATUS, 4))
        assertEquals(listOf("cam", "home"), q.snapshot().map { it.id })
    }

    @Test
    fun removeById() {
        val q = PendingInsightQueue()
        q.offer(insight("a", ProactiveCategory.HOME_STATUS, 1))
        q.offer(insight("b", ProactiveCategory.CAMERA_ANOMALY, 2))
        assertEquals("a", q.remove("a")?.id)
        assertNull(q.remove("a"))
        assertEquals("b", q.find("b")?.id)
        assertEquals(1, q.size)
    }

    @Test
    fun pruneDropsStaleCameraAlertsSoonerThanBriefings() {
        val q = PendingInsightQueue()
        val start = 1_000_000L
        q.offer(insight("cam", ProactiveCategory.CAMERA_ANOMALY, start))
        q.offer(insight("tasks", ProactiveCategory.TASKS_AND_REMINDERS, start))
        assertEquals(emptyList<ProactiveInsight>(), q.prune(start + 10 * 60_000L))
        assertEquals(2, q.size)
        val gone = q.prune(start + PendingInsightQueue.CAMERA_MAX_AGE_MS + 1)
        assertEquals(listOf("cam"), gone.map { it.id })
        assertEquals("tasks", q.peek()?.id)
        assertEquals(listOf("tasks"), q.prune(start + PendingInsightQueue.OTHER_MAX_AGE_MS + 1).map { it.id })
        assertNull(q.peek())
    }

    @Test
    fun removeByAlertIdDropsOnlyThatAlert() {
        val q = PendingInsightQueue()
        q.offer(insight("cam1", ProactiveCategory.CAMERA_ANOMALY, 1).copy(alertId = 10L))
        q.offer(insight("cam2", ProactiveCategory.CAMERA_ANOMALY, 2).copy(alertId = 11L))
        assertEquals(listOf("cam1"), q.removeByAlertId(10L).map { it.id })
        assertEquals(emptyList<ProactiveInsight>(), q.removeByAlertId(99L))
        assertEquals(listOf("cam2"), q.snapshot().map { it.id })
    }

    @Test
    fun offerReturnsWhatLeftTheQueue() {
        val q = PendingInsightQueue(maxSize = 2)
        assertEquals(emptyList<ProactiveInsight>(), q.offer(insight("tasks1", ProactiveCategory.TASKS_AND_REMINDERS, 1)))
        // A newer briefing of the same topic replaces the older one.
        assertEquals(listOf("tasks1"), q.offer(insight("tasks2", ProactiveCategory.TASKS_AND_REMINDERS, 2)).map { it.id })
        q.offer(insight("cam1", ProactiveCategory.CAMERA_ANOMALY, 3))
        // Over the cap the least important goes, even a briefing a notification shows.
        assertEquals(listOf("tasks2"), q.offer(insight("cam2", ProactiveCategory.CAMERA_ANOMALY, 4)).map { it.id })
    }

    @Test
    fun aLateCameraAlertSaysHowOldItIs() {
        val cam = insight("cam", ProactiveCategory.CAMERA_ANOMALY, 0)
        assertNull(PendingInsightQueue.heldAgeMinutes(cam, 60_000L))
        assertEquals(12, PendingInsightQueue.heldAgeMinutes(cam, 12 * 60_000L + 5_000L))
        assertNull(PendingInsightQueue.heldAgeMinutes(insight("w", ProactiveCategory.HABITS_AND_WATER, 0), 60 * 60_000L))
    }

    @Test
    fun removeOfAGoneIdTakesNothing() {
        val q = PendingInsightQueue()
        q.offer(insight("cam", ProactiveCategory.CAMERA_ANOMALY, 1))
        assertNull(q.remove("briefing-already-gone"))
        assertEquals("cam", q.peek()?.id)
    }
}
