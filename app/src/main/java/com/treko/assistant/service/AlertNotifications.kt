package com.treko.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.treko.assistant.MainActivity
import com.treko.assistant.R
import com.treko.core.common.util.TrekoLogBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notifications of the alerts that matter (camera watches and the camera sentinel), shared by
 * [CameraWatchCoordinator] and the service, plus the "open this alert" intent every alert
 * notification carries ([EXTRA_ALERT_ID]).
 */
object AlertNotifications {
    private const val TAG = "AlertNotifications"

    /** Long extra: id of the [com.treko.core.common.data.TrekoAlertLog] entry the notification is about. */
    const val EXTRA_ALERT_ID = "com.treko.assistant.extra.ALERT_ID"

    /** High-importance channel of the camera alerts (watches and sentinel). */
    const val WATCH_CHANNEL_ID = "treko_watch_alerts"

    private const val WATCH_NOTIFICATION_BASE_ID = 3000

    /** Seeded from the clock so ids do not repeat across restarts while alerts are on screen. */
    private val notificationSeq = AtomicInteger(((System.currentTimeMillis() / 1000) % 100_000).toInt())

    /** A fresh id for a camera alert notification. */
    fun nextWatchNotificationId(): Int = WATCH_NOTIFICATION_BASE_ID + (notificationSeq.getAndIncrement() % 100_000)

    /**
     * Notification tag of alerts-log entry [alertId]. Every alert notification is posted and
     * cancelled with it, so a reused notification id never cancels another alert's notification.
     */
    fun tagFor(alertId: Long?): String? = alertId?.let { "alert:$it" }

    /**
     * Opens the app; with [alertId] the app can show that alert. The intent's data names the
     * alert, so two alerts never share a PendingIntent (nor overwrite each other's extras),
     * whatever their [requestCode].
     */
    fun openAppIntent(context: Context, requestCode: Int, alertId: Long?, extraFlags: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or extraFlags
            if (alertId != null) {
                data = Uri.parse("treko://alert/$alertId")
                putExtra(EXTRA_ALERT_ID, alertId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun ensureWatchChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(WATCH_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            WATCH_CHANNEL_ID,
            context.getString(R.string.watch_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.watch_channel_desc)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts (or updates, same [id]) a camera alert on the high-importance channel. Only the first
     * post of an id makes a sound; updates add the picture and the description quietly.
     * Returns false when it could not be posted (e.g. notification permission denied).
     */
    fun postCameraAlert(
        context: Context,
        id: Int,
        title: String,
        body: String?,
        picture: Bitmap?,
        alertId: Long?
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureWatchChannel(context)
        val builder = NotificationCompat.Builder(context, WATCH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_treko_notif)
            .setContentTitle("👁 $title")
            .setContentText(body ?: context.getString(R.string.watch_alert_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context, id, alertId))
        if (picture != null) {
            builder.setLargeIcon(picture)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(body)
            )
        } else if (body != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        return try {
            manager.notify(tagFor(alertId), id, builder.build())
            // Without the permission (or with Treko's notifications off) Android drops it silently.
            manager.areNotificationsEnabled()
        } catch (e: SecurityException) {
            TrekoLogBuffer.w(TAG, "Cannot post camera alert (notification permission): ${e.message}")
            false
        }
    }
}
