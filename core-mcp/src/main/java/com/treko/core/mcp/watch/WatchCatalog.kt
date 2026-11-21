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

    private val VEHICLES = listOf("car", "truck", "bus", "motorcycle", "bicycle")
    private val ANIMALS = listOf(
        "dog", "cat", "bird", "horse", "bear", "deer", "squirrel", "fox", "rabbit", "raccoon",
        "goat", "cow", "skunk", "kangaroo"
    )
    private val DELIVERIES = listOf(
        "package", "amazon", "ups", "fedex", "dhl", "usps", "an_post", "purolator", "postnl",
        "nzpost", "postnord", "gls", "dpd"
    )

    /**
     * Labels Frigate (and Frigate+) can track, longest first so `license_plate` wins over a
     * shorter suffix. "all" is Frigate's any-object sensor. Labels not listed here are still
     * read from sensors Home Assistant reports as Frigate's (see [parseFrigateOccupancy]).
     */
    val FRIGATE_OBJECTS: List<String> = (
        listOf("person", "face", "license_plate", "waste_bin", "robot_lawnmower", "boat", "all") +
            VEHICLES + ANIMALS + DELIVERIES
        ).distinct().sortedByDescending { it.length }

    /** What the user may ask to watch for, mapped to Frigate labels. */
    val OBJECT_GROUPS: Map<String, List<String>> = mapOf(
        "person" to listOf("person"),
        "car" to VEHICLES,
        "animal" to ANIMALS,
        "package" to DELIVERIES,
        "any" to listOf("all")
    )

    /** Motion/occupancy only: a "presence" sensor says whether a person is home, not a place. */
    private val GENERIC_DEVICE_CLASSES = setOf("occupancy", "motion")
    private val GENERIC_NAME_NOISE = listOf(
        "motion", "movimiento", "occupancy", "ocupacion", "sensor", "detector",
        "de", "del"
    )
    private val ALL_PLACES = setOf("todo", "todas", "todos", "toda", "casa", "everywhere", "all", "cualquier", "cualquiera")

    /**
     * [frigateDevices] maps each Frigate entity to its Home Assistant device id (null when the
     * template API is unavailable). Grouping by device is what makes this portable: entity ids
     * follow whatever the user named each device, but a camera and its sensors always share a
     * device, and each Frigate zone is a device without a camera.
     */
    fun build(states: List<HaStateLite>, frigateDevices: Map<String, String>?): List<WatchSensor> {
        val byId = states.associateBy { it.entityId }
        val entitiesByDevice: Map<String, List<String>> = frigateDevices.orEmpty().entries
            .groupBy({ it.value }, { it.key })
        val result = ArrayList<WatchSensor>()
        for (s in states) {
            if (!s.entityId.startsWith("binary_sensor.")) continue
            val knownFrigate = frigateDevices?.containsKey(s.entityId) == true
            val frigate = parseFrigateOccupancy(s.entityId, allowUnknownLabel = knownFrigate)
            val isFrigate = frigate != null && (frigateDevices == null || knownFrigate)
            if (isFrigate && frigate != null) {
                val (areaSlug, obj) = frigate
                val deviceEntities = frigateDevices?.get(s.entityId)?.let { entitiesByDevice[it] }
                val camera: String?
                val snapshot: String?
                if (deviceEntities != null) {
                    camera = deviceEntities.filter { it.startsWith("camera.") && !endsWithObject(it) }.minOrNull()
                    snapshot = deviceEntities.firstOrNull {
                        (it.startsWith("image.") || it.startsWith("camera.")) && it.endsWith("_$obj")
                    }
                } else {
                    // No device info: the camera whose id is the longest prefix of the sensor's.
                    camera = states.map { it.entityId }
                        .filter { it.startsWith("camera.") && !endsWithObject(it) }
                        .filter { cam -> val slug = cam.substringAfter('.'); areaSlug == slug || areaSlug.startsWith("${slug}_") }
                        .maxByOrNull { it.length }
                    snapshot = listOf("image.${areaSlug}_$obj", "camera.${areaSlug}_$obj").firstOrNull { it in byId }
                }
                val name = camera?.let { byId[it]?.friendlyName }?.takeIf { it.isNotBlank() }
                    ?: placeFromSensorName(s.friendlyName, obj)
                    ?: prettify(areaSlug)
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
                frigateDevices?.containsKey(s.entityId) != true
            ) {
                val name = genericAreaName(s)
                if (name.isBlank()) continue
                val key = normalize(name)
                // Only a camera named like this place; a shared word ("exterior") is not enough.
                val camera = states.firstOrNull {
                    if (!it.entityId.startsWith("camera.")) return@firstOrNull false
                    val camKey = stripArticles(it.friendlyName.ifBlank { it.entityId.substringAfter('.') })
                    val placeKey = stripArticles(name)
                    camKey.isNotBlank() && placeKey.isNotBlank() && (camKey == placeKey || camKey.contains(placeKey) || placeKey.contains(camKey))
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

    /** `camera.patio_person` is Frigate's per-object snapshot camera, not the camera itself. */
    private fun endsWithObject(entityId: String): Boolean =
        FRIGATE_OBJECTS.any { entityId.endsWith("_$it") }

    /** "Frente Cercano Person occupancy" → "Frente Cercano". */
    private fun placeFromSensorName(friendlyName: String, obj: String): String? {
        val objWords = Regex.escape(obj.replace('_', ' '))
        return friendlyName.replace(Regex("(?i)\\s+$objWords\\s+occupancy\\s*$"), "").trim()
            .takeIf { it.isNotBlank() && !it.equals(friendlyName.trim(), ignoreCase = false) }
    }

    /**
     * `jardin_person_occupancy` → ("jardin", "person"); null if it is not that shape.
     * [allowUnknownLabel]: Home Assistant says the entity is Frigate's, so a label missing
     * from [FRIGATE_OBJECTS] is taken as the last word.
     */
    fun parseFrigateOccupancy(entityId: String, allowUnknownLabel: Boolean = false): Pair<String, String>? {
        val body = entityId.substringAfter('.')
        if (!body.endsWith("_occupancy")) return null
        val head = body.removeSuffix("_occupancy")
        val obj = FRIGATE_OBJECTS.firstOrNull { head.endsWith("_$it") }
            ?: head.substringAfterLast('_', "").takeIf { allowUnknownLabel && it.isNotBlank() && it != head }
            ?: return null
        val area = head.removeSuffix("_$obj")
        return if (area.isBlank()) null else area to obj
    }

    /** Request group ("person", "car", "animal", "package") a Frigate label belongs to. */
    fun groupOfLabel(label: String?): String? =
        label?.let { l -> OBJECT_GROUPS.entries.firstOrNull { it.key != "any" && l in it.value }?.key }

    /** "toda la casa", "todo", "everywhere"… */
    fun isEverywhere(query: String): Boolean {
        val words = stripArticles(query).split(' ').filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in ALL_PLACES }
    }

    /**
     * Places among [candidates] the user meant by [query]: exact names first (ignoring
     * articles and accents); otherwise names that contain it or share a 4+ letter word.
     */
    fun matchPlaces(query: String, candidates: Collection<String>): List<String> {
        val key = stripArticles(query)
        if (key.isBlank()) return emptyList()
        val exact = candidates.filter { stripArticles(it) == key }
        return exact.ifEmpty { candidates.filter { sharesPlace(key, stripArticles(it)) } }
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
        val everywhere = cleanQueries.isEmpty() || cleanQueries.any { isEverywhere(it) }
        val nameByKey = areas.mapValues { it.value.first().areaName }
        val targetAreas: List<List<String>> = if (everywhere) {
            // "Everywhere" = every camera (not the zones inside them, nor indoor motion
            // sensors). A home without Frigate cameras only has its motion sensors.
            val cameras = areas.filterValues { list ->
                list.any {
                    it.source == WatchSensor.Source.FRIGATE_CAMERA ||
                        (it.source == WatchSensor.Source.GENERIC && it.cameraEntityId != null)
                }
            }.keys
            listOf(cameras.ifEmpty { areas.filterValues { list -> list.any { it.source == WatchSensor.Source.GENERIC } }.keys }.toList())
        } else {
            cleanQueries.map { q ->
                val names = matchPlaces(q, nameByKey.values)
                val matches = nameByKey.filterValues { it in names }.keys.toList()
                if (matches.isEmpty()) unknown += q
                matches
            }
        }

        for (areaKeys in targetAreas) {
            for (areaKey in areaKeys) {
                val sensors = areas[areaKey].orEmpty()
                val frigateSensors = sensors.filter { it.objectType != null }
                val frigate = if (objectGroup == "any") {
                    // Frigate's "all" sensor covers every label; without it, every label sensor.
                    frigateSensors.filter { it.objectType == "all" }.ifEmpty { frigateSensors }
                } else {
                    frigateSensors.filter { it.objectType in wanted }
                }
                val pick = frigate.ifEmpty { sensors.filter { it.source == WatchSensor.Source.GENERIC } }
                pick.forEach { chosen[it.entityId] = it }
            }
        }
        return WatchResolution(chosen.values.toList(), unknown, available)
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('_', ' ')
            // Letters and digits of any script ("Сад", "庭"), not only a-z.
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun stripArticles(q: String): String =
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
