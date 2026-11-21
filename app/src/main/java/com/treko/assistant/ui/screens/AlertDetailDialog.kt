package com.treko.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertFeedback
import com.treko.core.common.model.AlertLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val DangerRed = Color(0xFFEF4444)

/**
 * Full-screen detail of one alert: picture, what happened, and the feedback / live view /
 * delete actions. Opening it marks the alert read and clears its notification.
 */
@Composable
fun AlertDetailDialog(alertId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by TrekoAlertLog.events.collectAsState()
    // The log may still be loading after a cold start from a notification: wait for it.
    var loaded by remember(alertId) { mutableStateOf(false) }
    LaunchedEffect(alertId) {
        openAlertAndMarkRead(context, alertId)
        loaded = true
    }
    val event = events.firstOrNull { it.id == alertId }
    var confirmDelete by remember(alertId) { mutableStateOf(false) }
    var liveUnavailable by remember(alertId) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = DeepBackground, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TextPrimary)
                    }
                    Text(
                        stringResource(R.string.alerts_title),
                        color = NeonCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                when {
                    event != null -> AlertDetailBody(
                        event = event,
                        liveUnavailable = liveUnavailable,
                        onViewLive = { camera ->
                            val opened = TrekoAssistantService.showAlertCamera(
                                context,
                                camera,
                                event.place?.takeIf { it.isNotBlank() } ?: camera
                            )
                            if (opened) onDismiss() else liveUnavailable = true
                        },
                        onFeedback = { choice ->
                            val next = if (event.feedback == choice) AlertFeedback.NONE else choice
                            scope.launch(Dispatchers.IO) { TrekoAlertLog.setFeedback(event.id, next) }
                        },
                        onDelete = { confirmDelete = true }
                    )
                    !loaded -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonCyan)
                    }
                    else -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.alert_detail_gone), color = TextSecondary, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    if (confirmDelete && event != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SurfaceDark,
            text = { Text(stringResource(R.string.alert_detail_delete_confirm), color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = event.id
                    scope.launch(Dispatchers.IO) { TrekoAlertLog.delete(id) }
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete), color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertDetailBody(
    event: AlertEvent,
    liveUnavailable: Boolean,
    onViewLive: (String) -> Unit,
    onFeedback: (AlertFeedback) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val tint = alertSourceColor(event.source)
    val assistantName by TrekoAssistantService.assistantName.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val file = alertImageFile(event, preferThumb = false)
        // Timers and briefings never have a picture: no empty "No picture" box for them.
        if (AlertsListModel.showsPictureBox(event, hasPicture = file != null)) Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (file != null) {
                AsyncImage(
                    model = remember(file) { ImageRequest.Builder(context).data(file).crossfade(true).build() },
                    contentDescription = event.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(alertSourceIcon(event.source), contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.alert_detail_no_picture), color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, appLocale(context))
                .format(Date(event.timestampMs))
            Text(
                listOfNotNull(event.place?.takeIf { it.isNotBlank() }, whenText).joinToString(" · "),
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPill(stringResource(alertSourceLabel(event.source)), tint)
            InfoPill(
                stringResource(if (event.level == AlertLevel.NO_NEWS) R.string.alerts_level_no_news else R.string.alerts_level_alert),
                if (event.level == AlertLevel.NO_NEWS) TextSecondary else tint
            )
            if (event.spoken) InfoPill(stringResource(R.string.alerts_spoken), NeonCyan)
            if (event.notified) InfoPill(stringResource(R.string.alerts_notified), NeonCyan)
        }

        event.visionDescription?.takeIf { it.isNotBlank() }?.let { description ->
            DetailSection(stringResource(R.string.alert_detail_vision), description)
        }
        event.spokenSummary?.takeIf { AlertsListModel.showsSpokenSummary(event) && it.trim() != event.title.trim() }?.let { said ->
            DetailSection(stringResource(R.string.alert_detail_said, assistantName.ifBlank { "Treko" }), said)
        }

        val camera = event.cameraEntityId?.takeIf { it.isNotBlank() }
        if (camera != null) {
            Button(
                onClick = { onViewLive(camera) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.alert_detail_live), modifier = Modifier.padding(start = 8.dp))
            }
            if (liveUnavailable) {
                Text(stringResource(R.string.alert_detail_live_unavailable), color = TextSecondary, fontSize = 12.sp)
            }
        }

        if (AlertsListModel.showsFeedback(event)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = event.feedback == AlertFeedback.FALSE_ALARM,
                    onClick = { onFeedback(AlertFeedback.FALSE_ALARM) },
                    label = { Text(stringResource(R.string.alert_detail_false_alarm), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.ReportProblem, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SentinelAmber.copy(alpha = 0.2f),
                        selectedLabelColor = SentinelAmber,
                        selectedLeadingIconColor = SentinelAmber
                    )
                )
                FilterChip(
                    selected = event.feedback == AlertFeedback.USEFUL,
                    onClick = { onFeedback(AlertFeedback.USEFUL) },
                    label = { Text(stringResource(R.string.alert_detail_useful), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.ThumbUp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WatchGreen.copy(alpha = 0.2f),
                        selectedLabelColor = WatchGreen,
                        selectedLeadingIconColor = WatchGreen
                    )
                )
            }
        }

        TextButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.alert_detail_delete), color = DangerRed, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun InfoPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(body, color = TextPrimary, fontSize = 14.sp)
        }
    }
}
