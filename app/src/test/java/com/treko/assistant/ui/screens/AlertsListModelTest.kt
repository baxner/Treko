package com.treko.assistant.ui.screens

import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.model.AlertSource
import com.treko.core.common.model.TrekoSkills
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlertsListModelTest {

    private val zone: ZoneId = ZoneId.of("America/Mexico_City")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        ts: Long,
        source: AlertSource = AlertSource.WATCH,
        level: AlertLevel = AlertLevel.ALERT,
        read: Boolean = false,
        notificationId: Int? = null
    ) = AlertEvent(
        id = id,
        timestampMs = ts,
        source = source,
        level = level,
        title = "e$id",
        read = read,
        notificationId = notificationId
    )

    @Test
    fun groupsByLocalDayWithTodayAndYesterday() {
        val now = at(2026, 11, 20, 0, 30)
        val events = listOf(
            event(1, at(2026, 11, 18, 22)),
            event(2, at(2026, 11, 19, 23, 59)),
            event(3, at(2026, 11, 20, 0, 5)),
            event(4, at(2026, 11, 19, 8))
        )
        val groups = AlertsListModel.groupByDay(events, now, zone)
        assertEquals(listOf(AlertDayKind.TODAY, AlertDayKind.YESTERDAY, AlertDayKind.EARLIER), groups.map { it.kind })
        assertEquals(listOf(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18)), groups.map { it.day })
        // Newest first inside a day.
        assertEquals(listOf(2L, 4L), groups[1].events.map { it.id })
        assertEquals(1, AlertsListModel.countToday(events, now, zone))
    }

    @Test
    fun emptyLogHasNoGroups() {
        assertEquals(emptyList<AlertDayGroup>(), AlertsListModel.groupByDay(emptyList(), 0L, zone))
    }

    @Test
    fun sourceChipsComeFromTheLogOrTheDetectedSkills() {
        val log = listOf(event(1, 0, AlertSource.TIMER))
        assertEquals(listOf(AlertSource.TIMER), AlertsListModel.visibleSources(log, emptySet()))
        assertEquals(
            listOf(AlertSource.WATCH, AlertSource.TIMER),
            AlertsListModel.visibleSources(log, setOf(TrekoSkills.MOTION_SENSORS))
        )
        assertEquals(
            listOf(AlertSource.WATCH, AlertSource.SENTINEL, AlertSource.PRINTER),
            AlertsListModel.visibleSources(emptyList(), setOf(TrekoSkills.FRIGATE, TrekoSkills.OCTOPRINT))
        )
    }

    @Test
    fun filtersBySourceAndUnread() {
        val events = listOf(
            event(1, 3, AlertSource.WATCH),
            event(2, 2, AlertSource.SENTINEL, level = AlertLevel.NO_NEWS),
            event(3, 1, AlertSource.SENTINEL, read = true)
        )
        assertEquals(3, AlertsListModel.filter(events, AlertFilter.All).size)
        // "No news" checks are never unread.
        assertEquals(listOf(1L), AlertsListModel.filter(events, AlertFilter.Unread).map { it.id })
        assertEquals(listOf(2L, 3L), AlertsListModel.filter(events, AlertFilter.BySource(AlertSource.SENTINEL)).map { it.id })
    }

    @Test
    fun goneSourceFilterFallsBackToAll() {
        val filter = AlertFilter.BySource(AlertSource.PRINTER)
        assertEquals(AlertFilter.All, AlertsListModel.effectiveFilter(filter, listOf(AlertSource.WATCH)))
        assertEquals(filter, AlertsListModel.effectiveFilter(filter, listOf(AlertSource.PRINTER)))
        assertEquals(AlertFilter.Unread, AlertsListModel.effectiveFilter(AlertFilter.Unread, emptyList()))
    }

    @Test
    fun filterKeysRoundTrip() {
        val all = listOf(AlertFilter.All, AlertFilter.Unread) + AlertSource.entries.map { AlertFilter.BySource(it) }
        all.forEach { assertEquals(it, AlertFilter.fromKey(it.key)) }
        assertEquals(AlertFilter.All, AlertFilter.fromKey("source:NOPE"))
        assertEquals(AlertFilter.All, AlertFilter.fromKey(null))
    }

    @Test
    fun notificationsToClearAreThoseOfUnreadEntries() {
        val events = listOf(
            event(1, 0, notificationId = 3001),
            event(2, 0, read = true, notificationId = 3002),
            event(3, 0),
            event(4, 0, notificationId = 3001)
        )
        // Each alert's own notification (the tag tells two alerts with the same id apart).
        assertEquals(listOf(1L to 3001, 4L to 3001), AlertsListModel.notificationsOfUnread(events))
    }

    @Test
    fun noNewsChecksAreNotCountedAsAlertsToday() {
        val now = at(2026, 11, 20, 12)
        val events = listOf(
            event(1, at(2026, 11, 20, 9)),
            event(2, at(2026, 11, 20, 10), source = AlertSource.SENTINEL, level = AlertLevel.NO_NEWS, read = true),
            event(3, at(2026, 11, 20, 11), source = AlertSource.SENTINEL, level = AlertLevel.NO_NEWS, read = true)
        )
        assertEquals(1, AlertsListModel.countToday(events, now, zone))
    }

    @Test
    fun noNewsChecksCollapseUnderAllButNotUnderTheSentinelChip() {
        val now = at(2026, 11, 20, 12)
        val events = listOf(
            event(1, at(2026, 11, 20, 9), source = AlertSource.SENTINEL),
            event(2, at(2026, 11, 20, 10), source = AlertSource.SENTINEL, level = AlertLevel.NO_NEWS, read = true),
            event(3, at(2026, 11, 20, 11), source = AlertSource.SENTINEL, level = AlertLevel.NO_NEWS, read = true),
            event(4, at(2026, 11, 19, 11), source = AlertSource.SENTINEL, level = AlertLevel.NO_NEWS, read = true)
        )
        val all = AlertsListModel.listFor(events, AlertFilter.All, now, zone)
        assertEquals(listOf(listOf(1L), emptyList()), all.map { g -> g.events.map { it.id } })
        assertEquals(listOf(2, 1), all.map { it.collapsedNoNews })

        val sentinel = AlertsListModel.listFor(events, AlertFilter.BySource(AlertSource.SENTINEL), now, zone)
        assertEquals(listOf(listOf(3L, 2L, 1L), listOf(4L)), sentinel.map { g -> g.events.map { it.id } })
        assertEquals(listOf(0, 0), sentinel.map { it.collapsedNoNews })

        // Checks are read: Unread never shows them.
        val unread = AlertsListModel.listFor(events, AlertFilter.Unread, now, zone)
        assertEquals(listOf(listOf(1L)), unread.map { g -> g.events.map { it.id } })
    }

    @Test
    fun timersAndBriefingsGetNoPictureBoxNorFeedback() {
        val timer = event(1, 0, source = AlertSource.TIMER, read = true).copy(spokenSummary = "Your timer finished")
        val briefing = event(2, 0, source = AlertSource.PROACTIVE).copy(spokenSummary = "Tienes dos tareas.")
        val sentinel = event(3, 0, source = AlertSource.SENTINEL)
        assertEquals(false, AlertsListModel.showsPictureBox(timer, hasPicture = false))
        assertEquals(false, AlertsListModel.showsPictureBox(briefing, hasPicture = false))
        assertEquals(true, AlertsListModel.showsPictureBox(briefing, hasPicture = true))
        assertEquals(true, AlertsListModel.showsPictureBox(sentinel, hasPicture = false))
        assertEquals(false, AlertsListModel.showsFeedback(timer))
        assertEquals(false, AlertsListModel.showsFeedback(briefing))
        assertEquals(true, AlertsListModel.showsFeedback(sentinel))
        assertEquals(false, AlertsListModel.showsFeedback(sentinel.copy(level = AlertLevel.NO_NEWS)))
        assertEquals(false, AlertsListModel.showsSpokenSummary(timer))
        assertEquals(true, AlertsListModel.showsSpokenSummary(briefing))
    }
}
