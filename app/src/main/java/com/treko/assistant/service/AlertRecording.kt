package com.treko.assistant.service

import android.content.Context
import android.graphics.BitmapFactory
import com.treko.assistant.R
import com.treko.assistant.data.ProactiveCategory
import com.treko.assistant.data.ProactiveInsight
import com.treko.assistant.data.SentinelRunTrace
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.model.AlertSource
import com.treko.core.common.util.LogText
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.HomeAssistantClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Writes proactive insights and camera-sentinel runs to [TrekoAlertLog], and posts the
 * sentinel's alert notification (with the picture) on the camera alerts channel.
 */
internal object AlertRecording {
    private const val TAG = "AlertRecording"
    /** How much of a vision verdict a "no news" entry keeps. */
    private const val NO_NEWS_REPORT_CHARS = 300
    private const val DETECTION_IMAGE_WAIT_MS = 10_000L

    /**
     * Records [insight] as delivered (a camera alert as SENTINEL, anything else as PROACTIVE)
     * and returns it with its [ProactiveInsight.alertId]. [trace] is the sentinel run, if any.
     */
    fun recordInsight(insight: ProactiveInsight, trace: SentinelRunTrace?, nowMs: Long = System.currentTimeMillis()): ProactiveInsight {
        if (insight.alertId != null) return insight
        val camera = insight.category == ProactiveCategory.CAMERA_ANOMALY
        val event = AlertEvent(
            timestampMs = if (camera && trace != null) trace.startedAtMs else nowMs,
            source = if (camera) AlertSource.SENTINEL else AlertSource.PROACTIVE,
            title = insight.title,
            place = if (camera) trace?.area?.takeIf { it.isNotBlank() } ?: insight.cameraNames.firstOrNull() else null,
            cameraEntityId = if (camera) insight.cameraEntityIds.firstOrNull() ?: trace?.cameraEntityId else null,
            objectLabel = if (camera) trace?.label else null,
            spokenSummary = insight.spokenSpeechText,
            visionDescription = insight.visionReport,
            latencyMs = trace?.let { (nowMs - it.startedAtMs).coerceAtLeast(0L) },
            extra = buildMap {
                put("category", insight.category.name)
                put("insight_id", insight.id)
                trace?.let { put("trigger", it.sensorId) }
            }
        )
        return insight.copy(alertId = TrekoAlertLog.record(event))
    }

    /**
     * [insight] reached the user: [spoken] out loud, or [notified]. A spoken briefing counts as
     * read; a camera alert stays unread so its picture is looked at.
     */
    fun markDelivered(insight: ProactiveInsight, spoken: Boolean = false, notified: Boolean = false) {
        val id = insight.alertId ?: return
        TrekoAlertLog.update(id) { e ->
            e.copy(
                spoken = e.spoken || spoken,
                notified = e.notified || notified,
                read = e.read || (spoken && insight.category != ProactiveCategory.CAMERA_ANOMALY)
            )
        }
    }

    /**
     * A sentinel run ended without an alert: records "no news" (the verdict was normal) or a
     * repeated alert that was not announced again. Runs that did not look (skipped, errors)
     * are only in the log line.
     */
    fun recordSentinelCheck(context: Context, trace: SentinelRunTrace, nowMs: Long = System.currentTimeMillis()) {
        val decision = trace.decision
        val level = when (decision) {
            "normal" -> AlertLevel.NO_NEWS
            "suppressed" -> AlertLevel.ALERT
            else -> return
        }
        val looks = trace.looks()
        val place = trace.area.ifBlank { looks.firstOrNull()?.cameraName.orEmpty() }
            .ifBlank { context.getString(R.string.alert_place_unknown) }
        val report = looks.filter { it.verdict.isNotBlank() }.joinToString(" | ") { look ->
            if (looks.size > 1) "${look.cameraName}: ${look.verdict}" else look.verdict
        }
        TrekoAlertLog.record(
            AlertEvent(
                timestampMs = trace.startedAtMs,
                source = AlertSource.SENTINEL,
                level = level,
                title = context.getString(
                    if (level == AlertLevel.NO_NEWS) R.string.alert_sentinel_no_news_title else R.string.alert_sentinel_repeat_title,
                    place
                ),
                place = place,
                cameraEntityId = trace.cameraEntityId,
                objectLabel = trace.label,
                visionDescription = LogText.preview(report, NO_NEWS_REPORT_CHARS).ifBlank { null },
                latencyMs = (nowMs - trace.startedAtMs).coerceAtLeast(0L),
                // Nothing to look at: never adds to the unread count.
                read = true,
                extra = buildMap {
                    put("trigger", trace.sensorId)
                    trace.reason?.let { put("reason", it) }
                }
            )
        )
    }

    /**
     * Posts the sentinel alert at once on the camera alerts channel (also when it is spoken),
     * then adds the picture: the detector's own picture when it is of the same camera, else a
     * live snapshot of the camera. The picture is kept in the alerts log too.
     */
    fun announceCameraAlert(
        context: Context,
        insight: ProactiveInsight,
        scope: CoroutineScope,
        client: HomeAssistantClient?,
        trace: SentinelRunTrace?
    ) {
        val appCtx = context.applicationContext
        val alertId = insight.alertId
        val notificationId = AlertNotifications.nextWatchNotificationId()
        val body = insight.visionReport ?: insight.summaryText
        val notified = AlertNotifications.postCameraAlert(appCtx, notificationId, insight.title, body, null, alertId)
        if (alertId != null) {
            TrekoAlertLog.update(alertId) { it.copy(notified = it.notified || notified, notificationId = notificationId) }
        }
        val primaryCamera = insight.cameraEntityIds.firstOrNull()
        val detection = trace?.detectionImage?.takeIf { primaryCamera == null || primaryCamera == trace.cameraEntityId }
        if (detection == null && (client == null || primaryCamera == null)) return
        scope.launch(Dispatchers.IO) {
            val bytes = detection?.let { withTimeoutOrNull(DETECTION_IMAGE_WAIT_MS) { runCatching { it.await() }.getOrNull() } }
                ?: primaryCamera?.let { client?.fetchEntityImage(it) }
                ?: return@launch
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bitmap != null) {
                AlertNotifications.postCameraAlert(appCtx, notificationId, insight.title, body, bitmap, alertId)
            }
            if (alertId != null && !TrekoAlertLog.attachSnapshot(alertId, bytes)) {
                TrekoLogBuffer.w(TAG, "Could not keep the picture of sentinel alert $alertId.")
            }
        }
    }
}
