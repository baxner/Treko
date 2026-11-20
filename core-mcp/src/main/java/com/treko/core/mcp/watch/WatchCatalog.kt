package com.treko.core.mcp.watch

import java.text.Normalizer
import java.util.Locale

/** Minimal view of a Home Assistant state, enough to build the watch catalog. */
data class HaStateLite(
    val entityId: String,
    val state: String,
    val friendlyName: String = "",
    val deviceClass: String = ""
)

/** A sensor Treko can watch, with the place it covers. */
data class WatchSensor(
    val entityId: String,
    /** Display name of the place ("Jardín", "Frente casa"). */
    val areaName: String,
    /** Normalized key used for matching and grouping. */
    val areaKey: String,
    /** Frigate object ("person", "car"…); null for a generic motion/occupancy sensor. */
    val objectType: String?,
    val source: Source,
    /** Camera that shows this place, when known. */
    val cameraEntityId: String?,
    /** Frigate's latest-detection picture for this place/object, when it exists. */
    val snapshotEntityId: String?
) {
    enum class Source { FRIGATE_CAMERA, FRIGATE_ZONE, GENERIC }
}

/** What a watch request resolved to. */
data class WatchResolution(
    val sensors: List<WatchSensor>,
    val unknownQueries: List<String>,
    val availableAreas: List<String>
)

/**
 * Builds what can be watched from whatever this Home Assistant has: Frigate occupancy
 * sensors (`binary_sensor.<camera or zone>_<object>_occupancy`) and, as a fallback, generic
 * motion / occupancy / presence sensors. Nothing here knows any particular home.
 */
object WatchCatalog {

    /** Objects Frigate can track; "all" is Frigate's any-object sensor. */
    val FRIGATE_OBJECTS = listOf(
        "license_plate", "motorcycle", "bicycle", "package", "person", "horse", "bear", "deer",
        "bird", "face", "car", "dog", "cat", "all"
    )

    /** What the user may ask to watch for, mapped to Frigate objects. */
    val OBJECT_GROUPS: Map<String, List<String>> = mapOf(
        "person" to listOf("person"),
        "car" to listOf("car", "motorcycle", "bicycle"),
        "animal" to listOf("dog", "cat", "bird", "horse", "bear", "deer"),
        "package" to listOf("package"),
        "any" to listOf("all", "person", "car", "dog", "cat")
    )

    private val GENERIC_DEVICE_CLASSES = setOf("occupancy", "motion", "presence")
    private val GENERIC_NAME_NOISE = listOf(
        "motion", "movimiento", "occupancy", "ocupacion", "presence", "presencia", "sensor", "detector",
        "de", "del"
    )
    private val ALL_PLACES = setOf("todo", "todas", "todos", "toda", "casa", "everywhere", "all", "cualquier", "cualquiera")

    fun build(states: List<HaStateLite>, frigateEntityIds: Set<String>?): List<WatchSensor> {
        val byId = states.associateBy { it.entityId }
        val result = ArrayList<WatchSensor>()
        for (s in states) {
            if (!s.entityId.startsWith("binary_sensor.")) continue
            val frigate = parseFrigateOccupancy(s.entityId)
            val isFrigate = frigate != null &&
                (frigateEntityIds?.contains(s.entityId) ?: true)
            if (isFrigate && frigate != null) {
                val (areaSlug, obj) = frigate
                val camera = "camera.$areaSlug".takeIf { it in byId }
                val snapshot = listOf("image.${areaSlug}_$obj", "camera.${areaSlug}_$obj").firstOrNull { it in byId }
                val name = camera?.let { byId[it]?.friendlyName }?.takeIf { it.isNotBlank() } ?: prettify(areaSlug)
                result += WatchSensor(
                    entityId = s.entityId,
                    areaName = name,
                    areaKey = normalize(name),
                    objectType = obj,
                    source = if (camera != null) WatchSensor.Source.FRIGATE_CAMERA else WatchSensor.Source.FRIGATE_ZONE,
                    cameraEntityId = camera,
                    snapshotEntityId = snapshot
                )
            } else if (s.deviceClass.lowercase(Locale.ROOT) in GENERIC_DEVICE_CLASSES &&
                frigateEntityIds?.contains(s.entityId) != true
            ) {
                val name = genericAreaName(s)
                if (name.isBlank()) continue
                val key = normalize(name)
                val camera = states.firstOrNull {
                    it.entityId.startsWith("camera.") && sharesPlace(key, normalize(it.friendlyName.ifBlank { it.entityId.substringAfter('.') }))
                }?.entityId
                result += WatchSensor(
                    entityId = s.entityId,
                    areaName = name,
                    areaKey = key,
                    objectType = null,
                    source = WatchSensor.Source.GENERIC,
                    cameraEntityId = camera,
                    snapshotEntityId = null
                )
            }
        }
        return result
    }

    /** `jardin_person_occupancy` → ("jardin", "person"); null if it is not that shape. */
    fun parseFrigateOccupancy(entityId: String): Pair<String, String>? {
        val body = entityId.substringAfter('.')
        if (!body.endsWith("_occupancy")) return null
        val head = body.removeSuffix("_occupancy")
        val obj = FRIGATE_OBJECTS.firstOrNull { head.endsWith("_$it") } ?: return null
        val area = head.removeSuffix("_$obj")
        return if (area.isBlank()) null else area to obj
    }

    /**
     * Matches the places the user named against the catalog. A place with Frigate sensors
     * for the requested objects uses those; otherwise its generic sensors, if any.
     */
    fun resolve(catalog: List<WatchSensor>, queries: List<String>, objectGroup: String): WatchResolution {
        val wanted = OBJECT_GROUPS[objectGroup] ?: OBJECT_GROUPS.getValue("person")
        val areas = catalog.groupBy { it.areaKey }
        val available = areas.values.map { it.first().areaName }.distinct().sorted()
        val chosen = LinkedHashMap<String, WatchSensor>()
        val unknown = ArrayList<String>()

        val cleanQueries = queries.map { it.trim() }.filter { it.isNotEmpty() }
        val everywhere = cleanQueries.isEmpty() || cleanQueries.any { q ->
            val words = stripArticles(q).split(' ').filter { it.isNotEmpty() }
            words.isNotEmpty() && words.all { it in ALL_PLACES }
        }
        val targetAreas: List<List<String>> = if (everywhere) {
            // "Everywhere" = every camera's own sensors, not every zone inside them.
            listOf(areas.filterValues { list -> list.any { it.source != WatchSensor.Source.FRIGATE_ZONE } }.keys.toList())
        } else {
            cleanQueries.map { q ->
                val key = normalize(stripArticles(q))
                val exact = areas.keys.filter { it == key }
                val matches = exact.ifEmpty { areas.keys.filter { sharesPlace(key, it) } }
                if (matches.isEmpty()) unknown += q
                matches
            }
        }

        for (areaKeys in targetAreas) {
            for (areaKey in areaKeys) {
                val sensors = areas[areaKey].orEmpty()
                val frigate = sensors.filter { it.objectType != null && it.objectType in wanted }
                val pick = when {
                    frigate.isNotEmpty() -> {
                        // Prefer the precise object sensor over Frigate's "all".
                        val specific = frigate.filter { it.objectType != "all" }
                        specific.ifEmpty { frigate }
                    }
                    else -> sensors.filter { it.source == WatchSensor.Source.GENERIC }
                }
                pick.forEach { chosen[it.entityId] = it }
            }
        }
        return WatchResolution(chosen.values.toList(), unknown, available)
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('_', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripArticles(q: String): String =
        normalize(q).split(' ').filterNot { it in setOf("el", "la", "los", "las", "en", "del", "de", "the", "camara", "zona") }
            .joinToString(" ")

    /** Same place if one name contains the other, or they share a word of 4+ letters. */
    internal fun sharesPlace(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a.contains(b) || b.contains(a)) return true
        val wordsA = a.split(' ').filter { it.length >= 4 }.toSet()
        return b.split(' ').any { it.length >= 4 && it in wordsA }
    }

    private fun prettify(slug: String): String =
        slug.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun genericAreaName(s: HaStateLite): String {
        val base = s.friendlyName.ifBlank { s.entityId.substringAfter('.').replace('_', ' ') }
        val kept = base.split(Regex("\\s+")).filterNot { normalize(it) in GENERIC_NAME_NOISE }
        return kept.joinToString(" ").trim()
    }
}
