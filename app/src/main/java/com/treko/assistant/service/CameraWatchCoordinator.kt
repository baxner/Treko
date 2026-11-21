package com.treko.assistant.service

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import com.treko.assistant.R
import com.treko.assistant.data.SentinelRunTrace
import com.treko.assistant.data.parseVisionVerdict
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoSkills
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertSource
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.mcp.watch.CameraWatch
import com.treko.core.mcp.watch.CameraWatchBook
import com.treko.core.mcp.watch.HaEventStream
import com.treko.core.mcp.watch.WatchCatalog
import com.treko.core.mcp.watch.WatchHit
import com.treko.core.mcp.watch.WatchPersistence
import com.treko.core.mcp.watch.WatchSensor
import com.treko.core.mcp.watch.ZoneCameraLearner
import com.treko.core.mcp.watch.ZoneCameraMemory
import com.treko.core.mcp.watch.ZoneCameras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

/**
 * "Tell me when you see someone in …": keeps the watches, listens to Home Assistant in real
 * time and delivers each alert at once (notification + voice), then adds a description.
 * Works with whatever the home has: Frigate sensors, or generic motion/occupancy sensors.
 */
class CameraWatchCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haClientProvider: () -> HomeAssistantClient?,
    /**
     * Speaks a short alert with the phone's voice; the service decides if it may talk now.
     * Returns true only when it was actually handed to the voice.
     */
    private val speak: suspend (String) -> Boolean,
    private val addChat: (String) -> Unit,
    /** True when the camera sentinel should follow Frigate detections. */
    private val sentinelEnabled: () -> Boolean = { false },
    /**
     * Frigate saw something on a camera: the sentinel takes a look and adds what it did to
     * [trace] (it runs with the trace in its coroutine context). False if it could not run.
     */
    private val onSentinelDetection: suspend (sensor: WatchSensor, trace: SentinelRunTrace) -> Boolean = { _, _ -> true }
) {
    private val book = CameraWatchBook(SharedPrefsWatchPersistence(context))
    /** Which camera sees each Frigate zone, learned by co-firing (same file as the watches). */
    private val zoneCameraMemory = ZoneCameraMemory(SharedPrefsWatchPersistence(context, key = "zone_cameras"))
    private val zoneCameraLearner = ZoneCameraLearner()
    private var stream: HaEventStream? = null
    private var streamClient: HomeAssistantClient? = null
    @Volatile private var catalog: List<WatchSensor> = emptyList()
    private var expiryJob: Job? = null
    @Volatile private var released = false
    /** Last problem of the live connection; null while alerts can arrive. */
    @Volatile private var liveProblem: String? = null
    private var sentinelSensors: Set<String> = emptySet()
    /** Camera → until when the sentinel leaves it alone (see [SentinelCooldown]). */
    private val sentinelRestUntilByCamera = HashMap<String, Long>()

    private val _activeAreas = MutableStateFlow<List<String>>(emptyList())
    /** Places being watched right now, for the UI and the persistent notification. */
    val activeAreas: StateFlow<List<String>> = _activeAreas.asStateFlow()

    private val _activeWatches = MutableStateFlow<List<CameraWatch>>(emptyList())
    /** The watches running right now (alerts screen), oldest first. */
    val activeWatches: StateFlow<List<CameraWatch>> = _activeWatches.asStateFlow()

    val hasActiveWatches: Boolean get() = book.active(now()).isNotEmpty()

    /** What can be watched, as last read from Home Assistant (refreshed every 15 minutes). */
    val catalogSnapshot: List<WatchSensor> get() = catalog

    /** Restores saved watches and reconnects; call on service start and on config change. */
    fun start() {
        AlertNotifications.ensureWatchChannel(context)
        scope.launch(Dispatchers.IO) {
            refreshCatalog()
            // Rebuilt if the Home Assistant URL/token changed (see syncStream) or the live
            // connection is failing (e.g. a rejected token), so a settings reload recovers it.
            syncStream(rebuild = liveProblem != null)
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

    @Synchronized
    fun release() {
        released = true
        expiryJob?.cancel()
        stream?.release()
        stream = null
        liveProblem = null
        _activeAreas.value = emptyList()
        _activeWatches.value = emptyList()
    }

    /** Stops one watch (alerts screen). False when it had already ended. */
    fun stopWatch(id: Long): Boolean {
        val removed = book.stopById(id) ?: return false
        TrekoLogBuffer.i(TAG, "Watch ${removed.id} stopped from the app (${removed.areaNames.joinToString()}).")
        syncStream(rebuild = false)
        return true
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
        // Someone may already be there: the shared stream only reports changes it has not
        // seen, so check the current state for this new watch (the cooldown avoids doubles).
        val watchedIds = resolution.sensors.map { it.entityId }.toSet()
        scope.launch(Dispatchers.IO) {
            val states = haClientProvider()?.getStates(watchedIds).orEmpty()
            watchedIds.filter { states[it] == "on" }.forEach { onSensorOn(it, replay = true) }
        }
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(watch.expiresAtMs))
        val photo = photoPromise(resolution.sensors)
        val generic = resolution.sensors.filter { it.source == WatchSensor.Source.GENERIC }.map { it.areaName }.distinct()
        val notes = buildList {
            if (resolution.unknownQueries.isNotEmpty()) {
                add("No encontré ${resolution.unknownQueries.joinToString { "'$it'" }}; lugares disponibles: ${resolution.availableAreas.joinToString()}.")
            }
            if (generic.isNotEmpty()) {
                add("En ${generic.joinToString()} solo hay sensor de movimiento: avisaré de cualquier movimiento, no solo de ${objectLabel(objectGroup)}.")
            }
            liveProblem?.let { add("Ojo: la conexión en tiempo real con Home Assistant falla ahora mismo ($it); los avisos llegarán cuando se recupere.") }
        }
        return "Vigilando ${areaNames.joinToString()} (${objectLabel(objectGroup)}) " +
            (if (once) "hasta el primer aviso, como máximo hasta las $until. " else "hasta las $until o hasta que me digas que pare. ") +
            when (photo) {
                PhotoPromise.KNOWN -> "Aviso con voz y notificación con foto."
                PhotoPromise.FROM_CAMERA_SEEING_ZONE -> "Aviso con voz y notificación con la foto de la cámara que ve esa zona."
                PhotoPromise.NONE -> "Aviso con voz y notificación (sin foto: no hay cámara asociada)."
            } +
            (if (notes.isEmpty()) "" else " " + notes.joinToString(" "))
    }

    private enum class PhotoPromise { KNOWN, FROM_CAMERA_SEEING_ZONE, NONE }

    /**
     * What the confirmation may promise about the picture: a known camera or detection picture;
     * for a Frigate zone with no known camera yet, the picture of the camera that sees it
     * (learned by co-firing or found on at alert time); nothing only without any camera.
     */
    private fun photoPromise(sensors: List<WatchSensor>): PhotoPromise {
        if (sensors.any { it.snapshotEntityId != null || cameraOf(it) != null }) return PhotoPromise.KNOWN
        val zoneWithCameras = sensors.any {
            it.source == WatchSensor.Source.FRIGATE_ZONE && ZoneCameras.companionSensors(it, catalog).isNotEmpty()
        }
        return if (zoneWithCameras) PhotoPromise.FROM_CAMERA_SEEING_ZONE else PhotoPromise.NONE
    }

    /** The camera of [sensor]: its own, or for a Frigate zone the one the names or co-firing tie it to. */
    private fun cameraOf(sensor: WatchSensor): String? =
        sensor.cameraEntityId ?: if (sensor.source == WatchSensor.Source.FRIGATE_ZONE) {
            ZoneCameras.knownCamera(sensor, catalog, zoneCameraMemory)
        } else {
            null
        }

    private fun describeActive(): String {
        val active = book.active(now())
        if (active.isEmpty()) return "No hay ninguna vigilancia activa."
        val format = DateFormat.getTimeInstance(DateFormat.SHORT)
        return "Vigilancias activas: " + active.joinToString("; ") { w ->
            "${w.areaNames.joinToString()} (${objectLabel(w.objectGroup)}${if (w.once) ", un solo aviso" else ""}) hasta las ${format.format(Date(w.expiresAtMs))}"
        } + "." + (liveProblem?.let { " Problema actual con la conexión en tiempo real: $it." } ?: "")
    }

    /** Points the event stream at the currently watched sensors and updates the UI list. */
    @Synchronized
    private fun syncStream(rebuild: Boolean) {
        if (released) return
        val active = book.active(now())
        _activeAreas.value = active.flatMap { it.areaNames }.distinct()
        _activeWatches.value = active
        sentinelSensors = if (sentinelEnabled()) {
            catalog.filter {
                it.source == WatchSensor.Source.FRIGATE_CAMERA && it.objectType in SENTINEL_OBJECTS
            }.map { it.entityId }.toSet()
        } else {
            emptySet()
        }
        val watchedIds = active.flatMap { it.sensorIds }.toSet()
        // A watched zone with no camera from its names: also listen to the cameras' sensors of
        // the same object, to learn which camera sees it (the one that fires with it).
        val learningSensors = catalog.filter {
            it.entityId in watchedIds && it.source == WatchSensor.Source.FRIGATE_ZONE && ZoneCameras.linkedCamera(it, catalog) == null
        }.flatMap { ZoneCameras.companionSensors(it, catalog) }.map { it.entityId }.toSet()
        val ids = watchedIds + sentinelSensors + learningSensors
        val client = haClientProvider()
        if (rebuild || client !== streamClient) {
            stream?.release()
            stream = null
            streamClient = client
            liveProblem = null
        }
        if (ids.isEmpty()) {
            stream?.setWatched(emptySet())
            liveProblem = null
            return
        }
        var created: HaEventStream? = null
        val current = stream ?: client?.openEventStream(
            onSensorOn = { entityId, replay -> scope.launch { onSensorOn(entityId, replay) } },
            onStatus = { message, ok ->
                TrekoLogBuffer.i(TAG, message)
                // Ignore late news from a stream that was already replaced.
                if (stream === created) liveProblem = if (ok) null else message
            }
        )?.also { created = it; stream = it }
        current?.setWatched(ids)
    }

    private suspend fun refreshCatalog(): Boolean {
        val fresh = haClientProvider()?.getWatchCatalog() ?: return false
        catalog = fresh
        return true
    }

    /**
     * A sensor turned on. [replay] = it was already on when the live connection (re)started or a
     * watch was added: watches still alert (someone is there now), but the sentinel only
     * follows real off → on changes, so a restart or reconnect does not re-run vision.
     */
    private suspend fun onSensorOn(entityId: String, replay: Boolean) {
        val nowMs = now()
        // Only live changes teach a zone's camera: a replay brings everything that is on at once.
        if (!replay) learnZoneCamera(entityId, nowMs)
        val hit = book.onSensorActivated(entityId, nowMs)
        // The sentinel can take a while (vision model): never let it delay the alert.
        if (entityId in sentinelSensors) {
            if (replay) {
                TrekoLogBuffer.d(TAG, "Sentinel ignores $entityId: already on when the live connection started.")
            } else {
                scope.launch { maybeRunSentinel(entityId, watchAlerted = hit != null) }
            }
        }
        if (hit == null) return
        if (hit.consumedOneShot) syncStream(rebuild = false)
        deliver(hit)
    }

    private fun learnZoneCamera(entityId: String, nowMs: Long) {
        for ((zone, camera) in zoneCameraLearner.onSensorOn(entityId, nowMs, catalog)) {
            if (ZoneCameras.linkedCamera(zone, catalog) != null) continue
            if (zoneCameraMemory.learn(zone.areaKey, camera)) {
                TrekoLogBuffer.i(TAG, "Zone ${zone.areaName} is seen by $camera (they fired together).")
            }
        }
    }

    /** The user asked to watch the place of [sensor] (same sensor, camera or place name). */
    private fun isWatched(sensor: WatchSensor): Boolean {
        val active = book.active(now())
        if (active.isEmpty()) return false
        val byId = catalog.associateBy { it.entityId }
        val camera = cameraOf(sensor)
        return active.any { w ->
            sensor.entityId in w.sensorIds ||
                w.areaNames.any { it.equals(sensor.areaName, ignoreCase = true) } ||
                (camera != null && w.sensorIds.any { id -> byId[id]?.let { cameraOf(it) } == camera })
        }
    }

    /**
     * [watchAlerted]: a camera watch is alerting this very detection (with its own picture and
     * description), so the sentinel does not look again nor speak a second time.
     */
    private suspend fun maybeRunSentinel(entityId: String, watchAlerted: Boolean = false) {
        val sensor = catalog.firstOrNull { it.entityId == entityId }
        val trace = SentinelRunTrace(
            sensorId = entityId,
            cameraEntityId = sensor?.cameraEntityId,
            area = sensor?.areaName.orEmpty(),
            label = sensor?.objectType,
            snapshotEntityId = sensor?.snapshotEntityId,
            watchRequested = sensor != null && isWatched(sensor)
        )
        val camera = sensor?.cameraEntityId
        if (sensor == null || camera == null) {
            trace.decide("skipped", if (sensor == null) "sensor not in the catalog" else "no camera linked to the sensor")
            TrekoLogBuffer.i(TAG, trace.format())
            return
        }
        val nowMs = now()
        val restLeftMs = synchronized(sentinelRestUntilByCamera) {
            val until = sentinelRestUntilByCamera[camera]
            if (until != null && nowMs < until) {
                until - nowMs
            } else {
                // Held while this run is in progress; set by the outcome once it ends.
                sentinelRestUntilByCamera[camera] = nowMs + if (watchAlerted) SentinelCooldown.AFTER_ALERT_MS else SentinelCooldown.WHILE_RUNNING_MS
                null
            }
        }
        if (restLeftMs != null) {
            trace.decide("skipped", "camera cooldown, ${restLeftMs / 1000}s left")
            TrekoLogBuffer.i(TAG, trace.format())
            return
        }
        if (watchAlerted) {
            trace.decide(SentinelCooldown.DECISION_WATCH_ALERTED, "a camera watch already alerts this detection")
            TrekoLogBuffer.i(TAG, trace.format())
            return
        }
        // The detector's own picture, fetched while the vision model looks: if there is an
        // alert, its notification and the alerts log get the frame the detector saw.
        trace.detectionImage = sensor.snapshotEntityId?.let { imageEntity ->
            scope.async(Dispatchers.IO) { haClientProvider()?.fetchEntityImage(imageEntity) }
        }
        val ran = try {
            withContext(trace) { onSentinelDetection(sensor, trace) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            trace.decide("error", e.message ?: e.javaClass.simpleName)
            false
        }
        if (!ran) trace.decide("not_run", "sentinel check did not run")
        // A short rest after "nothing new", a long one after an alert; none if it did not run.
        val restMs = SentinelCooldown.afterOutcome(trace.decision, ran)
        synchronized(sentinelRestUntilByCamera) {
            if (restMs == null) sentinelRestUntilByCamera.remove(camera) else sentinelRestUntilByCamera[camera] = now() + restMs
        }
        // Only an alert uses the picture.
        if (trace.decision?.startsWith("alert") != true) trace.detectionImage?.cancel()
        TrekoLogBuffer.i(TAG, trace.format())
    }

    private suspend fun deliver(hit: WatchHit) {
        val sensor = catalog.firstOrNull { it.entityId == hit.sensorId }
        val area = sensor?.areaName ?: hit.watch.areaNames.firstOrNull().orEmpty()
        val text = alertText(sensor, hit.watch.objectGroup, area)
        TrekoLogBuffer.i(TAG, "Watch alert: $text (${hit.sensorId})")
        val notificationId = AlertNotifications.nextWatchNotificationId()
        val alertId = TrekoAlertLog.record(
            AlertEvent(
                timestampMs = now(),
                source = AlertSource.WATCH,
                title = text,
                place = area,
                cameraEntityId = sensor?.let { cameraOf(it) },
                objectLabel = when {
                    sensor?.source == WatchSensor.Source.GENERIC -> "motion"
                    else -> sensor?.objectType ?: hit.watch.objectGroup
                },
                spokenSummary = text,
                notificationId = notificationId,
                extra = mapOf("sensor" to hit.sensorId, "watch" to hit.watch.id.toString())
            )
        )

        // 1. Right away: notification and voice. Nothing slow before this.
        val notified = post(notificationId, text, null, null, alertId)
        addChat("👁 $text")
        val ringer = context.getSystemService(AudioManager::class.java)?.ringerMode
        val spoken = if (ringer == null || ringer == AudioManager.RINGER_MODE_NORMAL) speak(text) else false
        TrekoAlertLog.update(alertId) { it.copy(notified = notified, spoken = spoken) }

        // 2. Then the picture, and a short description when a vision model is available.
        val client = haClientProvider() ?: return
        val (imageEntity, cameraEntity) = pictureSources(client, sensor)
        if (cameraEntity != null && sensor?.let { cameraOf(it) } != cameraEntity) {
            TrekoAlertLog.update(alertId) { it.copy(cameraEntityId = cameraEntity) }
        }
        val image = imageEntity?.let { client.fetchEntityImage(it) }
        val bitmap = image?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bitmap != null) post(notificationId, text, null, bitmap, alertId)
        // Kept for the alerts screen; encoding must not delay the description.
        if (image != null) scope.launch { TrekoAlertLog.attachSnapshot(alertId, image) }
        val camera = cameraEntity ?: return
        // Without LLM Vision there is no description to get (an empty set: detection has not
        // run yet, so it is tried as before).
        val skills = TrekoPreferences(context).detectedSkills
        if (skills.isNotEmpty() && TrekoSkills.LLM_VISION !in skills) return
        // One vision call gives the notification's text and the description (same JSON verdict
        // as the sentinel), looking at the detector's own picture first, then the live camera.
        val prompt = VisionPrompts(context).watch(
            cameraName = area,
            label = if (sensor?.source == WatchSensor.Source.GENERIC) "motion" else sensor?.objectType,
            objectGroup = hit.watch.objectGroup
        )
        var answer: String? = null
        for (image in listOfNotNull(imageEntity?.takeIf { it.startsWith("image.") }, camera).distinct()) {
            answer = runCatching { client.analyzeCameraWithLlmVision(image, prompt) }.getOrNull()?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith("Error", ignoreCase = true) }
            if (answer != null) break
        }
        val raw = answer ?: return
        val verdict = parseVisionVerdict(raw)
        // A parsed verdict is never shown as raw JSON; the raw answer only when it was plain text.
        val texts = cameraAlertTexts(context)
        val description = verdict?.fallbackLine(texts) ?: raw
        // Never a reassuring sentence for an attention/urgent verdict (see VisionVerdict.spokenLine).
        val shortText = verdict?.takeIf { it.spokenAlert.isNotBlank() }?.spokenLine(area, texts) ?: description
        post(notificationId, text, shortText, bitmap, alertId)
        addChat("👁 $area: $shortText")
        TrekoAlertLog.update(alertId) { e ->
            e.copy(
                visionDescription = description,
                extra = verdict?.let { e.extra + (AlertRecording.EXTRA_VISION_LEVEL to it.level.key) } ?: e.extra
            )
        }
    }

    /**
     * (picture to show, camera): Frigate's detection picture when there is one, otherwise a live
     * camera snapshot. A zone has no camera of its own: the one its names or co-firing tie it
     * to, else the camera whose sensor for the same object is on right now.
     */
    private suspend fun pictureSources(client: HomeAssistantClient, sensor: WatchSensor?): Pair<String?, String?> {
        if (sensor == null) return null to null
        if (sensor.source != WatchSensor.Source.FRIGATE_ZONE) {
            return (sensor.snapshotEntityId ?: sensor.cameraEntityId) to sensor.cameraEntityId
        }
        // The camera's own detection picture for this object when it has one, else a live snapshot.
        fun fromCamera(camera: String): Pair<String?, String?> =
            (ZoneCameras.sensorOfCamera(camera, sensor, catalog)?.snapshotEntityId ?: camera) to camera
        ZoneCameras.knownCamera(sensor, catalog, zoneCameraMemory)?.let { return fromCamera(it) }
        val companions = ZoneCameras.companionSensors(sensor, catalog)
        if (companions.isEmpty()) return null to null
        // The camera may report the same sighting a moment after the zone: look twice.
        repeat(2) { attempt ->
            if (attempt > 0) {
                delay(ZoneCameras.CO_FIRE_WINDOW_MS)
                // Learned meanwhile from the camera firing with the zone.
                ZoneCameras.knownCamera(sensor, catalog, zoneCameraMemory)?.let { return fromCamera(it) }
            }
            val states = client.getStates(companions.map { it.entityId }.toSet())
            ZoneCameras.cameraSeeingNow(sensor, catalog, states)?.cameraEntityId?.let { return fromCamera(it) }
        }
        return null to null
    }

    private fun alertText(sensor: WatchSensor?, objectGroup: String, area: String): String {
        val res = when {
            sensor?.source == WatchSensor.Source.GENERIC -> R.string.watch_alert_motion
            else -> when (WatchCatalog.groupOfLabel(sensor?.objectType) ?: objectGroup) {
                "person" -> R.string.watch_alert_person
                "car" -> R.string.watch_alert_vehicle
                "animal" -> R.string.watch_alert_animal
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

    private fun post(id: Int, title: String, body: String?, picture: android.graphics.Bitmap?, alertId: Long): Boolean =
        AlertNotifications.postCameraAlert(context, id, title, body, picture, alertId)

    private fun readList(args: JSONObject, key: String): List<String> {
        val raw = args.opt(key)
        return when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.split(',', ';').map { it.trim() }
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun now() = System.currentTimeMillis()

    /** In `treko_watches` (excluded from backups): what is watched and how the home's cameras relate. */
    private class SharedPrefsWatchPersistence(context: Context, private val key: String = "state") : WatchPersistence {
        private val prefs = context.applicationContext.getSharedPreferences("treko_watches", Context.MODE_PRIVATE)
        override fun save(encoded: String) {
            prefs.edit().putString(key, encoded).apply()
        }
        override fun load(): String? = prefs.getString(key, null)
    }

    companion object {
        private const val TAG = "CameraWatch"
        const val CHANNEL_ID = AlertNotifications.WATCH_CHANNEL_ID
        private val SENTINEL_OBJECTS = setOf("person", "car")
    }
}
