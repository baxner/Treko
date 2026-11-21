package com.treko.assistant.ui.screens

import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertLevel
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
    val events: List<AlertEvent>,
    /** "No news" sentinel checks of this day left out of [events], shown as one summary row. */
    val collapsedNoNews: Int = 0
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

    /** Alerts recorded today (local day); "no news" checks are not alerts. */
    fun countToday(events: List<AlertEvent>, nowMs: Long, zone: ZoneId): Int {
        val today = dayOf(nowMs, zone)
        return events.count { it.level == AlertLevel.ALERT && dayOf(it.timestampMs, zone) == today }
    }

    /**
     * The list for [filter]: under All and Unread each day's "no news" checks collapse into a
     * count ([AlertDayGroup.collapsedNoNews]) so real alerts are not buried; a source filter
     * (e.g. Sentinel) lists every check.
     */
    fun listFor(events: List<AlertEvent>, filter: AlertFilter, nowMs: Long, zone: ZoneId): List<AlertDayGroup> {
        val groups = groupByDay(filter(events, filter), nowMs, zone)
        if (filter is AlertFilter.BySource) return groups
        return groups.map { g ->
            val (checks, rest) = g.events.partition { it.level == AlertLevel.NO_NEWS }
            g.copy(events = rest, collapsedNoNews = checks.size)
        }
    }

    /** Notifications to clear when every alert is marked read: (alert id, notification id). */
    fun notificationsOfUnread(events: List<AlertEvent>): List<Pair<Long, Int>> =
        events.filter { !it.read }.mapNotNull { e -> e.notificationId?.let { e.id to it } }.distinct()

    /** Sources whose alerts come from a camera (or a printer camera) and may carry a picture. */
    private val PICTURE_SOURCES = setOf(AlertSource.WATCH, AlertSource.SENTINEL, AlertSource.PRINTER)

    /** The detail's picture box: when there is a picture, or the alert could have had one. */
    fun showsPictureBox(event: AlertEvent, hasPicture: Boolean): Boolean = hasPicture || event.source in PICTURE_SOURCES

    /** "False alarm / Useful" only for camera alerts (a timer or a briefing is no alarm). */
    fun showsFeedback(event: AlertEvent): Boolean =
        event.level == AlertLevel.ALERT && event.source in PICTURE_SOURCES

    /** "What <assistant> said": a timer only rang, nothing was spoken. */
    fun showsSpokenSummary(event: AlertEvent): Boolean =
        event.source != AlertSource.TIMER && !event.spokenSummary.isNullOrBlank()

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
