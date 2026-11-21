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
}
