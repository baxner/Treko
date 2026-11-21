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

    /**
     * Queues [insight] and returns the insights that left the queue because of it (an older
     * briefing of the same topic, or the least important one over [maxSize]).
     */
    fun offer(insight: ProactiveInsight): List<ProactiveInsight> {
        val gone = ArrayList<ProactiveInsight>()
        items.removeAll { it.id == insight.id }
        if (insight.category != ProactiveCategory.CAMERA_ANOMALY) {
            items.filter { it.category == insight.category }.let { same ->
                gone += same
                items.removeAll(same.toSet())
            }
        }
        items += insight
        items.sortWith(ORDER)
        while (items.size > maxSize) gone += items.removeAt(items.lastIndex)
        return gone
    }

    /**
     * Drops what is too old to be read as news (see [maxAgeMs]) and returns it. A camera alert
     * held for hours would otherwise be read back as if it were happening now.
     */
    fun prune(nowMs: Long): List<ProactiveInsight> {
        val old = items.filter { nowMs - it.createdAtMs > maxAgeMs(it.category) }
        if (old.isNotEmpty()) items.removeAll(old.toSet())
        return old
    }

    /** Removes the insights recorded as alerts-log entry [alertId] (the user already saw it). */
    fun removeByAlertId(alertId: Long): List<ProactiveInsight> {
        val seen = items.filter { it.alertId == alertId }
        if (seen.isNotEmpty()) items.removeAll(seen.toSet())
        return seen
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

        /** A held camera alert is stale after this long (the Alerts screen still has it). */
        const val CAMERA_MAX_AGE_MS = 30 * 60_000L
        /** Briefings, reminders and the rest. */
        const val OTHER_MAX_AGE_MS = 3 * 60 * 60_000L
        /** A camera alert read this long after it came up says how old it is. */
        const val AGE_NOTE_AFTER_MS = 5 * 60_000L

        fun maxAgeMs(category: ProactiveCategory): Long =
            if (category == ProactiveCategory.CAMERA_ANOMALY) CAMERA_MAX_AGE_MS else OTHER_MAX_AGE_MS

        /**
         * Whole minutes since [insight] came up when it is a camera alert read late enough to
         * say so ("Hace 12 minutos: …"); null otherwise.
         */
        fun heldAgeMinutes(insight: ProactiveInsight, nowMs: Long): Int? {
            if (insight.category != ProactiveCategory.CAMERA_ANOMALY) return null
            val age = nowMs - insight.createdAtMs
            if (age < AGE_NOTE_AFTER_MS) return null
            return (age / 60_000L).toInt()
        }

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
