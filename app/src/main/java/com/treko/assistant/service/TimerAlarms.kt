package com.treko.assistant.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.treko.assistant.MainActivity
import com.treko.assistant.R
import com.treko.core.common.data.TimerPersistence
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertSource
import com.treko.core.common.data.TimerScheduler
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.util.TrekoLogBuffer

/**
 * Conecta [TrekoTimerManager] con AlarmManager para que los temporizadores suenen aunque
 * Android haya cerrado la app, y los recupera tras reiniciar el teléfono.
 */
object TimerAlarms {
    private const val TAG = "TimerAlarms"
    const val CHANNEL_ID = "treko_timer_alerts"
    private const val ACTION_FIRED = "com.treko.assistant.action.TIMER_FIRED"
    private const val EXTRA_KEY = "timer_key"
    private const val EXTRA_LABEL = "timer_label"
    /** Ids de notificación: 2000 + clave del temporizador. */
    private const val NOTIFICATION_BASE_ID = 2000

    /** Idempotente: se llama desde la actividad, el servicio y los receptores. */
    fun install(context: Context) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        TrekoTimerManager.attach(TimerPersistence.sharedPreferences(appCtx), AlarmTimerScheduler(appCtx))
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Treko Timers & Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Treko timer or reminder finishes"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 450L, 250L, 450L, 250L, 450L)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    internal fun firedIntent(context: Context, key: Int, label: String?): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
            action = ACTION_FIRED
            putExtra(EXTRA_KEY, key)
            label?.let { putExtra(EXTRA_LABEL, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            key,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * The alarm fired: rings (notification), updates the timers and records it in the alerts
     * log. [onLogWritten] runs once the log is on disk (from a background thread); the
     * receiver keeps its process alive until then (goAsync).
     */
    internal fun handleFired(context: Context, intent: Intent, onLogWritten: () -> Unit = {}) {
        if (intent.action != ACTION_FIRED) return onLogWritten()
        try {
            handleFiredNow(context, intent, onLogWritten)
        } catch (e: RuntimeException) {
            onLogWritten()
            throw e
        }
    }

    private fun handleFiredNow(context: Context, intent: Intent, onLogWritten: () -> Unit) {
        install(context)
        val key = intent.getIntExtra(EXTRA_KEY, -1)
        if (key < 0) return onLogWritten()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Timer" }
        TrekoTimerManager.onAlarmFired(key)
        TrekoLogBuffer.i(TAG, "Timer finished: $label")
        TrekoAssistantService.addChatMessage(TrekoAssistantService.resolveAssistantName(context), "⏰ $label")
        // init() and record() do no disk work on this (main) thread: the log loads and saves
        // on its own background thread.
        TrekoAlertLog.init(context)
        val notificationId = NOTIFICATION_BASE_ID + key
        val alertId = TrekoAlertLog.record(
            AlertEvent(
                timestampMs = System.currentTimeMillis(),
                source = AlertSource.TIMER,
                title = label.ifBlank { context.getString(R.string.alert_timer_default_title) },
                objectLabel = if (key == TrekoTimerManager.MAIN_TIMER_KEY) "main_timer" else "timer",
                spokenSummary = context.getString(R.string.timer_finished_notification_body),
                notificationId = notificationId,
                // It already rang loudly: kept in the history, not added to the unread badge.
                read = true
            )
        )
        val notified = postFinishedNotification(context, notificationId, label, alertId)
        TrekoAlertLog.runInBackground {
            try {
                TrekoAlertLog.update(alertId) { it.copy(notified = notified) }
                // A receiver's process may be gone soon after it finishes: write the log now.
                TrekoAlertLog.flush()
            } finally {
                onLogWritten()
            }
        }
    }

    private fun postFinishedNotification(context: Context, notificationId: Int, label: String, alertId: Long): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val openApp = AlertNotifications.openAppIntent(context, notificationId, alertId)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $label")
            .setContentText(context.getString(R.string.timer_finished_notification_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmSound)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        return try {
            manager.notify(AlertNotifications.tagFor(alertId), notificationId, notification)
            manager.areNotificationsEnabled()
        } catch (e: SecurityException) {
            TrekoLogBuffer.w(TAG, "Cannot post timer notification (permission missing): ${e.message}")
            false
        }
    }
}

private class AlarmTimerScheduler(private val context: Context) : TimerScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(key: Int, atEpochMs: Long, label: String, totalSeconds: Int) {
        val am = alarmManager ?: return
        val fire = TimerAlarms.firedIntent(context, key, label)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                // setAlarmClock es exacto y atraviesa Doze; el icono del sistema lleva a Treko.
                val show = PendingIntent.getActivity(
                    context,
                    key,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atEpochMs, show), fire)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, fire)
            }
        } catch (e: SecurityException) {
            TrekoLogBuffer.w("TimerAlarms", "Exact alarm denied, using inexact: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, fire)
        }
    }

    override fun cancel(key: Int) {
        alarmManager?.cancel(TimerAlarms.firedIntent(context, key, null))
    }
}

/** Recibe la alarma de un temporizador terminado. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The alerts log is read and written off the main thread; goAsync keeps the process
        // alive until it is on disk.
        val pending = goAsync()
        TimerAlarms.handleFired(context, intent) { pending.finish() }
    }
}

/** Tras reiniciar el teléfono o actualizar la app, vuelve a programar los temporizadores. */
class TimerRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> TimerAlarms.install(context)
        }
    }
}
