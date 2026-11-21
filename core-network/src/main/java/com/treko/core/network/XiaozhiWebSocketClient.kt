package com.treko.core.network

import android.util.Log
import com.treko.core.common.model.TrekoState
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Cliente WebSocket oficial para el protocolo Xiaozhi AI v1/v2.
 * Maneja:
 * - Autenticación por MAC y Client-ID.
 * - Negociación Opus a 16kHz captura y 48kHz reproducción.
 * - Escucha activa natural sin eco ni auto-disparo de la palabra clave.
 * - Soporte para herramientas MCP (Model Context Protocol).
 */
class XiaozhiWebSocketClient(
    private val serverUrl: String,
    private val macAddress: String,
    private val clientId: String,
    private val token: String = ""
) {
    private val TAG = "TrekoNetwork"
    @Volatile
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // WebSocket pings every 20 s only detect a dead link sooner. They do NOT keep an idle
        // session open: the Xiaozhi server closes it (code 1005) about 60 s after the last voice
        // activity, whatever the pings do, and the client then reconnects (see onClosing).
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(TrekoState.IDLE)
    val connectionState: StateFlow<TrekoState> = _connectionState.asStateFlow()

    @Volatile
    var sessionId: String? = null
        private set

    /** Home Assistant integrations detected for this user; gates skill tools (TrekoSkills). */
    @Volatile
    var availableSkills: Set<String> = emptySet()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var isExplicitDisconnect: Boolean = false

    @Volatile
    private var pendingListenStart: Boolean = false

    @Volatile
    private var pendingListenMode: String = "auto"

    @Volatile
    var advertiseAecFeature: Boolean = true

    @Volatile
    private var reconnectAttempt: Int = 0

    @Volatile
    private var reconnectScheduledForSocket: WebSocket? = null

    /** When the current socket opened; 0 when none is open. */
    @Volatile
    private var openedAtMs: Long = 0L

    private var reconnectJob: Job? = null

    var onSessionStarted: ((String) -> Unit)? = null
    var onSttReceived: ((String) -> Unit)? = null
    var onLlmReceived: ((text: String, emotion: String?) -> Unit)? = null
    var onTtsStateChanged: ((state: String, text: String?) -> Unit)? = null
    var onAudioPacketReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((TrekoState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onServerAlert: ((status: String, message: String) -> Unit)? = null
    var onMcpToolCall: (suspend (name: String, args: JSONObject) -> String)? = null
    /** Antes de ejecutar una herramienta MCP; devuelve un token que se pasa a [onToolCallFinished]. */
    var onToolCallStarted: ((name: String) -> Any?)? = null
    /**
     * Al terminar una herramienta MCP (con éxito o error), antes de enviar la respuesta.
     * [result] es el texto devuelto al modelo, o el mensaje de error; null si no hubo ninguno.
     */
    var onToolCallFinished: ((name: String, token: Any?, durationMs: Long, result: String?) -> Unit)? = null

    /**
     * Attempt counter to use for the next reconnect delay: back to 0 after a session that lived
     * at least [STABLE_SESSION_MS], otherwise unchanged (so the backoff keeps growing).
     * [sessionLivedMs] null = the socket never opened.
     */
    internal fun nextReconnectAttempt(current: Int, sessionLivedMs: Long?): Int =
        if (sessionLivedMs != null && sessionLivedMs >= STABLE_SESSION_MS) 0 else current

    internal fun computeReconnectDelayMs(attempt: Int): Long {
        val exp = 1L shl attempt.coerceIn(0, 4) // 1, 2, 4, 8, 16 -> 2s..32s
        val baseMs = min(2000L * exp, 30_000L)
        val jitterMs = Random.nextLong(0L, 500L)
        return baseMs + jitterMs
    }

    private fun scheduleReconnect(closedSocket: WebSocket? = null) {
        if (isExplicitDisconnect) return
        if (closedSocket != null) {
            if (reconnectScheduledForSocket === closedSocket) return
            reconnectScheduledForSocket = closedSocket
        }
        // Back off again from scratch only after a session that really worked; a server that
        // closes right after opening (quota, auth) must not be retried every 2 s forever.
        val opened = openedAtMs
        openedAtMs = 0L
        reconnectAttempt = nextReconnectAttempt(
            reconnectAttempt,
            sessionLivedMs = if (opened > 0L) System.currentTimeMillis() - opened else null
        )
        reconnectJob?.cancel()
        val delayMs = computeReconnectDelayMs(reconnectAttempt)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isConnected && !isConnecting && !isExplicitDisconnect) {
                TrekoLogBuffer.i(TAG, "Reconectando a Xiaozhi (intento #$reconnectAttempt, delay=${delayMs}ms)...")
                connect()
            }
        }
    }

    fun connect() {
        isExplicitDisconnect = false
        if (isConnected || isConnecting) {
            Log.d(TAG, "Conexión WebSocket ya se encuentra activa o en progreso.")
            return
        }

        val cleanUrl = serverUrl.trim()
        if (!cleanUrl.startsWith("ws://", ignoreCase = true) && !cleanUrl.startsWith("wss://", ignoreCase = true)) {
            val errMsg = "URL WebSocket inválida: '$serverUrl' (debe iniciar con ws:// o wss://)"
            TrekoLogBuffer.e(TAG, errMsg)
            _connectionState.value = TrekoState.ERROR
            onConnectionStateChanged?.invoke(TrekoState.ERROR)
            onError?.invoke(errMsg)
            return
        }

        isConnecting = true

        try {
            val requestBuilder = Request.Builder()
                .url(cleanUrl)
                .addHeader("Device-Id", macAddress)
                .addHeader("Client-Id", clientId)
                .addHeader("Protocol-Version", "1")

            if (token.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()
            TrekoLogBuffer.i(TAG, "Conectando a Xiaozhi WebSocket: $cleanUrl (MAC: $macAddress)...")

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (ws !== webSocket) return
                    TrekoLogBuffer.i(TAG, "Conexión WebSocket establecida con Xiaozhi.")
                    isConnecting = false
                    isConnected = true
                    openedAtMs = System.currentTimeMillis()
                    reconnectScheduledForSocket = null
                    sendHello()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (ws !== webSocket) return
                    handleJsonMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (ws !== webSocket) return
                    onAudioPacketReceived?.invoke(bytes.toByteArray())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    if (ws !== webSocket) return
                    TrekoLogBuffer.i(TAG, "WebSocket cerrándose: $code / $reason")
                    // Answer the server's close frame so the socket is released at once.
                    try {
                        ws.close(1000, null)
                    } catch (_: Exception) {
                    }
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    scheduleReconnect(ws)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (ws !== webSocket) return
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    scheduleReconnect(ws)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (ws !== webSocket) return
                    val httpInfo = response?.let { " (HTTP ${it.code})" } ?: ""
                    val errMsg = "${t.message ?: "Error de conexión"}$httpInfo"
                    TrekoLogBuffer.e(TAG, "Fallo en conexión WebSocket Xiaozhi: $errMsg")
                    isConnecting = false
                    isConnected = false
                    sessionId = null
                    _connectionState.value = TrekoState.ERROR
                    onConnectionStateChanged?.invoke(TrekoState.ERROR)
                    onError?.invoke(errMsg)
                    scheduleReconnect(ws)
                }
            })
        } catch (e: Exception) {
            isConnecting = false
            isConnected = false
            val errMsg = e.message ?: "Error iniciando WebSocket"
            TrekoLogBuffer.e(TAG, "Error construyendo conexión WebSocket: $errMsg", e)
            _connectionState.value = TrekoState.ERROR
            onConnectionStateChanged?.invoke(TrekoState.ERROR)
            onError?.invoke(errMsg)
        }
    }

    private fun sendHello() {
        try {
            val hello = JSONObject().apply {
                put("type", "hello")
                put("version", 1)
                put("features", JSONObject().apply {
                    put("mcp", true)
                    if (advertiseAecFeature) {
                        put("aec", true)
                    }
                })
                put("transport", "websocket")
                put("audio_params", JSONObject().apply {
                    put("format", "opus")
                    put("sample_rate", 16000)
                    put("channels", 1)
                    put("frame_duration", 60)
                })
            }
            sendJson(hello.toString())
            Log.d(TAG, "Mensaje hello enviado.")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando hello: ${e.message}")
        }
    }

    private fun handleJsonMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "hello" -> {
                    sessionId = if (json.has("session_id")) json.optString("session_id") else null
                    Log.i(TAG, "Negociación completada con éxito. Session ID: $sessionId")
                    _connectionState.value = TrekoState.IDLE
                    onConnectionStateChanged?.invoke(TrekoState.IDLE)
                    // A turn that started while connecting: open listening first, so the audio
                    // buffered meanwhile (flushed by onSessionStarted) follows listen:start.
                    if (pendingListenStart && sessionId != null) {
                        pendingListenStart = false
                        Log.i(TAG, "Iniciando escucha diferida tras negociación exitosa...")
                        startListening(pendingListenMode)
                    }
                    sessionId?.let { onSessionStarted?.invoke(it) }
                }
                "mcp" -> {
                    handleMcpMessage(json)
                }
                "stt" -> {
                    val transcript = json.optString("text")
                    if (transcript.isNotBlank()) {
                        Log.i(TAG, "STT reconocido: $transcript")
                        onSttReceived?.invoke(transcript)
                    }
                }
                "llm" -> {
                    val emotion = if (json.has("emotion")) json.optString("emotion") else null
                    val answer = json.optString("text", "")
                    Log.i(TAG, "LLM respuesta: texto='$answer', emoción='$emotion'")
                    onLlmReceived?.invoke(answer, emotion)
                }
                "tts" -> {
                    val state = json.optString("state")
                    val subtitle = if (json.has("text")) json.optString("text") else null
                    Log.d(TAG, "TTS estado: $state (texto: $subtitle)")
                    onTtsStateChanged?.invoke(state, subtitle)
                }
                "alert" -> {
                    val status = json.optString("status", "ERROR")
                    val message = json.optString("message", "")
                    TrekoLogBuffer.w(TAG, "Alerta del servidor Xiaozhi ($status): $message")
                    onServerAlert?.invoke(status, message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando JSON entrante: ${e.message}")
        }
    }

    private fun handleMcpMessage(json: JSONObject) {
        try {
            val payload = json.optJSONObject("payload") ?: return
            val method = payload.optString("method")
            val idValue = payload.opt("id")?.takeIf { it !== JSONObject.NULL }

            if (method == "initialize" && idValue != null) {
                val response = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", idValue)
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", JSONObject().apply {
                                // Tools depend on detected Home Assistant skills and can change.
                                put("tools", JSONObject().put("listChanged", true))
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "Treko Android")
                                put("version", "1.0.0")
                            })
                            // Usage policy for the model (MCP initialize "instructions").
                            put("instructions", TrekoToolCatalog.INITIALIZE_INSTRUCTIONS)
                        })
                    })
                }
                sendJson(response.toString())
            } else if (method == "tools/list" && idValue != null) {
                // Definitions live in TrekoToolCatalog, already filtered by the detected skills.
                val toolsArray = JSONArray(TrekoToolCatalog.toolsJson(availableSkills))

                val toolsResp = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", idValue)
                        put("result", JSONObject().apply {
                            put("tools", toolsArray)
                        })
                    })
                }
                sendJson(toolsResp.toString())
            } else if (method == "tools/call" && idValue != null) {
                val params = payload.optJSONObject("params")
                val toolName = params?.optString("name") ?: ""
                val arguments = params?.optJSONObject("arguments") ?: JSONObject()
                Log.i(TAG, "Ejecutando herramienta MCP: $toolName con argumentos: $arguments")

                scope.launch {
                    val startedAt = System.currentTimeMillis()
                    val toolToken = try {
                        onToolCallStarted?.invoke(toolName)
                    } catch (e: Exception) {
                        null
                    }
                    var loggedResult: String? = null
                    val callResp = try {
                        val resultText = onMcpToolCall?.invoke(toolName, arguments)
                            ?: "Herramienta $toolName ejecutada correctamente."
                        loggedResult = resultText
                        JSONObject().apply {
                            put("session_id", sessionId ?: "")
                            put("type", "mcp")
                            put("payload", JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", idValue)
                                put("result", JSONObject().apply {
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", resultText)
                                        })
                                    })
                                })
                            })
                        }
                    } catch (e: Exception) {
                        TrekoLogBuffer.e(TAG, "Error ejecutando herramienta MCP '$toolName'", e)
                        loggedResult = "error: ${e.message}"
                        JSONObject().apply {
                            put("session_id", sessionId ?: "")
                            put("type", "mcp")
                            put("payload", JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", idValue)
                                put("error", JSONObject().apply {
                                    put("code", -32000)
                                    put("message", e.message ?: "Error ejecutando herramienta $toolName")
                                })
                            })
                        }
                    } finally {
                        try {
                            onToolCallFinished?.invoke(toolName, toolToken, System.currentTimeMillis() - startedAt, loggedResult)
                        } catch (_: Exception) {
                        }
                    }
                    sendJson(callResp.toString())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error manejando mensaje MCP: ${e.message}")
        }
    }

    /**
     * Inicia la captura de audio en vivo sin auto-enviar la palabra de activación como texto prompt.
     */
    fun startListening(mode: String = "auto") {
        val sId = sessionId
        if (sId == null || !isConnected) {
            Log.i(TAG, "Sesión no lista al iniciar escucha. Marcando inicio diferido y conectando...")
            pendingListenMode = mode
            pendingListenStart = true
            connect()
            return
        }
        pendingListenStart = false
        val start = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "start")
            put("mode", mode)
        }
        sendJson(start.toString())
        Log.i(TAG, "Iniciada sesión de escucha para voz del usuario en Xiaozhi (Session: $sId, mode=$mode).")
    }

    fun stopListening() {
        val sId = sessionId
        if (sId == null || !isConnected) {
            Log.w(TAG, "Ignorando stopListening: sesión aún no lista.")
            pendingListenStart = false
            return
        }
        val stop = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "stop")
        }
        sendJson(stop.toString())
        Log.i(TAG, "Detenida sesión de escucha en Xiaozhi (esperando respuesta).")
    }

    /** Stops the server's current reply; [reason] says why (logged by the server). */
    fun abortSpeaking(reason: String) {
        val sId = sessionId
        if (!isConnected || sId.isNullOrBlank()) return
        val abort = JSONObject().apply {
            put("session_id", sId)
            put("type", "abort")
            put("reason", reason)
        }
        sendJson(abort.toString())
        Log.i(TAG, "Interrupción de habla enviada al servidor Xiaozhi (reason=$reason).")
    }

    /**
     * Inicia una conversación proactiva enviando un disparador breve en listen(state="detect")
     * para que Xiaozhi consulte manage_proactive_assistant sin exceder el límite de longitud del servidor.
     */
    fun sendProactiveTrigger(triggerText: String = "Treko lee tu aviso pendiente"): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected) return false
        val sId = sessionId
        if (sId.isNullOrBlank()) return false
        val raw = triggerText.trim()
        val compact = if (raw.length <= 44 && raw.isNotBlank()) raw else "Treko lee tu aviso pendiente"

        val msg = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "detect")
            put("text", compact)
        }
        val sent = ws.send(msg.toString())
        TrekoLogBuffer.i(TAG, "Enviado disparador proactivo a Xiaozhi (sent=$sent, text='$compact').")
        Log.i(TAG, "Disparador proactivo enviado a Xiaozhi (Session: $sId, sent=$sent, text='$compact').")
        return sent
    }

    fun sendProactivePrompt(prompt: String): Boolean = sendProactiveTrigger(prompt)

    fun sendAudio(opusData: ByteArray) {
        if (!isConnected || sessionId == null) return
        try {
            webSocket?.send(ByteString.of(*opusData))
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando paquete Opus: ${e.message}")
        }
    }

    /** Asks the server to fetch tools/list again after the detected skills changed (MCP). */
    fun notifyToolsChanged() {
        if (!isConnected) return
        val notification = JSONObject().apply {
            put("session_id", sessionId ?: "")
            put("type", "mcp")
            put("payload", JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/tools/list_changed")
            })
        }
        sendJson(notification.toString())
    }

    fun sendJson(json: String) {
        webSocket?.send(json)
    }

    fun disconnect() {
        isExplicitDisconnect = true
        isConnecting = false
        reconnectJob?.cancel()
        pendingListenStart = false
        val wsToClose = webSocket
        webSocket = null
        try {
            wsToClose?.close(1000, "Desconexión normal")
        } catch (e: Exception) {
            // ignore
        }
        isConnected = false
        sessionId = null
        _connectionState.value = TrekoState.IDLE
        onConnectionStateChanged?.invoke(TrekoState.IDLE)
    }

    fun release() {
        disconnect()
        scope.cancel()
        client.connectionPool.evictAll()
    }

    companion object {
        /** A session that lived this long counts as working: the reconnect backoff restarts. */
        internal const val STABLE_SESSION_MS = 10_000L
    }
}
