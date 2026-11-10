package com.treko.core.network

import android.util.Log
import com.treko.core.common.model.TrekoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket de alta velocidad para streaming de audio Opus y mensajes Xiaozhi JSON-RPC.
 */
class XiaozhiWebSocketClient(
    private val serverUrl: String,
    private val token: String
) {
    private val TAG = "TrekoNetwork"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(TrekoState.IDLE)
    val connectionState: StateFlow<TrekoState> = _connectionState.asStateFlow()

    private val _incomingText = MutableStateFlow("")
    val incomingText: StateFlow<String> = _incomingText.asStateFlow()

    var onAudioPacketReceived: ((ByteArray) -> Unit)? = null
    var onJsonMessageReceived: ((String) -> Unit)? = null

    fun connect() {
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Conexión WebSocket establecida con el servidor Xiaozhi.")
                _connectionState.value = TrekoState.LISTENING
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Mensaje JSON recibido: $text")
                _incomingText.value = text
                onJsonMessageReceived?.invoke(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Trama de audio Opus recibida desde el servidor
                onAudioPacketReceived?.invoke(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket cerrándose: $code / $reason")
                _connectionState.value = TrekoState.IDLE
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en conexión WebSocket: ${t.message}", t)
                _connectionState.value = TrekoState.ERROR
            }
        })
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
        _connectionState.value = TrekoState.IDLE
    }
}
