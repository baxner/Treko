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
 * binary sensor turns on.
 *
 * Uses `subscribe_entities`, which any Home Assistant user may call (`subscribe_trigger`
 * needs an admin), limited to the watched entities. Home Assistant first sends the current
 * state of each one, then only changes; [SensorTransitions] turns that into "turned on"
 * events, so a sensor that went on while the connection was down is still reported once.
 * Reconnects with backoff while there is something to watch; closes when the list is empty.
 */
class HaEventStream(
    baseUrl: String,
    private val token: String,
    private val onSensorOn: (entityId: String) -> Unit,
    /** Human-readable state of the live connection; [ok] false = alerts will not arrive. */
    private val onStatus: (message: String, ok: Boolean) -> Unit = { _, _ -> }
) {
    private val wsUrl = toWebSocketUrl(baseUrl)
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ha-event-stream").apply { isDaemon = true }
    }
    private val lock = Any()
    private val transitions = SensorTransitions()

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
            transitions.retainOnly(entityIds)
            when {
                entityIds.isEmpty() -> closeLocked()
                socket == null -> connectLocked()
                authenticated -> subscribeLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            watched = emptySet()
            closeLocked()
        }
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
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
            onStatus("URL de Home Assistant inválida para eventos: ${e.message}", false)
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
        ws.send(
            JSONObject()
                .put("id", id)
                .put("type", "subscribe_entities")
                .put("entity_ids", JSONArray(watched.sorted()))
                .toString()
        )
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
            val fired = ArrayList<String>()
            var status: Pair<String, Boolean>? = null
            synchronized(lock) {
                if (webSocket !== socket) return
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject().put("type", "auth").put("access_token", token).toString()
                    )
                    "auth_ok" -> {
                        authenticated = true
                        reconnectDelayMs = INITIAL_BACKOFF_MS
                        subscribeLocked()
                    }
                    "auth_invalid" -> {
                        authRejected = true
                        status = "Home Assistant rechazó el token para eventos en tiempo real." to false
                        closeLocked()
                    }
                    "result" -> if (msg.optInt("id") == subscriptionId) {
                        status = if (msg.optBoolean("success", false)) {
                            "Eventos de Home Assistant conectados (${watched.size} sensores)." to true
                        } else {
                            val error = msg.optJSONObject("error")
                            "Home Assistant rechazó la suscripción a eventos: ${error?.optString("code")} ${error?.optString("message")}" to false
                        }
                    }
                    "event" -> if (msg.optInt("id") == subscriptionId) {
                        val event = msg.optJSONObject("event") ?: return
                        fired += transitions.apply(parseCompressedStates(event))
                    }
                }
            }
            status?.let { (text, ok) -> onStatus(text, ok) }
            fired.filter { it in watchedSnapshot() }.forEach { onSensorOn(it) }
        }

        // Home Assistant closed first (restart, reload): answer and reconnect right away
        // instead of waiting for a failed ping.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket)
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
            onStatus("Conexión de eventos con Home Assistant perdida; reconectando.", false)
        }
    }

    private fun watchedSnapshot(): Set<String> = synchronized(lock) { watched }

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

        /**
         * `subscribe_entities` events: `a` = full states (`{"s": state}`), `c` = changes
         * (`{"+": {"s": state}}`), `r` = removed. Returns entity → new state.
         */
        internal fun parseCompressedStates(event: JSONObject): Map<String, String?> {
            val out = LinkedHashMap<String, String?>()
            event.optJSONObject("a")?.let { added ->
                for (key in added.keys()) out[key] = added.optJSONObject(key)?.optString("s")
            }
            event.optJSONObject("c")?.let { changed ->
                for (key in changed.keys()) {
                    val plus = changed.optJSONObject(key)?.optJSONObject("+") ?: continue
                    if (plus.has("s")) out[key] = plus.optString("s")
                }
            }
            event.optJSONArray("r")?.let { removed ->
                for (i in 0 until removed.length()) out[removed.optString(i)] = null
            }
            return out
        }
    }
}

/**
 * Remembers the last known state of each sensor and reports only off → on transitions.
 * A sensor seen for the first time already "on" counts as a transition (someone is there
 * now); after a reconnect, only sensors that were not "on" before are reported again.
 */
class SensorTransitions {
    private val last = HashMap<String, String>()

    fun apply(states: Map<String, String?>): List<String> {
        val turnedOn = ArrayList<String>()
        for ((entity, state) in states) {
            if (state == null) {
                last.remove(entity)
                continue
            }
            if (state == "on" && last[entity] != "on") turnedOn += entity
            last[entity] = state
        }
        return turnedOn
    }

    fun retainOnly(entityIds: Set<String>) {
        last.keys.retainAll(entityIds)
    }
}
