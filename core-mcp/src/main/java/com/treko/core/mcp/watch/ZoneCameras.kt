package com.treko.core.mcp.watch

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Which camera shows a Frigate zone. A zone is always drawn on a camera, but Home Assistant
 * gives the zone its own device with no camera, so the link is found here, in this order:
 * 1. [linkedCamera]: what the names say (the only Frigate camera, or a zone named after its
 *    camera, `binary_sensor.<camera>_<zone>_person_occupancy`);
 * 2. [ZoneCameraMemory]: learned by co-firing ([ZoneCameraLearner]);
 * 3. [cameraSeeingNow]: at alert time, the camera whose own sensor for the same object is on.
 * Pure logic; nothing here knows any particular home.
 */
object ZoneCameras {
    /** A zone and a camera sensor turning on this close together are the same sighting. */
    const val CO_FIRE_WINDOW_MS = 5_000L

    /** The Frigate cameras of the catalog (their main camera entity), sorted. */
    fun frigateCameras(catalog: List<WatchSensor>): List<String> =
        catalog.filter { it.source == WatchSensor.Source.FRIGATE_CAMERA }
            .mapNotNull { it.cameraEntityId }.distinct().sorted()

    /**
     * The camera the names tie [zone] to, or null when they do not say: the only Frigate
     * camera of the home, else the camera whose id is the start or end of the zone's
     * (`frente_cercano` → `camera.frente`); the longest wins, a tie means unknown.
     */
    fun linkedCamera(zone: WatchSensor, catalog: List<WatchSensor>): String? {
        if (zone.source != WatchSensor.Source.FRIGATE_ZONE) return zone.cameraEntityId
        zone.cameraEntityId?.let { return it }
        val cameras = frigateCameras(catalog)
        if (cameras.size == 1) return cameras.single()
        val zoneSlug = WatchCatalog.parseFrigateOccupancy(zone.entityId, allowUnknownLabel = zone.objectType != null)?.first
            ?: return null
        val named = cameras.filter { cam ->
            val slug = cam.substringAfter('.')
            slug.isNotBlank() && slug != zoneSlug && (zoneSlug.startsWith("${slug}_") || zoneSlug.endsWith("_$slug"))
        }
        val longest = named.maxOfOrNull { it.length } ?: return null
        return named.filter { it.length == longest }.singleOrNull()
    }

    /**
     * The camera sensors whose firing can reveal [zone]'s camera: each Frigate camera's
     * sensor for the zone's object (its "all" sensor for an "all" zone).
     */
    fun companionSensors(zone: WatchSensor, catalog: List<WatchSensor>): List<WatchSensor> {
        if (zone.source != WatchSensor.Source.FRIGATE_ZONE || zone.objectType == null) return emptyList()
        return catalog.filter {
            it.source == WatchSensor.Source.FRIGATE_CAMERA && it.cameraEntityId != null && it.objectType == zone.objectType
        }
    }

    /** The camera sensor of [zone]'s object that is on right now ([states]: entity → state); the first one when several are. */
    fun cameraSeeingNow(zone: WatchSensor, catalog: List<WatchSensor>, states: Map<String, String>): WatchSensor? =
        companionSensors(zone, catalog).firstOrNull { states[it.entityId] == "on" }

    /** The camera's own sensor for the zone's object, for its detection picture; null if it has none. */
    fun sensorOfCamera(camera: String, zone: WatchSensor, catalog: List<WatchSensor>): WatchSensor? =
        companionSensors(zone, catalog).firstOrNull { it.cameraEntityId == camera }

    /** Steps 1 and 2: the names, else what was learned. Null = only [cameraSeeingNow] can tell. */
    fun knownCamera(zone: WatchSensor, catalog: List<WatchSensor>, memory: ZoneCameraMemory): String? {
        linkedCamera(zone, catalog)?.let { return it }
        val cameras = frigateCameras(catalog)
        // A learned camera that is no longer in Home Assistant is ignored.
        return memory.cameraFor(zone.areaKey)?.takeIf { it in cameras }
    }
}

/**
 * Learns which camera sees each Frigate zone: when a zone sensor and exactly one camera's
 * sensor for the same object turn on within [windowMs] of each other (in either order), that
 * camera sees the zone. Only live changes are fed (never the replay after a reconnect, when
 * everything that is on arrives at once).
 */
class ZoneCameraLearner(private val windowMs: Long = ZoneCameras.CO_FIRE_WINDOW_MS) {
    private val lastOn = HashMap<String, Long>()

    /** A sensor turned on at [atMs]: returns (zone sensor, camera) pairs this event teaches. */
    @Synchronized
    fun onSensorOn(entityId: String, atMs: Long, catalog: List<WatchSensor>): List<Pair<WatchSensor, String>> {
        lastOn.entries.removeAll { atMs - it.value > windowMs }
        lastOn[entityId] = atMs
        val sensor = catalog.firstOrNull { it.entityId == entityId } ?: return emptyList()
        val zones = when (sensor.source) {
            WatchSensor.Source.FRIGATE_ZONE -> listOf(sensor)
            WatchSensor.Source.FRIGATE_CAMERA -> catalog.filter {
                it.source == WatchSensor.Source.FRIGATE_ZONE && it.objectType == sensor.objectType && it.entityId in lastOn
            }
            WatchSensor.Source.GENERIC -> emptyList()
        }
        return zones.mapNotNull { zone ->
            val zoneAt = lastOn[zone.entityId] ?: return@mapNotNull null
            val cameras = ZoneCameras.companionSensors(zone, catalog)
                .filter { cam -> lastOn[cam.entityId]?.let { kotlin.math.abs(it - zoneAt) <= windowMs } == true }
                .mapNotNull { it.cameraEntityId }
                .distinct()
            cameras.singleOrNull()?.let { zone to it }
        }
    }
}

/**
 * Learned zone → camera links, counted per sighting so one odd co-firing does not override a
 * camera seen many times. Keyed by the zone's [WatchSensor.areaKey] (shared by its person,
 * car… sensors). Saved through [persistence]; it describes the home, so it is not backed up.
 */
class ZoneCameraMemory(private val persistence: WatchPersistence) {
    private val counts = HashMap<String, MutableMap<String, Int>>()

    init {
        persistence.load()?.let { decode(it) }?.let { counts.putAll(it) }
    }

    /** The camera seen most often with [zoneKey], ties broken by name; null when none was learned. */
    @Synchronized
    fun cameraFor(zoneKey: String): String? =
        counts[zoneKey]?.entries?.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.firstOrNull()?.key

    /** One more sighting of [camera] with [zoneKey]. True when that changed [cameraFor]. */
    @Synchronized
    fun learn(zoneKey: String, camera: String): Boolean {
        if (zoneKey.isBlank() || camera.isBlank()) return false
        val before = cameraFor(zoneKey)
        val perCamera = counts.getOrPut(zoneKey) { HashMap() }
        perCamera[camera] = ((perCamera[camera] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        persistence.save(encode(counts))
        return cameraFor(zoneKey) != before
    }

    companion object {
        private const val VERSION = "z1"
        private const val MAX_COUNT = 1000

        internal fun encode(map: Map<String, Map<String, Int>>): String = buildString {
            append(VERSION).append('\n')
            for ((zone, cams) in map.toSortedMap()) {
                for ((cam, n) in cams.toSortedMap()) {
                    append(enc(zone)).append('|').append(enc(cam)).append('|').append(n).append('\n')
                }
            }
        }

        internal fun decode(text: String): Map<String, MutableMap<String, Int>>? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != VERSION) return null
            val out = HashMap<String, MutableMap<String, Int>>()
            for (line in lines.drop(1)) {
                val p = line.split('|')
                if (p.size != 3) continue
                val n = p[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                out.getOrPut(dec(p[0])) { HashMap() }[dec(p[1])] = n
            }
            return out
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
