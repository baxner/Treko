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
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoState
import com.treko.core.kws.TrekoWakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Servicio en segundo plano con permisos de micrófono para detección continua de la palabra de activación ("Treko" o personalizada).
 */
class TrekoAssistantService : Service() {

    private val TAG = "TrekoService"
    private val CHANNEL_ID = "treko_assistant_service"
    private val NOTIFICATION_ID = 1001

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeWordDetector: TrekoWakeWordDetector? = null

    companion object {
        private val _serviceState = MutableStateFlow(TrekoState.IDLE)
        val serviceState: StateFlow<TrekoState> = _serviceState.asStateFlow()

        @Volatile
        private var instance: TrekoAssistantService? = null

        fun updateWakeWordConfig(context: Context, keyword: String, boost: Float, threshold: Float) {
            val current = instance
            if (current != null) {
                current.wakeWordDetector?.updateKeywords(keyword, boost, threshold)
            } else {
                Log.d("TrekoService", "Servicio inactivo al actualizar KWS, iniciando servicio...")
                startService(context)
            }
        }

        fun setServiceState(state: TrekoState) {
            _serviceState.value = state
        }

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
        instance = this
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.status_idle))
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "TrekoAssistantService iniciado en primer plano.")

        // Inicializar detector KWS local con configuración de preferencias
        val prefs = TrekoPreferences(this)
        val detector = TrekoWakeWordDetector(this)
        detector.initialize(
            initialKeyword = prefs.customWakeWord,
            boost = prefs.wakeWordBoost,
            threshold = prefs.wakeWordSensitivity
        )
        wakeWordDetector = detector

        // Conectar el buffer de audio PCM del motor nativo con el detector KWS
        NativeAudioEngine.onPcmData = { buffer, read ->
            detector.acceptWaveform(buffer, read)
        }

        // Suscribirse a eventos de detección del Wake Word
        serviceScope.launch {
            detector.detectionEvents.collect { keyword ->
                Log.i(TAG, "¡Palabra de activación detectada por KWS en servicio: '$keyword'! Cambiando a LISTENING.")
                _serviceState.value = TrekoState.LISTENING
            }
        }

        // Iniciar captura continua de micrófono
        NativeAudioEngine.startRecording(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _serviceState.value = TrekoState.IDLE
        if (!NativeAudioEngine.isRecording) {
            NativeAudioEngine.startRecording(serviceScope)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeAudioEngine.onPcmData = null
        NativeAudioEngine.stopRecording()
        wakeWordDetector?.release()
        wakeWordDetector = null
        serviceScope.cancel()
        _serviceState.value = TrekoState.IDLE
        if (instance == this) {
            instance = null
        }
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
                description = "Mantiene la escucha activa para despertar con Treko"
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
