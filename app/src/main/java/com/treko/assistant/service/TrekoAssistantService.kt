package com.treko.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.treko.assistant.R
import com.treko.core.common.model.TrekoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Servicio en segundo plano con permisos de micrófono para detección continua de "Treko".
 */
class TrekoAssistantService : Service() {

    private val TAG = "TrekoService"
    private val CHANNEL_ID = "treko_assistant_service"
    private val NOTIFICATION_ID = 1001

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private val _serviceState = MutableStateFlow(TrekoState.IDLE)
        val serviceState: StateFlow<TrekoState> = _serviceState.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, TrekoAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TrekoAssistantService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.status_idle))
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "TrekoAssistantService iniciado en primer plano.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _serviceState.value = TrekoState.IDLE
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _serviceState.value = TrekoState.IDLE
        Log.i(TAG, "TrekoAssistantService destruido.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Treko AI Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la escucha activa para despertar con 'Treko'"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
