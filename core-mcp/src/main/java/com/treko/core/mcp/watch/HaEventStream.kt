package com.treko.core.mcp.watch

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Live connection to Home Assistant's WebSocket API that reports when any watched
 * binary sensor turns on. Subscribes with a state trigger limited to the watched
 * entities, so Home Assistant only sends what matters. Reconnects with backoff while
 * there is something to watch; closes when the watch list is empty.
 */
class HaEventStream(
    baseUrl: String,
    private val token: String,
    private val onSensorOn: (entityId: String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val wsUrl = toWebSocketUrl(baseUrl)
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    private var socket: WebSocket? = null
    private var authenticated = false
    private var nextMessageId = 1
    private var subscriptionId: Int? = null
    private var watched: Set<String> = emptySet()
    private var reconnectDelayMs = INITIAL_BACKOFF_MS
    private var reconnect: ScheduledFuture<*>? = null
    private var released = false
    private var authRejected = false

    /** Replaces the set of sensors to listen to. */
    fun setWatched(entityIds: Set<String>) {
        synchronized(lock) {
            if (released || entityIds == watched) return
            watched = entityIds
            when {
                entityIds.isEmpty() -> closeLocked()
                socket == null -> connectLocked()
                authenticated -> subscribeLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            watched = emptySet()
            closeLocked()
        }
        scheduler.shutdownNow()
    }

    private fun connectLocked() {
        if (released || authRejected || watched.isEmpty() || socket != null) return
        reconnect?.cancel(false)
        reconnect = null
        authenticated = false
        subscriptionId = null
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            onStatus("URL de Home Assistant inválida para eventos: ${e.message}")
            return
        }
        socket = http.newWebSocket(request, Listener())
    }

    private fun closeLocked() {
        reconnect?.cancel(false)
        reconnect = null
        socket?.close(1000, "no watches")
        socket = null
        authenticated = false
        subscriptionId = null
    }

    private fun subscribeLocked() {
        val ws = socket ?: return
        subscriptionId?.let { old ->
            ws.send(JSONObject().put("id", nextMessageId++).put("type", "unsubscribe_events").put("subscription", old).toString())
        }
        subscriptionId = null
        if (watched.isEmpty()) return
        val id = nextMessageId++
        subscriptionId = id
        val trigger = JSONObject()
            .put("platform", "state")
            .put("entity_id", JSONArray(watched.sorted()))
            .put("to", "on")
        ws.send(JSONObject().put("id", id).put("type", "subscribe_trigger").put("trigger", trigger).toString())
    }

    private fun scheduleReconnectLocked() {
        if (released || authRejected || watched.isEmpty() || reconnect != null) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        reconnect = scheduler.schedule({
            synchronized(lock) {
                reconnect = null
                connectLocked()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            var firedEntity: String? = null
            synchronized(lock) {
                if (webSocket !== socket) return
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject().put("type", "auth").put("access_token", token).toString()
                    )
                    "auth_ok" -> {
                        authenticated = true
                        reconnectDelayMs = INITIAL_BACKOFF_MS
                        onStatus("Eventos de Home Assistant conectados (${watched.size} sensores).")
                        subscribeLocked()
                    }
                    "auth_invalid" -> {
                        authRejected = true
                        onStatus("Home Assistant rechazó el token para eventos en tiempo real.")
                        closeLocked()
                    }
                    "result" -> if (!msg.optBoolean("success", true)) {
                        onStatus("Home Assistant rechazó la suscripción: ${msg.optJSONObject("error")?.optString("message")}")
                    }
                    "event" -> if (msg.optInt("id") == subscriptionId) {
                        val trigger = msg.optJSONObject("event")?.optJSONObject("variables")?.optJSONObject("trigger")
                        val entity = trigger?.optString("entity_id").orEmpty()
                        val toState = trigger?.optJSONObject("to_state")?.optString("state")
                        if (entity.isNotBlank() && (toState == null || toState == "on")) firedEntity = entity
                    }
                }
            }
            firedEntity?.let { onSensorOn(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "HA event stream failed: ${t.message}")
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(webSocket)
        }

        private fun dropped(webSocket: WebSocket) {
            synchronized(lock) {
                if (webSocket !== socket) return
                socket = null
                authenticated = false
                subscriptionId = null
                scheduleReconnectLocked()
            }
        }
    }

    companion object {
        private const val TAG = "HaEventStream"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** `http(s)://host:8123[/]` → `ws(s)://host:8123/api/websocket`. */
        fun toWebSocketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val ws = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
                else -> trimmed
            }
            return "$ws/api/websocket"
        }
    }
}
