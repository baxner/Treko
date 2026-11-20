package com.treko.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.treko.assistant.MainActivity
import com.treko.assistant.R
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.mcp.watch.CameraWatchBook
import com.treko.core.mcp.watch.HaEventStream
import com.treko.core.mcp.watch.WatchCatalog
import com.treko.core.mcp.watch.WatchHit
import com.treko.core.mcp.watch.WatchPersistence
import com.treko.core.mcp.watch.WatchSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Tell me when you see someone in …": keeps the watches, listens to Home Assistant in real
 * time and delivers each alert at once (notification + voice), then adds a description.
 * Works with whatever the home has: Frigate sensors, or generic motion/occupancy sensors.
 */
class CameraWatchCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haClientProvider: () -> HomeAssistantClient?,
    /** Speaks a short alert with the phone's voice; the service decides if it may talk now. */
    private val speak: suspend (String) -> Unit,
    private val addChat: (String) -> Unit,
    /** True when the camera sentinel should follow Frigate detections. */
    private val sentinelEnabled: () -> Boolean = { false },
    /** Frigate saw something on a camera: the sentinel takes a look at it. */
    private val onSentinelDetection: suspend (cameraName: String) -> Unit = {}
) {
    private val book = CameraWatchBook(SharedPrefsWatchPersistence(context))
    private var stream: HaEventStream? = null
    private var streamClient: HomeAssistantClient? = null
    private var catalog: List<WatchSensor> = emptyList()
    private var expiryJob: Job? = null
    private val notificationSeq = AtomicInteger(0)
    private var sentinelSensors: Set<String> = emptySet()
    private val lastSentinelRunByCamera = HashMap<String, Long>()

    private val _activeAreas = MutableStateFlow<List<String>>(emptyList())
    /** Places being watched right now, for the UI and the persistent notification. */
    val activeAreas: StateFlow<List<String>> = _activeAreas.asStateFlow()

    val hasActiveWatches: Boolean get() = book.active(now()).isNotEmpty()

    /** Restores saved watches and reconnects; call on service start and on config change. */
    fun start() {
        ensureChannel()
        scope.launch(Dispatchers.IO) {
            refreshCatalog()
            syncStream(rebuild = true)
        }
        if (expiryJob?.isActive != true) {
            expiryJob = scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(30_000L)
                    // New cameras or sensors in Home Assistant show up within 15 minutes.
                    if (++ticks % 30 == 0) withContext(Dispatchers.IO) { refreshCatalog() }
                    syncStream(rebuild = false)
                }
            }
        }
    }

    fun release() {
        expiryJob?.cancel()
        stream?.release()
        stream = null
    }

    fun stopAll(): Int {
        val removed = book.stop(emptyList()).size
        syncStream(rebuild = false)
        return removed
    }

    /** MCP tool `watch_cameras`. */
    suspend fun handleTool(args: JSONObject): String {
        val action = args.optString("action", "start").lowercase()
        val places = readList(args, "places")
        return when (action) {
            "stop" -> {
                val removed = book.stop(places)
                syncStream(rebuild = false)
                if (removed.isEmpty()) "No había ninguna vigilancia activa${if (places.isEmpty()) "" else " en ${places.joinToString()}"}."
                else "Dejé de vigilar: ${removed.flatMap { it.areaNames }.distinct().joinToString()}."
            }
            "list" -> describeActive()
            else -> startWatch(args, places)
        }
    }

    private suspend fun startWatch(args: JSONObject, places: List<String>): String {
        if (haClientProvider() == null) return "Home Assistant no está configurado en Treko."
        if (!refreshCatalog() && catalog.isEmpty()) {
            return "No pude consultar Home Assistant para saber qué cámaras y sensores hay."
        }
        if (catalog.isEmpty()) {
            return "Este Home Assistant no tiene Frigate ni sensores de movimiento u ocupación que pueda vigilar."
        }
        val objectGroup = args.optString("object", "person").lowercase().takeIf { it in WatchCatalog.OBJECT_GROUPS } ?: "person"
        val resolution = WatchCatalog.resolve(catalog, places, objectGroup)
        if (resolution.sensors.isEmpty()) {
            val asked = resolution.unknownQueries.ifEmpty { places }
            return "No encontré dónde vigilar ${asked.joinToString { "'$it'" }}. No activé nada. " +
                "Lugares disponibles: ${resolution.availableAreas.joinToString()}."
        }
        val minutes = args.optInt("duration_minutes", 0).takeIf { it > 0 } ?: (12 * 60)
        val once = args.optBoolean("once", false)
        val areaNames = resolution.sensors.map { it.areaName }.distinct()
        val watch = book.add(
            sensorIds = resolution.sensors.map { it.entityId },
            areaNames = areaNames,
            objectGroup = objectGroup,
            durationMs = minutes * 60_000L,
            once = once,
            nowMs = now()
        )
        syncStream(rebuild = false)
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(watch.expiresAtMs))
        val generic = resolution.sensors.filter { it.source == WatchSensor.Source.GENERIC }.map { it.areaName }.distinct()
        val notes = buildList {
            if (resolution.unknownQueries.isNotEmpty()) {
                add("No encontré ${resolution.unknownQueries.joinToString { "'$it'" }}; lugares disponibles: ${resolution.availableAreas.joinToString()}.")
            }
            if (generic.isNotEmpty()) {
                add("En ${generic.joinToString()} solo hay sensor de movimiento: avisaré de cualquier movimiento, no solo de ${objectLabel(objectGroup)}.")
            }
        }
        return "Vigilando ${areaNames.joinToString()} (${objectLabel(objectGroup)}) " +
            (if (once) "hasta el primer aviso, como máximo hasta las $until. " else "hasta las $until o hasta que me digas que pare. ") +
            "Aviso con voz y notificación con foto." +
            (if (notes.isEmpty()) "" else " " + notes.joinToString(" "))
    }

    private fun describeActive(): String {
        val active = book.active(now())
        if (active.isEmpty()) return "No hay ninguna vigilancia activa."
        val format = DateFormat.getTimeInstance(DateFormat.SHORT)
        return "Vigilancias activas: " + active.joinToString("; ") { w ->
            "${w.areaNames.joinToString()} (${objectLabel(w.objectGroup)}${if (w.once) ", un solo aviso" else ""}) hasta las ${format.format(Date(w.expiresAtMs))}"
        } + "."
    }

    /** Points the event stream at the currently watched sensors and updates the UI list. */
    @Synchronized
    private fun syncStream(rebuild: Boolean) {
        val active = book.active(now())
        _activeAreas.value = active.flatMap { it.areaNames }.distinct()
        sentinelSensors = if (sentinelEnabled()) {
            catalog.filter {
                it.source == WatchSensor.Source.FRIGATE_CAMERA && it.objectType in SENTINEL_OBJECTS
            }.map { it.entityId }.toSet()
        } else {
            emptySet()
        }
        val ids = active.flatMap { it.sensorIds }.toSet() + sentinelSensors
        val client = haClientProvider()
        if (rebuild || client !== streamClient) {
            stream?.release()
            stream = null
            streamClient = client
        }
        if (ids.isEmpty()) {
            stream?.setWatched(emptySet())
            return
        }
        val current = stream ?: client?.openEventStream(
            onSensorOn = { entityId -> scope.launch { onSensorOn(entityId) } },
            onStatus = { TrekoLogBuffer.i(TAG, it) }
        )?.also { stream = it }
        current?.setWatched(ids)
    }

    private suspend fun refreshCatalog(): Boolean {
        val fresh = haClientProvider()?.getWatchCatalog() ?: return false
        catalog = fresh
        return true
    }

    private suspend fun onSensorOn(entityId: String) {
        if (entityId in sentinelSensors) maybeRunSentinel(entityId)
        val hit = book.onSensorActivated(entityId, now()) ?: return
        if (hit.watch.once) syncStream(rebuild = false)
        deliver(hit)
    }

    private suspend fun maybeRunSentinel(entityId: String) {
        val sensor = catalog.firstOrNull { it.entityId == entityId } ?: return
        val camera = sensor.cameraEntityId ?: return
        val nowMs = now()
        synchronized(lastSentinelRunByCamera) {
            val last = lastSentinelRunByCamera[camera]
            if (last != null && nowMs - last < SENTINEL_COOLDOWN_MS) return
            lastSentinelRunByCamera[camera] = nowMs
        }
        onSentinelDetection(sensor.areaName)
    }

    private suspend fun deliver(hit: WatchHit) {
        val sensor = catalog.firstOrNull { it.entityId == hit.sensorId }
        val area = sensor?.areaName ?: hit.watch.areaNames.firstOrNull().orEmpty()
        val text = alertText(sensor, hit.watch.objectGroup, area)
        TrekoLogBuffer.i(TAG, "Watch alert: $text (${hit.sensorId})")
        val notificationId = NOTIFICATION_BASE_ID + (notificationSeq.getAndIncrement() % 50)

        // 1. Right away: notification and voice. Nothing slow before this.
        post(notificationId, text, null, null)
        addChat("👁 $text")
        val ringer = context.getSystemService(AudioManager::class.java)?.ringerMode
        if (ringer == null || ringer == AudioManager.RINGER_MODE_NORMAL) {
            speak(text)
        }

        // 2. Then the picture, and a short description when a vision model is available.
        val client = haClientProvider() ?: return
        val (imageEntity, cameraEntity) = pictureSources(client, sensor)
        val image = imageEntity?.let { client.fetchEntityImage(it) }
        val bitmap = image?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bitmap != null) post(notificationId, text, null, bitmap)
        val camera = cameraEntity ?: return
        val description = runCatching {
            client.analyzeCameraWithLlmVision(camera, context.getString(R.string.watch_vision_question))
        }.getOrNull()?.trim()
        if (!description.isNullOrBlank() && !description.startsWith("Error", ignoreCase = true)) {
            post(notificationId, text, description, bitmap)
            addChat("👁 $area: $description")
        }
    }

    /** Frigate's detection picture when there is one; otherwise a live camera snapshot. */
    private suspend fun pictureSources(client: HomeAssistantClient, sensor: WatchSensor?): Pair<String?, String?> {
        if (sensor == null) return null to null
        if (sensor.source != WatchSensor.Source.FRIGATE_ZONE) {
            return (sensor.snapshotEntityId ?: sensor.cameraEntityId) to sensor.cameraEntityId
        }
        // A zone has no camera of its own: use a camera that sees the same object right now.
        val sameObject = catalog.filter { it.source == WatchSensor.Source.FRIGATE_CAMERA && it.objectType == sensor.objectType }
        val states = client.getStates(sameObject.map { it.entityId }.toSet())
        val seeing = sameObject.firstOrNull { states[it.entityId] == "on" } ?: return null to null
        return (seeing.snapshotEntityId ?: seeing.cameraEntityId) to seeing.cameraEntityId
    }

    private fun alertText(sensor: WatchSensor?, objectGroup: String, area: String): String {
        val res = when {
            sensor?.source == WatchSensor.Source.GENERIC -> R.string.watch_alert_motion
            else -> when (sensor?.objectType ?: objectGroup) {
                "person" -> R.string.watch_alert_person
                "car", "motorcycle", "bicycle" -> R.string.watch_alert_vehicle
                "dog", "cat", "bird", "horse", "bear", "deer", "animal" -> R.string.watch_alert_animal
                "package" -> R.string.watch_alert_package
                else -> R.string.watch_alert_any
            }
        }
        return context.getString(res, area)
    }

    private fun objectLabel(group: String): String = when (group) {
        "car" -> "vehículos"
        "animal" -> "animales"
        "package" -> "paquetes"
        "any" -> "cualquier objeto"
        else -> "personas"
    }

    private fun post(id: Int, title: String, body: String?, picture: android.graphics.Bitmap?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_treko_notif)
            .setContentTitle("👁 $title")
            .setContentText(body ?: context.getString(R.string.watch_alert_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (picture != null) {
            builder.setLargeIcon(picture)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
                    .setSummaryText(body)
            )
        } else if (body != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            TrekoLogBuffer.w(TAG, "Cannot post watch alert (notification permission): ${e.message}")
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.watch_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.watch_channel_desc)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun readList(args: JSONObject, key: String): List<String> {
        val raw = args.opt(key)
        return when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.split(',', ';').map { it.trim() }
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun now() = System.currentTimeMillis()

    private class SharedPrefsWatchPersistence(context: Context) : WatchPersistence {
        private val prefs = context.applicationContext.getSharedPreferences("treko_watches", Context.MODE_PRIVATE)
        override fun save(encoded: String) {
            prefs.edit().putString("state", encoded).apply()
        }
        override fun load(): String? = prefs.getString("state", null)
    }

    companion object {
        private const val TAG = "CameraWatch"
        const val CHANNEL_ID = "treko_watch_alerts"
        private const val NOTIFICATION_BASE_ID = 3000
        private val SENTINEL_OBJECTS = setOf("person", "car")
        private const val SENTINEL_COOLDOWN_MS = 10 * 60_000L
    }
}
