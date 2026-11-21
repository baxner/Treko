package com.treko.assistant.ui.screens

import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertSource
import com.treko.core.common.model.TrekoSkills
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which day a group of alerts belongs to, relative to today. */
enum class AlertDayKind { TODAY, YESTERDAY, EARLIER }

/** The alerts of one calendar day, newest first. */
data class AlertDayGroup(
    val day: LocalDate,
    val kind: AlertDayKind,
    val events: List<AlertEvent>
)

/** What the alerts list shows. Saved across recreation as [key]. */
sealed interface AlertFilter {
    val key: String

    data object All : AlertFilter {
        override val key = "all"
    }

    data object Unread : AlertFilter {
        override val key = "unread"
    }

    data class BySource(val source: AlertSource) : AlertFilter {
        override val key = "source:${source.name}"
    }

    companion object {
        fun fromKey(key: String?): AlertFilter = when {
            key == Unread.key -> Unread
            key != null && key.startsWith("source:") ->
                AlertSource.entries.firstOrNull { it.name == key.removePrefix("source:") }?.let { BySource(it) } ?: All
            else -> All
        }
    }
}

/** Pure logic of the alerts screen (grouping, filters), kept apart for unit tests. */
object AlertsListModel {

    /**
     * Sources that only exist with a Home Assistant integration: their chip shows when the skill
     * is detected, even before the first alert. The others (timers, briefings) show once the
     * log has one of theirs.
     */
    private val SKILLS_BY_SOURCE: Map<AlertSource, Set<String>> = mapOf(
        AlertSource.WATCH to setOf(TrekoSkills.FRIGATE, TrekoSkills.MOTION_SENSORS),
        AlertSource.SENTINEL to setOf(TrekoSkills.FRIGATE),
        AlertSource.PRINTER to setOf(TrekoSkills.OCTOPRINT)
    )

    /** Filter chips by source: present in the log, or possible with the detected skills. */
    fun visibleSources(events: List<AlertEvent>, detectedSkills: Set<String>): List<AlertSource> {
        val present = events.mapTo(HashSet()) { it.source }
        return AlertSource.entries.filter { source ->
            source in present || SKILLS_BY_SOURCE[source]?.any { it in detectedSkills } == true
        }
    }

    /** A source filter whose chip is gone (e.g. its entries were deleted) falls back to all. */
    fun effectiveFilter(filter: AlertFilter, visibleSources: List<AlertSource>): AlertFilter =
        if (filter is AlertFilter.BySource && filter.source !in visibleSources) AlertFilter.All else filter

    fun filter(events: List<AlertEvent>, filter: AlertFilter): List<AlertEvent> = when (filter) {
        AlertFilter.All -> events
        AlertFilter.Unread -> events.filter { it.isUnread }
        is AlertFilter.BySource -> events.filter { it.source == filter.source }
    }

    /** Groups [events] by local calendar day, newest day first and newest entry first. */
    fun groupByDay(events: List<AlertEvent>, nowMs: Long, zone: ZoneId): List<AlertDayGroup> {
        if (events.isEmpty()) return emptyList()
        val today = dayOf(nowMs, zone)
        val yesterday = today.minusDays(1)
        return events
            .sortedWith(compareByDescending<AlertEvent> { it.timestampMs }.thenByDescending { it.id })
            .groupBy { dayOf(it.timestampMs, zone) }
            .map { (day, list) ->
                val kind = when (day) {
                    today -> AlertDayKind.TODAY
                    yesterday -> AlertDayKind.YESTERDAY
                    else -> AlertDayKind.EARLIER
                }
                AlertDayGroup(day, kind, list)
            }
    }

    /** Alerts recorded today (local day). */
    fun countToday(events: List<AlertEvent>, nowMs: Long, zone: ZoneId): Int {
        val today = dayOf(nowMs, zone)
        return events.count { dayOf(it.timestampMs, zone) == today }
    }

    /** Notifications to clear when every alert is marked read. */
    fun notificationsOfUnread(events: List<AlertEvent>): List<Int> =
        events.filter { !it.read }.mapNotNull { it.notificationId }.distinct()

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
