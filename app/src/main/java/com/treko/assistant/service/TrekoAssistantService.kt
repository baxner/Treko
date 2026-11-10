package com.treko.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.treko.assistant.R
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.audio.OpusAudioCodec
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState
import com.treko.core.kws.TrekoWakeWordDetector
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.network.XiaozhiWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio en segundo plano para Treko AI Assistant.
 * Orquesta:
 * 1. Detección local continua de Wake Word con Sherpa-ONNX.
 * 2. Streaming bidireccional de audio Opus a 16kHz/24kHz con el servidor Xiaozhi.
 * 3. VAD (Voice Activity Detection) para corte automático tras hablar.
 * 4. Sincronización en tiempo real de estados conversacionales, subtítulos y emociones.
 */
class TrekoAssistantService : Service() {

    private val TAG = "TrekoService"
    private val CHANNEL_ID = "treko_assistant_service"
    private val NOTIFICATION_ID = 1001

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var wakeWordDetector: TrekoWakeWordDetector? = null
    private var opusCodec: OpusAudioCodec? = null
    private var wsClient: XiaozhiWebSocketClient? = null
    private var ttsDrainingJob: Job? = null
    private var thinkingTimeoutJob: Job? = null

    @Volatile
    private var speechDetected: Boolean = false

    @Volatile
    private var lastSpeechTimestamp: Long = 0L

    @Volatile
    private var listeningStartTimestamp: Long = 0L

    @Volatile
    private var activeListeningTimeoutMs: Long = 4500L

    companion object {
        private val _serviceState = MutableStateFlow(TrekoState.IDLE)
        val serviceState: StateFlow<TrekoState> = _serviceState.asStateFlow()

        private val _currentEmotion = MutableStateFlow(TrekoEmotion.RELAX)
        val currentEmotion: StateFlow<TrekoEmotion> = _currentEmotion.asStateFlow()

        private val _subtitleText = MutableStateFlow("")
        val subtitleText: StateFlow<String> = _subtitleText.asStateFlow()

        @Volatile
        private var instance: TrekoAssistantService? = null

        fun startConversation(keyword: String = "Treko") {
            instance?.beginConversation(keyword) ?: run {
                _serviceState.value = TrekoState.LISTENING
                _currentEmotion.value = TrekoEmotion.ESCUCHA
            }
        }

        fun stopConversation() {
            instance?.abortConversation()
        }

        fun updateWakeWordConfig(context: Context, keyword: String, boost: Float, threshold: Float) {
            val current = instance
            if (current != null) {
                current.wakeWordDetector?.updateKeywords(keyword, boost, threshold)
            } else {
                Log.d("TrekoService", "Servicio inactivo al actualizar KWS, iniciando...")
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

        val prefs = TrekoPreferences(this)

        // 1. Inicializar Códec Opus nativo (16kHz entrada, 48kHz salida nativa de c2.android.opus.decoder)
        opusCodec = OpusAudioCodec(inputSampleRate = 16000, outputSampleRate = 48000)

        // 2. Inicializar Cliente WebSocket Xiaozhi
        initWebSocketClient(prefs)

        // 3. Inicializar detector neuronal KWS
        initWakeWordDetector(prefs)

        // 4. Conectar audio PCM de NativeAudioEngine
        setupAudioPipeline()

        // 5. Iniciar captura de micrófono
        NativeAudioEngine.startRecording(serviceScope)
    }

    private fun initWebSocketClient(prefs: TrekoPreferences) {
        val client = XiaozhiWebSocketClient(
            serverUrl = prefs.xiaozhiWsUrl,
            macAddress = prefs.macAddress,
            clientId = prefs.clientId,
            token = prefs.xiaozhiToken
        )

        client.onSttReceived = { transcript ->
            val clean = transcript.trim()
            if (clean.equals("谢谢观看", ignoreCase = true) || clean.contains("观看") || clean.isBlank()) {
                Log.d(TAG, "Ignorando alucinación de silencio en STT: $clean")
            } else {
                _subtitleText.value = "Tú: $clean"

                val lower = clean.lowercase(Locale.ROOT)
                val isSleepIntent = listOf(
                    "adiós", "adios", "duérmete", "duermete", "cállate", "callate",
                    "hasta luego", "descansa", "eso es todo", "nada más", "nada mas",
                    "bye", "silencio", "apágate", "apagate", "a dormir", "buenas noches"
                ).any { lower.contains(it) }

                if (isSleepIntent) {
                    Log.i(TAG, "Intención de despedida detectada ('$clean'). Treko va a descansar.")
                    serviceScope.launch {
                        client.stopListening()
                        client.abortSpeaking()
                        _serviceState.value = TrekoState.THINKING
                        _subtitleText.value = "Treko: ¡Hasta luego! Zzz..."
                        _currentEmotion.value = TrekoEmotion.SLEEPY
                        delay(1200)
                        _serviceState.value = TrekoState.IDLE
                        _currentEmotion.value = TrekoEmotion.RELAX
                        _subtitleText.value = ""
                    }
                } else {
                    _serviceState.value = TrekoState.THINKING
                    _currentEmotion.value = TrekoEmotion.PIENSA
                    startThinkingTimeout()
                }
            }
        }

        client.onLlmReceived = { text, emotionStr ->
            thinkingTimeoutJob?.cancel()
            _subtitleText.value = "Treko: $text"
            _currentEmotion.value = TrekoEmotion.fromString(emotionStr)
        }

        client.onTtsStateChanged = { state, text ->
            when (state) {
                "start" -> {
                    thinkingTimeoutJob?.cancel()
                    ttsDrainingJob?.cancel()
                    _serviceState.value = TrekoState.SPEAKING
                    _currentEmotion.value = TrekoEmotion.FELIZ_ABIERTO
                }
                "sentence_start" -> {
                    if (!text.isNullOrBlank()) {
                        _subtitleText.value = "Treko: $text"
                    }
                }
                "stop" -> {
                    ttsDrainingJob?.cancel()
                    ttsDrainingJob = serviceScope.launch {
                        delay(650) // Permitir que el búfer de AudioTrack termine de sonar
                        if (_serviceState.value == TrekoState.SPEAKING) {
                            // Escucha activa natural: espera réplica multi-turno sin tener que repetir wake word
                            startActiveListening(prompt = "¿Deseas algo más?")
                        }
                    }
                }
            }
        }

        client.onAudioPacketReceived = { opusBytes ->
            opusCodec?.decodeAndPlay(opusBytes)
        }

        client.onMcpToolCall = { toolName, args ->
            handleMcpToolCall(toolName, args, prefs)
        }

        wsClient = client
        client.connect()
    }

    private fun initWakeWordDetector(prefs: TrekoPreferences) {
        val detector = TrekoWakeWordDetector(this)
        detector.initialize(
            initialKeyword = prefs.customWakeWord,
            boost = prefs.wakeWordBoost,
            threshold = prefs.wakeWordSensitivity
        )
        wakeWordDetector = detector

        // Escuchar detecciones acústicas locales
        serviceScope.launch {
            detector.detectionEvents.collect { keyword ->
                Log.i(TAG, "¡Wake word detectada ('$keyword')! Iniciando conversación...")
                beginConversation(keyword)
            }
        }
    }

    private fun setupAudioPipeline() {
        NativeAudioEngine.onPcmData = { buffer, read ->
            val currentState = _serviceState.value

            when (currentState) {
                TrekoState.IDLE -> {
                    // Alimentar detector KWS local en reposo
                    wakeWordDetector?.acceptWaveform(buffer, read)
                }
                TrekoState.SPEAKING -> {
                    // Interrupción vocal (Barge-In): permitir que el detector KWS despierte durante habla
                    wakeWordDetector?.acceptWaveform(buffer, read)
                }
                TrekoState.LISTENING -> {
                    // 1. Codificar a Opus y enviar al servidor Xiaozhi
                    val packets = opusCodec?.encodePcm(buffer, read) ?: emptyList()
                    val client = wsClient
                    if (client != null && client.isConnected) {
                        for (packet in packets) {
                            client.sendAudio(packet)
                        }
                    }

                    // 2. VAD: Detección de actividad vocal y pausa natural
                    val rms = NativeAudioEngine.audioLevel.value.rms
                    val now = System.currentTimeMillis()

                    if (rms > 0.015f) {
                        speechDetected = true
                        lastSpeechTimestamp = now
                    }

                    if (speechDetected && (now - lastSpeechTimestamp > 1250L)) {
                        // Usuario terminó de hablar tras una pausa natural de 1250ms
                        Log.i(TAG, "Fin de voz detectado por VAD. Drenando buffers y solicitando respuesta...")
                        speechDetected = false
                        // Enviar paquetes finales de Opus para no cortar la última sílaba
                        val finalPackets = opusCodec?.flushEncoder() ?: emptyList()
                        for (packet in finalPackets) {
                            client?.sendAudio(packet)
                        }
                        client?.stopListening()
                        _serviceState.value = TrekoState.THINKING
                        _currentEmotion.value = TrekoEmotion.PIENSA
                        _subtitleText.value = "Pensando..."
                        startThinkingTimeout()
                    } else if (!speechDetected && (now - listeningStartTimestamp > activeListeningTimeoutMs)) {
                        // Timeout sin hablar
                        Log.i(TAG, "Tiempo de espera agotado sin voz ($activeListeningTimeoutMs ms). Volviendo a IDLE (a dormir).")
                        client?.stopListening()
                        _serviceState.value = TrekoState.IDLE
                        _currentEmotion.value = TrekoEmotion.RELAX
                        _subtitleText.value = ""
                    }
                }
                else -> {
                    // THINKING: esperando respuesta del servidor
                }
            }
        }
    }

    private fun startThinkingTimeout() {
        thinkingTimeoutJob?.cancel()
        thinkingTimeoutJob = serviceScope.launch {
            delay(12000L) // 12s de espera máxima para respuesta del LLM
            if (_serviceState.value == TrekoState.THINKING) {
                Log.w(TAG, "Watchdog: Tiempo de espera agotado en THINKING. Recuperando a IDLE.")
                _subtitleText.value = "Sin respuesta del servidor. Intenta de nuevo."
                _currentEmotion.value = TrekoEmotion.CONFUSED
                delay(2500L)
                if (_serviceState.value == TrekoState.THINKING) {
                    _serviceState.value = TrekoState.IDLE
                    _currentEmotion.value = TrekoEmotion.RELAX
                    _subtitleText.value = ""
                }
            }
        }
    }

    fun beginConversation(keyword: String = "Treko") {
        thinkingTimeoutJob?.cancel()
        ttsDrainingJob?.cancel()
        opusCodec?.stopPlayback()
        opusCodec?.resetEncoder()
        wsClient?.abortSpeaking()

        speechDetected = false
        val now = System.currentTimeMillis()
        listeningStartTimestamp = now
        lastSpeechTimestamp = now
        activeListeningTimeoutMs = 5000L

        _serviceState.value = TrekoState.LISTENING
        _currentEmotion.value = TrekoEmotion.ESCUCHA
        _subtitleText.value = "Escuchando..."

        val client = wsClient
        if (client != null) {
            if (!client.isConnected) {
                client.connect()
            }
            client.startListening()
        }
    }

    fun startActiveListening(prompt: String = "¿Deseas algo más?") {
        thinkingTimeoutJob?.cancel()
        ttsDrainingJob?.cancel()
        opusCodec?.resetEncoder()
        speechDetected = false
        val now = System.currentTimeMillis()
        listeningStartTimestamp = now
        lastSpeechTimestamp = now
        activeListeningTimeoutMs = 7000L // 7 segundos para réplica multi-turno

        _serviceState.value = TrekoState.LISTENING
        _currentEmotion.value = TrekoEmotion.ESCUCHA
        _subtitleText.value = prompt

        val client = wsClient
        if (client != null) {
            if (!client.isConnected) {
                client.connect()
            }
            client.startListening()
        }
    }

    private suspend fun handleMcpToolCall(
        name: String,
        args: JSONObject,
        prefs: TrekoPreferences
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "get_device_status" -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        bm?.isCharging == true
                    } else false
                    val sdf = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES"))
                    val currentTime = sdf.format(Date())
                    "Dispositivo Treko: Batería al $batteryLevel% (Cargando: $isCharging). Fecha y hora actual: $currentTime."
                }
                "manage_vault_memory" -> {
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    val vaultPrefs = getSharedPreferences("treko_vault_memory", Context.MODE_PRIVATE)

                    when (action) {
                        "set" -> {
                            if (key.isBlank()) return@withContext "Error: Se requiere una clave ('key') para guardar en Vault."
                            vaultPrefs.edit().putString(key, value).apply()
                            "Recuerdo guardado con éxito en Vault: '$key' = '$value'."
                        }
                        "get" -> {
                            if (key.isBlank()) return@withContext "Error: Se requiere una clave ('key') para consultar Vault."
                            val stored = vaultPrefs.getString(key, null)
                            if (stored != null) {
                                "Vault[$key]: $stored"
                            } else {
                                "No se encontró ningún registro para '$key' en la memoria Vault."
                            }
                        }
                        "list" -> {
                            val all = vaultPrefs.all
                            if (all.isEmpty()) {
                                "La memoria Vault está vacía actualmente."
                            } else {
                                val items = all.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                "Memorias guardadas en Vault: $items"
                            }
                        }
                        "delete" -> {
                            vaultPrefs.edit().remove(key).apply()
                            "Recuerdo '$key' eliminado de la memoria Vault."
                        }
                        else -> "Acción '$action' no reconocida para manage_vault_memory."
                    }
                }
                "home_assistant_control" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko. Por favor ingresa la URL y el Token en Ajustes."
                    }

                    val entityId = args.optString("entity_id", "")
                    val action = args.optString("action", "turn_on")
                    val brightness = args.optInt("brightness", 0)
                    val kelvin = args.optInt("kelvin", 0)
                    val color = args.optString("color", "").trim()
                    val colorOrTemp = if (color.isNotBlank()) color else if (kelvin > 0) "${kelvin}k" else ""

                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.controlDevice(
                        deviceQuery = entityId,
                        accion = action,
                        brillo = brightness,
                        colorOrTemp = colorOrTemp
                    )
                }
                "home_assistant_list_entities" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val domain = args.optString("domain", "light")
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.listEntities(domain)
                }
                else -> "Herramienta '$name' no implementada en Treko."
            }
        } catch (e: Exception) {
            "Error ejecutando $name: ${e.message}"
        }
    }

    fun abortConversation() {
        thinkingTimeoutJob?.cancel()
        ttsDrainingJob?.cancel()
        opusCodec?.stopPlayback()
        wsClient?.abortSpeaking()
        _serviceState.value = TrekoState.IDLE
        _currentEmotion.value = TrekoEmotion.RELAX
        _subtitleText.value = ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NativeAudioEngine.isRecording) {
            NativeAudioEngine.startRecording(serviceScope)
        }
        val client = wsClient
        if (client != null && !client.isConnected) {
            client.connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeAudioEngine.onPcmData = null
        NativeAudioEngine.stopRecording()

        wakeWordDetector?.release()
        wakeWordDetector = null

        opusCodec?.release()
        opusCodec = null

        wsClient?.disconnect()
        wsClient = null

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
