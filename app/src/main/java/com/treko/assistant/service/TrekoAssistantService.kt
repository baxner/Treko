package com.treko.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.treko.assistant.R
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.audio.OpusAudioCodec
import com.treko.core.common.data.TrekoHabitManager
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.data.TrekoTaskManager
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.model.CameraFeedState
import com.treko.core.common.model.ChatMessage
import com.treko.core.common.model.TimerMode
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

    private var lastSoundTimestamp: Long = System.currentTimeMillis()
    private var isTorchOn: Boolean = false

    @Volatile
    private var speechDetected: Boolean = false

    @Volatile
    private var lastSpeechTimestamp: Long = 0L

    @Volatile
    private var listeningStartTimestamp: Long = 0L

    @Volatile
    private var speakingStartTimestamp: Long = 0L

    @Volatile
    private var activeListeningTimeoutMs: Long = 4500L

    companion object {
        private val _serviceState = MutableStateFlow(TrekoState.IDLE)
        val serviceState: StateFlow<TrekoState> = _serviceState.asStateFlow()

        private val _currentEmotion = MutableStateFlow(TrekoEmotion.RELAX)
        val currentEmotion: StateFlow<TrekoEmotion> = _currentEmotion.asStateFlow()

        private val _subtitleText = MutableStateFlow("")
        val subtitleText: StateFlow<String> = _subtitleText.asStateFlow()

        private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
        val conversationHistory: StateFlow<List<ChatMessage>> = _conversationHistory.asStateFlow()

        fun addChatMessage(sender: String, text: String) {
            val clean = text.trim()
            if (clean.isBlank()) return
            val current = _conversationHistory.value.toMutableList()
            val last = current.lastOrNull()
            val now = System.currentTimeMillis()
            if (sender == "Treko" && last != null && last.sender == "Treko" && (now - last.timestamp < 10000L)) {
                // Concatenar oraciones continuas de la misma respuesta
                current[current.lastIndex] = last.copy(text = "${last.text} $clean", timestamp = now)
                _conversationHistory.value = current
            } else {
                current.add(ChatMessage(sender = sender, text = clean, timestamp = now))
                _conversationHistory.value = current.takeLast(60)
            }
        }

        fun clearHistory() {
            _conversationHistory.value = emptyList()
        }

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

        private val _activeCameraFeed = MutableStateFlow<CameraFeedState?>(null)
        val activeCameraFeed: StateFlow<CameraFeedState?> = _activeCameraFeed.asStateFlow()

        fun showCameraFeed(state: CameraFeedState) {
            _activeCameraFeed.value = state
        }

        fun dismissCameraFeed() {
            _activeCameraFeed.value = null
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
                addChatMessage("Tú", clean)

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
                        addChatMessage("Treko", "¡Hasta luego! Zzz...")
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
                    speakingStartTimestamp = System.currentTimeMillis()
                    _serviceState.value = TrekoState.SPEAKING
                    _currentEmotion.value = TrekoEmotion.FELIZ_ABIERTO
                }
                "sentence_start" -> {
                    if (!text.isNullOrBlank()) {
                        _subtitleText.value = "Treko: $text"
                        addChatMessage("Treko", text)
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
                Log.i(TAG, "¡Wake word / Comando KWS detectado ('$keyword')! Estado actual: ${_serviceState.value}")
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

                    val rms = NativeAudioEngine.audioLevel.value.rms
                    val now = System.currentTimeMillis()

                    if (rms > 0.015f) {
                        // Sonido ambiente o presencia: despertar a expresión relajada
                        lastSoundTimestamp = now
                        if (_currentEmotion.value == TrekoEmotion.SLEEPY) {
                            _currentEmotion.value = TrekoEmotion.RELAX
                        }
                    } else if (now - lastSoundTimestamp > 5000L) {
                        // Silencio absoluto prolongado (> 5s): Treko se duerme
                        if (_currentEmotion.value != TrekoEmotion.SLEEPY) {
                            _currentEmotion.value = TrekoEmotion.SLEEPY
                        }
                    }
                }
                TrekoState.SPEAKING -> {
                    // Barge-In vocal limpio: permitir que el usuario interrumpa diciendo "Treko",
                    // ignorando los primeros 650ms de habla para evitar transitorios acústicos del altavoz
                    if (System.currentTimeMillis() - speakingStartTimestamp > 650L) {
                        wakeWordDetector?.acceptWaveform(buffer, read)
                    }
                }
                TrekoState.LISTENING -> {
                    lastSoundTimestamp = System.currentTimeMillis()

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
        _subtitleText.value = "Escuchando..."

        if (keyword.equals("Treko", ignoreCase = true)) {
            // Reacción visual instantánea de alegría al escuchar 'Treko'
            _currentEmotion.value = TrekoEmotion.FELIZ_ABIERTO
            serviceScope.launch {
                delay(350)
                if (_serviceState.value == TrekoState.LISTENING) {
                    _currentEmotion.value = TrekoEmotion.ESCUCHA
                }
            }
        } else {
            _currentEmotion.value = TrekoEmotion.ESCUCHA
        }

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
                "home_assistant_call_service" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val domain = args.optString("domain", "").trim()
                    val service = args.optString("service", "").trim()

                    val serviceData = when {
                        args.has("service_data") && args.optJSONObject("service_data") != null -> {
                            args.getJSONObject("service_data")
                        }
                        args.has("service_data") && args.optString("service_data").startsWith("{") -> {
                            try {
                                JSONObject(args.getString("service_data"))
                            } catch (_: Exception) {
                                JSONObject()
                            }
                        }
                        else -> {
                            val payload = JSONObject()
                            val it = args.keys()
                            while (it.hasNext()) {
                                val key = it.next()
                                if (key != "domain" && key != "service") {
                                    payload.put(key, args.get(key))
                                }
                            }
                            payload
                        }
                    }

                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.callService(domain, service, serviceData)
                }
                "home_assistant_broadcast" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val message = args.optString("message", "").trim()
                    val target = args.optString("target", "").trim()
                    if (message.isBlank()) {
                        return@withContext "Mensaje vacío para transmisión."
                    }
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.broadcastMessage(message, target)
                }
                "home_assistant_list_entities" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val domain = args.optString("domain", "")
                    val search = args.optString("search", "")
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.listEntities(domain, search)
                }
                "home_assistant_get_entity_state" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val entityId = args.optString("entity_id", "").trim()
                    if (entityId.isBlank()) {
                        return@withContext "Se requiere 'entity_id' para consultar su estado."
                    }
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.getEntityState(entityId)
                }
                "home_assistant_list_services" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val domain = args.optString("domain", "")
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.listServices(domain)
                }
                "home_assistant_control" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko. Por favor ingresa la URL y el Token en Ajustes."
                    }

                    val entityId = args.optString("entity_id", "")
                        .ifBlank { args.optString("device", "") }
                        .ifBlank { args.optString("name", "") }
                        .ifBlank { args.optString("target", "") }
                        .ifBlank { args.optString("query", "") }
                    val action = args.optString("action", "turn_on")
                        .ifBlank { args.optString("service", "turn_on") }
                    val source = args.optString("source", "")
                        .ifBlank { args.optString("input", "") }
                    val brightness = args.optInt("brightness", 0)
                    val kelvin = args.optInt("kelvin", 0)
                    val color = args.optString("color", "").trim()
                    val colorOrTemp = if (color.isNotBlank()) color else if (kelvin > 0) "${kelvin}k" else ""

                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.controlDevice(
                        deviceQuery = entityId,
                        accion = action,
                        brillo = brightness,
                        colorOrTemp = colorOrTemp,
                        source = source
                    )
                }
                "manage_habits" -> {
                    val habitManager = TrekoHabitManager(this@TrekoAssistantService)
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val title = args.optString("title", "").trim()
                    val id = args.optInt("id", -1)
                    val waterCount = args.optInt("water_count", -1)

                    when (action) {
                        "add", "create" -> {
                            if (title.isBlank()) return@withContext "Error: Se requiere el nombre o título del hábito a registrar."
                            val item = habitManager.addHabit(title)
                            "Hábito registrado exitosamente: '${item.title}' con icono ${item.icon} (ID: ${item.id})."
                        }
                        "toggle", "check", "complete" -> {
                            val targetId = if (id != -1) id else {
                                val found = TrekoHabitManager.habitsFlow.value.find { it.title.contains(title, ignoreCase = true) }
                                found?.id ?: -1
                            }
                            if (targetId == -1) return@withContext "No se encontró ningún hábito que coincida con '$title'."
                            habitManager.toggleHabit(targetId)
                            val item = TrekoHabitManager.habitsFlow.value.find { it.id == targetId }
                            val state = if (item?.isDone == true) "completado" else "pendiente"
                            "Hábito '${item?.title}' marcado como $state."
                        }
                        "delete", "remove" -> {
                            val targetId = if (id != -1) id else {
                                val found = TrekoHabitManager.habitsFlow.value.find { it.title.contains(title, ignoreCase = true) }
                                found?.id ?: -1
                            }
                            if (targetId == -1) return@withContext "No se encontró ningún hábito que coincida con '$title'."
                            habitManager.deleteHabit(targetId)
                            "Hábito eliminado correctamente."
                        }
                        "set_water" -> {
                            if (waterCount < 0) return@withContext "Se requiere indicar la cantidad de vasos de agua (water_count)."
                            habitManager.setWaterGlasses(waterCount)
                            "Contador de agua actualizado a $waterCount vasos."
                        }
                        "list", "get" -> {
                            habitManager.listHabitsSummary()
                        }
                        else -> "Acción '$action' no reconocida para manage_habits."
                    }
                }
                "manage_tasks" -> {
                    val taskManager = TrekoTaskManager(this@TrekoAssistantService)
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val title = args.optString("title", "").trim()
                    val id = args.optInt("id", -1)

                    when (action) {
                        "add", "create" -> {
                            if (title.isBlank()) return@withContext "Error: Se requiere el título de la tarea a registrar."
                            val item = taskManager.addTask(title)
                            "Tarea registrada exitosamente: '${item.title}' (ID: ${item.id})."
                        }
                        "toggle", "check", "complete" -> {
                            val targetId = if (id != -1) id else {
                                val found = TrekoTaskManager.tasksFlow.value.find { it.title.contains(title, ignoreCase = true) }
                                found?.id ?: -1
                            }
                            if (targetId == -1) return@withContext "No se encontró ninguna tarea que coincida con '$title'."
                            taskManager.toggleTask(targetId)
                            val item = TrekoTaskManager.tasksFlow.value.find { it.id == targetId }
                            val state = if (item?.isDone == true) "completada" else "pendiente"
                            "Tarea '${item?.title}' marcada como $state."
                        }
                        "delete", "remove" -> {
                            val targetId = if (id != -1) id else {
                                val found = TrekoTaskManager.tasksFlow.value.find { it.title.contains(title, ignoreCase = true) }
                                found?.id ?: -1
                            }
                            if (targetId == -1) return@withContext "No se encontró ninguna tarea que coincida con '$title'."
                            taskManager.deleteTask(targetId)
                            "Tarea eliminada correctamente."
                        }
                        "list", "get" -> {
                            taskManager.listTasksSummary()
                        }
                        else -> "Acción '$action' no reconocida para manage_tasks."
                    }
                }
                "manage_timer" -> {
                    val action = args.optString("action", "status").lowercase(Locale.ROOT)
                    val minutes = args.optInt("minutes", 0)
                    val modeStr = args.optString("mode", "").lowercase(Locale.ROOT)

                    when (action) {
                        "start", "iniciar" -> {
                            if (minutes > 0) {
                                TrekoTimerManager.setCustomMinutes(minutes)
                                "Temporizador de $minutes minutos iniciado."
                            } else {
                                when {
                                    modeStr.contains("pomo") -> TrekoTimerManager.setMode(TimerMode.POMODORO)
                                    modeStr.contains("corto") || modeStr.contains("short") -> TrekoTimerManager.setMode(TimerMode.SHORT_BREAK)
                                    modeStr.contains("largo") || modeStr.contains("long") -> TrekoTimerManager.setMode(TimerMode.LONG_BREAK)
                                }
                                TrekoTimerManager.start()
                                "Temporizador iniciado: ${TrekoTimerManager.getStatusDescription()}"
                            }
                        }
                        "pause", "pausar", "detener" -> {
                            TrekoTimerManager.pause()
                            "Temporizador pausado. ${TrekoTimerManager.getStatusDescription()}"
                        }
                        "toggle", "alternar" -> {
                            TrekoTimerManager.toggle()
                            TrekoTimerManager.getStatusDescription()
                        }
                        "reset", "reiniciar" -> {
                            TrekoTimerManager.reset()
                            "Temporizador reiniciado. ${TrekoTimerManager.getStatusDescription()}"
                        }
                        "set", "fijar", "poner" -> {
                            val mins = if (minutes > 0) minutes else 10
                            TrekoTimerManager.setCustomMinutes(mins)
                            "Temporizador configurado a $mins minutos e iniciado."
                        }
                        else -> {
                            TrekoTimerManager.getStatusDescription()
                        }
                    }
                }
                "control_flashlight" -> {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    if (cm == null) {
                        "El servicio de cámara no está disponible en este dispositivo."
                    } else {
                        val cameraId = cm.cameraIdList.firstOrNull() ?: return@withContext "No se encontró flash en el dispositivo."
                        val action = args.optString("action", "toggle").lowercase(Locale.ROOT)
                        val newState = when (action) {
                            "turn_on", "on", "encender", "prender" -> true
                            "turn_off", "off", "apagar" -> false
                            else -> !isTorchOn
                        }
                        try {
                            cm.setTorchMode(cameraId, newState)
                            isTorchOn = newState
                            if (newState) "Linterna encendida." else "Linterna apagada."
                        } catch (e: Exception) {
                            "Error al controlar la linterna: ${e.message}"
                        }
                    }
                }
                "open_app" -> {
                    val appName = args.optString("app_name", "").trim().lowercase(Locale.ROOT)
                    if (appName.isBlank()) return@withContext "Error: Se requiere el nombre de la aplicación a abrir."
                    val pm = packageManager
                    val knownPackages = mapOf(
                        "spotify" to "com.spotify.music",
                        "youtube" to "com.google.android.youtube",
                        "whatsapp" to "com.whatsapp",
                        "netflix" to "com.netflix.mediaclient",
                        "home assistant" to "io.homeassistant.companion.android",
                        "chrome" to "com.android.chrome",
                        "maps" to "com.google.android.apps.maps",
                        "google maps" to "com.google.android.apps.maps",
                        "telegram" to "org.telegram.messenger",
                        "instagram" to "com.instagram.android",
                        "tiktok" to "com.zhiliaoapp.musically",
                        "camara" to "com.sec.android.app.camera",
                        "cámara" to "com.sec.android.app.camera",
                        "galeria" to "com.sec.android.gallery3d",
                        "galería" to "com.sec.android.gallery3d",
                        "reloj" to "com.sec.android.app.clockpackage",
                        "ajustes" to "com.android.settings",
                        "configuracion" to "com.android.settings",
                        "configuración" to "com.android.settings"
                    )

                    var targetPkg = knownPackages[appName]
                    if (targetPkg == null) {
                        for ((k, v) in knownPackages) {
                            if (appName.contains(k) || k.contains(appName)) {
                                targetPkg = v
                                break
                            }
                        }
                    }

                    if (targetPkg == null) {
                        val installed = pm.getInstalledApplications(0)
                        val match = installed.find { appInfo ->
                            val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
                            label.contains(appName) || appName.contains(label)
                        }
                        targetPkg = match?.packageName
                    }

                    if (targetPkg == null) {
                        return@withContext "No encontré ninguna aplicación instalada llamada '$appName'."
                    }

                    val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        "Abriendo $appName..."
                    } else {
                        "No se pudo iniciar '$appName'."
                    }
                }
                "set_device_volume" -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am == null) {
                        "Control de volumen no disponible."
                    } else {
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val action = args.optString("action", "set").lowercase(Locale.ROOT)
                        val level = args.optInt("level", -1)

                        when (action) {
                            "up", "subir" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                "Volumen subido al ${(newVol * 100) / maxVol}%."
                            }
                            "down", "bajar" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                "Volumen bajado al ${(newVol * 100) / maxVol}%."
                            }
                            "mute", "silenciar" -> {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                                "Volumen silenciado."
                            }
                            "set", "fijar" -> {
                                if (level in 0..100) {
                                    val target = ((level / 100.0) * maxVol).toInt()
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                                    "Volumen fijado al $level%."
                                } else {
                                    "Indica un porcentaje de volumen válido entre 0 y 100."
                                }
                            }
                            else -> "Volumen actual al ${(currentVol * 100) / maxVol}%."
                        }
                    }
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", -1)
                    val minute = args.optInt("minute", 0)
                    val message = args.optString("message", "Alarma Treko").trim()
                    if (hour !in 0..23 || minute !in 0..59) {
                        return@withContext "Por favor especifica una hora válida (0-23) y minutos (0-59)."
                    }
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(intent)
                        val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                        "Alarma programada con éxito para las $timeStr ($message)."
                    } catch (e: Exception) {
                        "Error al programar la alarma: ${e.message}"
                    }
                }
                "get_weather" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.getWeatherSummary()
                }
                "media_player_playback" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val action = args.optString("action", "play_pause").trim()
                    val device = args.optString("device", "").trim()
                    val volume = if (args.has("volume_level")) args.optDouble("volume_level", 0.5).toFloat() else null
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.mediaPlayerPlayback(device, action, volume)
                }
                "trigger_scene" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val scene = args.optString("scene", "").trim()
                    if (scene.isBlank()) return@withContext "Se requiere el nombre de la escena."
                    val haClient = HomeAssistantClient(haUrl, haToken)
                    haClient.triggerScene(scene)
                }
                "view_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val action = args.optString("action", "view").lowercase(Locale.ROOT)
                    val haClient = HomeAssistantClient(haUrl, haToken)

                    if (action == "dismiss" || action == "close" || action == "cerrar") {
                        dismissCameraFeed()
                        return@withContext "Cámara cerrada en pantalla."
                    }

                    if (action == "list" || cameraQuery.equals("todas", ignoreCase = true)) {
                        val cams = haClient.getCameras()
                        if (cams.isEmpty()) return@withContext "No se encontraron cámaras disponibles en Home Assistant."
                        val sb = StringBuilder("Cámaras encontradas (${cams.size}):\n")
                        cams.forEach { sb.append("• ${it.friendlyName} (${it.entityId}) [${it.state}]\n") }
                        return@withContext sb.toString().trim()
                    }

                    val snapshotPair = haClient.getCameraSnapshot(cameraQuery)
                    if (snapshotPair == null) {
                        return@withContext "No fue posible conectar con la cámara '$cameraQuery' en Home Assistant."
                    }

                    val (camInfo, _) = snapshotPair
                    val isLive = action == "live" || action == "stream" || cameraQuery.contains("vivo")
                    _activeCameraFeed.value = CameraFeedState(
                        entityId = camInfo.entityId,
                        friendlyName = camInfo.friendlyName,
                        snapshotUrl = camInfo.snapshotUrl,
                        haUrl = haUrl,
                        haToken = haToken,
                        isLive = isLive
                    )
                    "Mostrando la cámara '${camInfo.friendlyName}' en pantalla."
                }
                "analyze_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant no está configurado en los ajustes de Treko."
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val question = args.optString("question", "Describe lo que se ve en la cámara.")
                    val haClient = HomeAssistantClient(haUrl, haToken)

                    val snapshotPair = haClient.getCameraSnapshot(cameraQuery)
                    if (snapshotPair == null) {
                        return@withContext "No fue posible obtener la captura de la cámara '$cameraQuery'."
                    }

                    val (camInfo, bytes) = snapshotPair
                    _activeCameraFeed.value = CameraFeedState(
                        entityId = camInfo.entityId,
                        friendlyName = camInfo.friendlyName,
                        snapshotUrl = camInfo.snapshotUrl,
                        haUrl = haUrl,
                        haToken = haToken,
                        isLive = false
                    )

                    val sizeKb = bytes.size / 1024
                    "Captura de la cámara '${camInfo.friendlyName}' obtenida con éxito (${sizeKb} KB, estado: ${camInfo.state}). La cámara está visible en la pantalla. Responde a la pregunta del usuario: '$question' confirmando que la imagen está en pantalla y describiendo el estado de la cámara."
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = TrekoPreferences(this)
        if (!prefs.isAlwaysListeningInBackground) {
            Log.i(TAG, "Aplicación cerrada desde recientes y escucha en segundo plano desactivada. Deteniendo servicio...")
            stopSelf()
        } else {
            Log.d(TAG, "Aplicación cerrada pero 'Siempre en escucha' está activo. Treko continúa en segundo plano.")
        }
        super.onTaskRemoved(rootIntent)
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
