package com.treko.core.network

import android.util.Log
import com.treko.core.common.model.TrekoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket oficial para el protocolo Xiaozhi AI v1/v2.
 * Maneja autenticación por MAC/Client-Id, negociación de audio Opus a 16/24kHz,
 * control conversacional (detect, start, stop) y recepción de audio TTS + STT/LLM.
 */
class XiaozhiWebSocketClient(
    private val serverUrl: String,
    private val macAddress: String,
    private val clientId: String,
    private val token: String = ""
) {
    private val TAG = "TrekoNetwork"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(TrekoState.IDLE)
    val connectionState: StateFlow<TrekoState> = _connectionState.asStateFlow()

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    var onSessionStarted: ((String) -> Unit)? = null
    var onSttReceived: ((String) -> Unit)? = null
    var onLlmReceived: ((text: String, emotion: String?) -> Unit)? = null
    var onTtsStateChanged: ((state: String, text: String?) -> Unit)? = null
    var onAudioPacketReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((TrekoState) -> Unit)? = null

    fun connect() {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "WebSocket ya se encuentra conectado.")
            return
        }

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .addHeader("Device-Id", macAddress)
            .addHeader("Client-Id", clientId)
            .addHeader("Protocol-Version", "1")

        if (token.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val request = requestBuilder.build()
        Log.i(TAG, "Conectando a Xiaozhi WebSocket: $serverUrl (MAC: $macAddress)...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Conexión WebSocket establecida con Xiaozhi.")
                isConnected = true
                sendHello()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleJsonMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Trama Opus recibida desde el servidor Xiaozhi
                onAudioPacketReceived?.invoke(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket cerrándose: $code / $reason")
                isConnected = false
                _connectionState.value = TrekoState.IDLE
                onConnectionStateChanged?.invoke(TrekoState.IDLE)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en conexión WebSocket Xiaozhi: ${t.message}", t)
                isConnected = false
                _connectionState.value = TrekoState.ERROR
                onConnectionStateChanged?.invoke(TrekoState.ERROR)
            }
        })
    }

    private fun sendHello() {
        try {
            val hello = JSONObject().apply {
                put("type", "hello")
                put("version", 1)
                put("features", JSONObject().apply { put("mcp", true) })
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando JSON entrante: ${e.message}")
        }
    }

    private fun handleMcpMessage(json: JSONObject) {
        try {
            val payload = json.optJSONObject("payload") ?: return
            val method = payload.optString("method")
            val id = payload.optLong("id", -1)

            if (method == "initialize" && id != -1L) {
                // Responder a la inicialización de herramientas MCP
                val response = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", JSONObject().apply {
                                put("tools", JSONObject())
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "Treko Android")
                                put("version", "1.0.0")
                            })
                        })
                    })
                }
                sendJson(response.toString())
            } else if (method == "tools/list" && id != -1L) {
                val toolsResp = JSONObject().apply {
                    put("session_id", sessionId ?: "")
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("tools", org.json.JSONArray())
                        })
                    })
                }
                sendJson(toolsResp.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error manejando mensaje MCP: ${e.message}")
        }
    }

    fun startListening(wakeWord: String) {
        val sId = sessionId ?: ""
        val detect = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "detect")
            put("text", wakeWord)
        }
        sendJson(detect.toString())

        val start = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "start")
            put("mode", "auto")
        }
        sendJson(start.toString())
        Log.i(TAG, "Iniciada sesión de escucha para '$wakeWord' en Xiaozhi.")
    }

    fun stopListening() {
        val sId = sessionId ?: ""
        val stop = JSONObject().apply {
            put("session_id", sId)
            put("type", "listen")
            put("state", "stop")
        }
        sendJson(stop.toString())
        Log.i(TAG, "Detenida sesión de escucha en Xiaozhi (esperando respuesta LLM/TTS).")
    }

    fun abortSpeaking() {
        val sId = sessionId ?: ""
        val abort = JSONObject().apply {
            put("session_id", sId)
            put("type", "abort")
            put("reason", "wake_word_detected")
        }
        sendJson(abort.toString())
    }

    fun sendAudio(opusData: ByteArray) {
        webSocket?.send(ByteString.of(*opusData))
    }

    fun sendJson(json: String) {
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "Desconexión normal")
        webSocket = null
        isConnected = false
        sessionId = null
        _connectionState.value = TrekoState.IDLE
        onConnectionStateChanged?.invoke(TrekoState.IDLE)
    }
}
