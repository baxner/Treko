package com.treko.assistant.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.treko.assistant.R
import com.treko.assistant.service.AlertNotifications
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.model.AlertSource
import com.treko.core.common.model.TrekoSkills
import com.treko.core.mcp.watch.CameraWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal val WatchGreen = Color(0xFF22C55E)
internal val SentinelAmber = Color(0xFFF59E0B)

internal fun alertSourceColor(source: AlertSource): Color = when (source) {
    AlertSource.WATCH -> WatchGreen
    AlertSource.SENTINEL -> SentinelAmber
    AlertSource.PROACTIVE -> NeonPurple
    AlertSource.PRINTER -> NeonCyan
    AlertSource.TIMER -> TextSecondary
}

internal fun alertSourceIcon(source: AlertSource): ImageVector = when (source) {
    AlertSource.WATCH -> Icons.Default.Visibility
    AlertSource.SENTINEL -> Icons.Default.Shield
    AlertSource.PROACTIVE -> Icons.Default.AutoAwesome
    AlertSource.PRINTER -> Icons.Default.Print
    AlertSource.TIMER -> Icons.Default.Timer
}

internal fun alertSourceLabel(source: AlertSource): Int = when (source) {
    AlertSource.WATCH -> R.string.alerts_source_watch
    AlertSource.SENTINEL -> R.string.alerts_source_sentinel
    AlertSource.PROACTIVE -> R.string.alerts_source_proactive
    AlertSource.PRINTER -> R.string.alerts_source_printer
    AlertSource.TIMER -> R.string.alerts_source_timer
}

/** Time of day in the phone's 12/24 h format. */
internal fun formatAlertTime(context: Context, ms: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))

internal fun appLocale(context: Context): Locale =
    context.resources.configuration.locales.get(0) ?: Locale.getDefault()

/** The picture to show: the thumbnail in lists, the full picture in the detail. */
internal fun alertImageFile(event: AlertEvent, preferThumb: Boolean): File? {
    val path = if (preferThumb) event.thumbPath ?: event.snapshotPath else event.snapshotPath ?: event.thumbPath
    return path?.let { File(it) }
}

/**
 * Clears the notifications of [alerts] (alerts-log id to notification id; the alert was read
 * in the app). The alert's tag makes sure only that alert's own notification goes.
 */
internal fun cancelAlertNotifications(context: Context, alerts: Collection<Pair<Long, Int>>) {
    if (alerts.isEmpty()) return
    val manager = NotificationManagerCompat.from(context)
    alerts.forEach { (alertId, notificationId) -> manager.cancel(AlertNotifications.tagFor(alertId), notificationId) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlertsScreen(onOpenAlert: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by TrekoAlertLog.events.collectAsState()
    val unread by TrekoAlertLog.unreadCount.collectAsState()
    val watches by TrekoAssistantService.activeWatches.collectAsState()
    val assistantName by TrekoAssistantService.assistantName.collectAsState()
    val detectedSkills = remember(context) { TrekoPreferences(context).detectedSkills }
    var filterKey by rememberSaveable { mutableStateOf(AlertFilter.All.key) }
    // Re-evaluated every minute so "Today"/"Yesterday" stay right past midnight.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    val zone = remember { ZoneId.systemDefault() }

    val sources = remember(events, detectedSkills) { AlertsListModel.visibleSources(events, detectedSkills) }
    val filter = AlertsListModel.effectiveFilter(AlertFilter.fromKey(filterKey), sources)
    val groups = remember(events, filter, nowMs) {
        AlertsListModel.listFor(events, filter, nowMs, zone)
    }
    val todayCount = remember(events, nowMs) { AlertsListModel.countToday(events, nowMs, zone) }
    val canWatch = TrekoSkills.FRIGATE in detectedSkills || TrekoSkills.MOTION_SENSORS in detectedSkills

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.alerts_title), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.alerts_subtitle, unread, todayCount), color = TextSecondary, fontSize = 13.sp)
            }
            if (unread > 0) {
                TextButton(onClick = {
                    val toClear = AlertsListModel.notificationsOfUnread(events)
                    cancelAlertNotifications(context, toClear)
                    scope.launch(Dispatchers.IO) { TrekoAlertLog.markAllRead() }
                }) {
                    Icon(Icons.Default.DoneAll, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.alerts_mark_all_read),
                        color = NeonCyan,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (watches.isNotEmpty()) {
                item(key = "watching") { ActiveWatchesCard(watches) }
            }
            if (events.isNotEmpty() && sources.isNotEmpty()) {
                item(key = "filters") {
                    AlertFilterChips(
                        sources = sources,
                        selected = filter,
                        unread = unread,
                        onSelect = { filterKey = it.key }
                    )
                }
            }
            if (events.isEmpty()) {
                item(key = "empty") {
                    AlertsEmptyState(
                        hint = if (canWatch) {
                            stringResource(R.string.alerts_empty_hint_watch, assistantName.ifBlank { "Treko" })
                        } else {
                            stringResource(R.string.alerts_empty_hint_generic, assistantName.ifBlank { "Treko" })
                        }
                    )
                }
            } else if (groups.isEmpty()) {
                item(key = "empty_filter") {
                    Text(
                        stringResource(R.string.alerts_empty_filter),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            }
            groups.forEach { group ->
                stickyHeader(key = "day_${group.day}") { DayHeader(group) }
                items(group.events, key = { it.id }) { event ->
                    if (event.level == AlertLevel.NO_NEWS) {
                        NoNewsRow(event, onClick = { onOpenAlert(event.id) })
                    } else {
                        AlertCard(event, onClick = { onOpenAlert(event.id) })
                    }
                }
                if (group.collapsedNoNews > 0) {
                    item(key = "no_news_${group.day}") {
                        NoNewsSummaryRow(
                            count = group.collapsedNoNews,
                            onClick = { filterKey = AlertFilter.BySource(AlertSource.SENTINEL).key }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveWatchesCard(watches: List<CameraWatch>) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = WatchGreen.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, WatchGreen.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Visibility, contentDescription = null, tint = WatchGreen, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.alerts_watching_now), color = WatchGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            watches.forEach { watch ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            watch.areaNames.joinToString(", "),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val until = stringResource(R.string.alerts_watch_until, formatAlertTime(context, watch.expiresAtMs))
                        val details = stringResource(R.string.alerts_watch_line, stringResource(watchObjectLabel(watch.objectGroup)), until) +
                            if (watch.once) " · " + stringResource(R.string.alerts_watch_once) else ""
                        Text(details, color = TextSecondary, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { TrekoAssistantService.stopWatch(watch.id) },
                        border = BorderStroke(1.dp, WatchGreen.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.alerts_watch_stop), color = WatchGreen, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun watchObjectLabel(group: String): Int = when (group) {
    "car" -> R.string.alerts_object_car
    "animal" -> R.string.alerts_object_animal
    "package" -> R.string.alerts_object_package
    "any" -> R.string.alerts_object_any
    else -> R.string.alerts_object_person
}

@Composable
private fun AlertFilterChips(
    sources: List<AlertSource>,
    selected: AlertFilter,
    unread: Int,
    onSelect: (AlertFilter) -> Unit
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = NeonCyan.copy(alpha = 0.18f),
        selectedLabelColor = NeonCyan,
        selectedLeadingIconColor = NeonCyan
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == AlertFilter.All,
                onClick = { onSelect(AlertFilter.All) },
                label = { Text(stringResource(R.string.alerts_filter_all), fontSize = 12.sp) },
                colors = chipColors
            )
        }
        item {
            FilterChip(
                selected = selected == AlertFilter.Unread,
                onClick = { onSelect(AlertFilter.Unread) },
                label = { Text(stringResource(R.string.alerts_filter_unread, unread), fontSize = 12.sp) },
                colors = chipColors
            )
        }
        items(sources) { source ->
            FilterChip(
                selected = selected == AlertFilter.BySource(source),
                onClick = { onSelect(AlertFilter.BySource(source)) },
                label = { Text(stringResource(alertSourceLabel(source)), fontSize = 12.sp) },
                leadingIcon = {
                    Icon(alertSourceIcon(source), contentDescription = null, tint = alertSourceColor(source), modifier = Modifier.size(14.dp))
                },
                colors = chipColors
            )
        }
    }
}

@Composable
private fun DayHeader(group: AlertDayGroup) {
    val context = LocalContext.current
    val label = when (group.kind) {
        AlertDayKind.TODAY -> stringResource(R.string.alerts_day_today)
        AlertDayKind.YESTERDAY -> stringResource(R.string.alerts_day_yesterday)
        AlertDayKind.EARLIER -> formatDay(context, group.day)
    }
    Text(
        text = label,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepBackground)
            .padding(vertical = 4.dp)
    )
}

private fun formatDay(context: Context, day: LocalDate): String {
    val locale = appLocale(context)
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    return day.format(DateTimeFormatter.ofPattern(pattern, locale))
}

@Composable
private fun AlertCard(event: AlertEvent, onClick: () -> Unit) {
    val context = LocalContext.current
    val tint = alertSourceColor(event.source)
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (event.isUnread) tint.copy(alpha = 0.5f) else CardBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AlertThumbnail(event, tint)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    event.title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val time = formatAlertTime(context, event.timestampMs)
                Text(
                    listOfNotNull(event.place?.takeIf { it.isNotBlank() }, time).joinToString(" · "),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    add(stringResource(alertSourceLabel(event.source)))
                    add(stringResource(R.string.alerts_level_alert))
                    if (event.spoken) add(stringResource(R.string.alerts_spoken))
                }.joinToString(" · ")
                Text(meta, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            if (event.isUnread) {
                val unreadCd = stringResource(R.string.alerts_unread_cd)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(NeonCyan)
                        .semantics { contentDescription = unreadCd }
                )
            }
        }
    }
}

@Composable
private fun AlertThumbnail(event: AlertEvent, tint: Color) {
    val context = LocalContext.current
    val file = alertImageFile(event, preferThumb = true)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 66.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(alertSourceIcon(event.source), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        if (file != null) {
            // Coil decodes the file on its own background threads.
            AsyncImage(
                model = remember(file) { ImageRequest.Builder(context).data(file).crossfade(true).build() },
                contentDescription = event.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NoNewsRow(event: AlertEvent, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        Text(
            event.title,
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(formatAlertTime(context, event.timestampMs), color = TextSecondary, fontSize = 12.sp)
    }
}

/** One row for a day's "no news" sentinel checks; tapping it lists them under the Sentinel chip. */
@Composable
private fun NoNewsSummaryRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        Text(
            pluralStringResource(R.plurals.alerts_no_news_checks, count, count),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AlertsEmptyState(hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.alerts_empty_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Box(modifier = Modifier.height(4.dp))
    }
}

/** Marks [id] read (loading the log first if needed) and clears its notification. */
internal suspend fun openAlertAndMarkRead(context: Context, id: Long): AlertEvent? = withContext(Dispatchers.IO) {
    val event = TrekoAlertLog.get(id) ?: return@withContext null
    // Seen: it is no longer read back later as pending news.
    TrekoAssistantService.onAlertSeen(context, id)
    if (!event.read) {
        TrekoAlertLog.markRead(id)
        // An entry already read (a timer, an alert seen before) has no notification left to clear.
        event.notificationId?.let { cancelAlertNotifications(context, listOf(id to it)) }
    }
    TrekoAlertLog.get(id)
}
