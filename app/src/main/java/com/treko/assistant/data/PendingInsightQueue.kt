package com.treko.assistant.data

/**
 * Proactive insights waiting to be heard (they could not be spoken when they came up).
 *
 * A small priority queue instead of a single slot, so a camera alert is never overwritten by
 * a later, less important briefing. The head is the most important one, newest first within
 * the same priority. A newer briefing replaces an older one of the same topic; camera alerts
 * are distinct events and are all kept. Above [maxSize] the least important, oldest goes.
 * Not thread-safe: the owner synchronizes.
 */
class PendingInsightQueue(private val maxSize: Int = DEFAULT_MAX_SIZE) {
    private val items = ArrayList<ProactiveInsight>()

    val size: Int get() = items.size

    fun offer(insight: ProactiveInsight) {
        items.removeAll { it.id == insight.id }
        if (insight.category != ProactiveCategory.CAMERA_ANOMALY) {
            items.removeAll { it.category == insight.category }
        }
        items += insight
        items.sortWith(ORDER)
        while (items.size > maxSize) items.removeAt(items.lastIndex)
    }

    fun peek(): ProactiveInsight? = items.firstOrNull()

    fun poll(): ProactiveInsight? = if (items.isEmpty()) null else items.removeAt(0)

    fun find(id: String): ProactiveInsight? = items.firstOrNull { it.id == id }

    fun remove(id: String): ProactiveInsight? {
        val index = items.indexOfFirst { it.id == id }
        return if (index < 0) null else items.removeAt(index)
    }

    fun clear() = items.clear()

    fun snapshot(): List<ProactiveInsight> = items.toList()

    companion object {
        const val DEFAULT_MAX_SIZE = 5

        /** Lower is more important. */
        fun priorityOf(category: ProactiveCategory): Int = when (category) {
            ProactiveCategory.CAMERA_ANOMALY -> 0
            ProactiveCategory.HOME_STATUS -> 1
            ProactiveCategory.TASKS_AND_REMINDERS -> 2
            ProactiveCategory.DAILY_BRIEFING -> 3
            ProactiveCategory.HABITS_AND_WATER -> 4
            ProactiveCategory.MEMORY_FOLLOWUP -> 5
        }

        private val ORDER = compareBy<ProactiveInsight> { priorityOf(it.category) }
            .thenByDescending { it.createdAtMs }
    }
}
