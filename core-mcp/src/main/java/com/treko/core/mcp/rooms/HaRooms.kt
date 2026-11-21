package com.treko.core.mcp.rooms

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A media player of an area: its entity, state, whether Alexa Media Player owns it and its
 * device class ("tv", "speaker"…; empty when it reports none).
 */
data class HaAreaPlayer(
    val entityId: String,
    val state: String,
    val isAlexa: Boolean,
    val deviceClass: String = ""
) {
    val isAvailable: Boolean get() = state !in UNAVAILABLE_STATES
    val isTv: Boolean get() = deviceClass.equals("tv", ignoreCase = true)
}

/** A Home Assistant area with the media players and lights assigned to it (directly or by device). */
data class HaArea(
    val id: String,
    val name: String,
    val mediaPlayers: List<HaAreaPlayer>,
    val lights: List<String>
) {
    val hasPlayer: Boolean get() = mediaPlayers.isNotEmpty()
    val hasLights: Boolean get() = lights.isNotEmpty()

    /**
     * The player to use in this area; null if none. Never a TV (it would turn on its screen).
     * Alexa first when [preferAlexa], then players that are on (idle, paused, playing…)
     * before ones that are off (an off player does not even report its volume).
     */
    fun preferredPlayer(preferAlexa: Boolean): HaAreaPlayer? =
        mediaPlayers.filter { it.isAvailable && !it.isTv }
            .sortedWith(compareBy({ if (preferAlexa && it.isAlexa) 0 else 1 }, { if (it.state == "off") 1 else 0 }))
            .firstOrNull()
}

/** One Home Assistant service call, as plain data (turned into JSON by the client). */
data class HaServiceCall(
    val domain: String,
    val service: String,
    val data: Map<String, Any>
)

/**
 * What a light looked like before Treko touched it. [colorMode] says which colour field is
 * the real one (HA reports converted values for the others). [members] is non-empty for a
 * light group.
 */
data class LightSnapshot(
    val entityId: String,
    val isOn: Boolean,
    val brightness: Int? = null,
    val colorMode: String? = null,
    val colorTempKelvin: Int? = null,
    val hsColor: List<Double>? = null,
    val rgbColor: List<Int>? = null,
    val xyColor: List<Double>? = null,
    val rgbwColor: List<Int>? = null,
    val rgbwwColor: List<Int>? = null,
    val effect: String? = null,
    val supportedColorModes: List<String> = emptyList(),
    val members: List<String> = emptyList()
) {
    /** True when the light can be dimmed (anything beyond plain on/off). */
    val supportsBrightness: Boolean
        get() = supportedColorModes.isEmpty() && brightness != null ||
            supportedColorModes.any { it != "onoff" }

    /** True when the light can show a colour temperature (directly or converted to colour by HA). */
    val supportsColorTemp: Boolean
        get() = supportedColorModes.any { it in COLOR_TEMP_CAPABLE_MODES }
}

/** The answer the user gave to "¿En qué espacio estás?". */
sealed class PlaceMatch {
    data class Found(val area: HaArea) : PlaceMatch()
    /** "aquí", "solo el teléfono"…: no room devices. */
    data object PhoneOnly : PlaceMatch()
    data class Ambiguous(val candidates: List<HaArea>) : PlaceMatch()
    data object None : PlaceMatch()
}

/** Where a room session plays and which lights it may adjust. */
sealed class PlaceDecision {
    /** Use [area]. [announce]: it was picked without asking (the only one), so say which. */
    data class UseArea(val area: HaArea, val announce: Boolean) : PlaceDecision()

    data class UsePhone(val reason: PhoneReason) : PlaceDecision()

    /**
     * Ask the user before starting. [options]: candidate area names; [suggestion]: the last
     * used place when it is still a candidate; [unmatched]: what the user said that did not
     * match, or null when nothing was said.
     */
    data class Ask(
        val options: List<String>,
        val suggestion: String?,
        val unmatched: String?
    ) : PlaceDecision()
}

enum class PhoneReason { NO_HOME_ASSISTANT, NO_ROOMS, USER_CHOSE_PHONE }

/** Volume and state of a media player before Treko changed it. */
data class PlayerSnapshot(val entityId: String, val state: String, val volume: Double?)

/**
 * What must be put back if a room session ends or the app dies mid-session. [sceneEntityId]
 * is the Home Assistant scene snapshot (null if it could not be created); [lights] the local
 * snapshot of every light that was changed (lights that were off are never in it).
 * [volume] is the player's volume before the session (null: unknown, so never changed).
 * [dimBrightnessPercent], [dimKelvin] and [playerLevel] are what Treko set, so a late restore
 * can tell what the user changed since and leave that alone.
 * [lightsOffAtEnd]: the end policy of the session (a sleep session that runs to its end, or
 * dies on the way): the dimmed lights are turned off instead of put back. Volume and player
 * are restored either way.
 */
data class RoomRestoreRecord(
    val sceneEntityId: String?,
    val lights: List<LightSnapshot>,
    val mediaPlayer: String? = null,
    val volume: Double? = null,
    val startedAtMs: Long = 0L,
    val dimBrightnessPercent: Int? = null,
    val dimKelvin: Int? = null,
    val playerLevel: Double? = null,
    val lightsOffAtEnd: Boolean = false
) {
    val isEmpty: Boolean get() = sceneEntityId == null && lights.isEmpty() && mediaPlayer == null
}

/**
 * Pure logic for rooms: parsing Home Assistant template output, matching what the user says
 * to an area, deciding whether to ask, and planning light changes. Knows no particular home.
 */
object HaRooms {
    const val DEFAULT_WARM_KELVIN = 2400
    const val DEFAULT_DIM_PERCENT = 22
    const val DEFAULT_TRANSITION_S = 4

    /** Seconds a sleep session's lights take to fade out at its end. */
    const val SLEEP_OFF_TRANSITION_S = 30
    const val RESTORE_SCENE_ID = "treko_room_restore"
    const val RESTORE_SCENE_ENTITY = "scene.$RESTORE_SCENE_ID"

    private val ENTITY_ID = Regex("[a-z_]+\\.[a-z0-9_]+")

    fun isValidEntityId(entityId: String): Boolean = ENTITY_ID.matches(entityId)

    /**
     * Template listing every area as `id|name|players|lights`, one per line. Players are
     * `entity=state=alexa(1/0)=device_class`, comma separated. Uses only template functions every HA has;
     * `integration_entities` of a missing integration is just empty.
     */
    val AREAS_TEMPLATE: String = """
        {%- set alexa = integration_entities('alexa_media') -%}
        {%- for a in areas() -%}
        {%- set ents = area_entities(a) -%}
        {{ a }}|{{ (area_name(a) or a) | replace('|', '/') | replace('\n', ' ') }}|
        {%- for e in ents if e.startswith('media_player.') -%}{{ e }}={{ states(e) }}={{ 1 if e in alexa else 0 }}={{ (state_attr(e, 'device_class') or '') | replace('|', '') | replace(',', '') | replace('=', '') }}{{ ',' if not loop.last }}{%- endfor -%}|
        {%- for e in ents if e.startswith('light.') -%}{{ e }}{{ ',' if not loop.last }}{%- endfor -%}
        {{ '\n' }}
        {%- endfor -%}
    """.trimIndent()

    /** Parses [AREAS_TEMPLATE] output. Bad lines are skipped. */
    fun parseAreas(output: String): List<HaArea> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val id = parts[0].trim()
            if (id.isEmpty()) return@mapNotNull null
            val players = parts[2].split(',').mapNotNull { p ->
                val f = p.trim().split('=')
                val eid = f.getOrNull(0)?.trim().orEmpty()
                if (!eid.startsWith("media_player.") || !isValidEntityId(eid)) null
                else HaAreaPlayer(
                    eid,
                    f.getOrNull(1)?.trim().orEmpty().ifEmpty { "unknown" },
                    f.getOrNull(2)?.trim() == "1",
                    f.getOrNull(3)?.trim().orEmpty().takeUnless { it == "None" }.orEmpty()
                )
            }
            val lights = parts[3].split(',').map { it.trim() }
                .filter { it.startsWith("light.") && isValidEntityId(it) }
            HaArea(id, parts[1].trim().ifEmpty { id }, players, lights)
        }.toList()

    /** Areas worth offering: those with a media player and/or a light. */
    fun roomCandidates(areas: List<HaArea>): List<HaArea> = areas.filter { it.hasPlayer || it.hasLights }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val STOP_WORDS = setOf(
        "en", "el", "la", "los", "las", "lo", "mi", "de", "del", "al", "un", "una", "estoy", "esta",
        "the", "in", "my", "at", "on", "im", "i", "am", "room"
    )
    private val PHONE_PHRASES = listOf(
        "aqui", "aca", "solo el telefono", "solo telefono", "telefono", "celular", "movil", "ninguno",
        "ninguna", "here", "phone", "just the phone", "only the phone", "none", "nowhere"
    )

    /** Generic room words that mean the same place; used only when names do not match directly. */
    private val ROOM_SYNONYMS: List<Set<String>> = listOf(
        setOf("recamara", "dormitorio", "habitacion", "alcoba", "cuarto", "bedroom"),
        setOf("sala", "estancia", "salon", "living", "lounge", "family"),
        setOf("cocina", "kitchen"),
        setOf("comedor", "dining"),
        setOf("oficina", "estudio", "despacho", "office", "study"),
        setOf("bano", "aseo", "bathroom", "toilet"),
        setOf("jardin", "patio", "terraza", "garden", "yard", "terrace"),
        setOf("cochera", "garaje", "garage")
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_WORD, " ")
            .trim()

    private fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }

    /** True when [answer] means "here / just the phone" rather than a room. */
    fun isPhoneOnlyAnswer(answer: String): Boolean {
        val n = normalize(answer)
        if (n.isEmpty()) return false
        val compact = tokens(answer).joinToString(" ")
        return PHONE_PHRASES.any { n == it || compact == it } ||
            Regex("\\b(solo|only|just)\\b.*\\b(telefono|celular|movil|phone)\\b").containsMatchIn(n)
    }

    /**
     * The area the user means by [answer]: exact name or id (accents and case ignored), then a
     * unique partial match, then a unique generic-synonym match ("dormitorio" → "Recámara"),
     * then a unique close spelling. Several equal matches → [PlaceMatch.Ambiguous].
     */
    fun matchPlace(answer: String, areas: List<HaArea>): PlaceMatch {
        if (isPhoneOnlyAnswer(answer)) return PlaceMatch.PhoneOnly
        val query = tokens(answer)
        if (query.isEmpty() || areas.isEmpty()) return PlaceMatch.None
        val q = query.joinToString(" ")

        fun pick(list: List<HaArea>): PlaceMatch? = when (list.size) {
            0 -> null
            1 -> PlaceMatch.Found(list.single())
            else -> PlaceMatch.Ambiguous(list)
        }

        fun names(a: HaArea) = listOf(tokens(a.name).joinToString(" "), tokens(a.id.replace('_', ' ')).joinToString(" "))
            .filter { it.isNotEmpty() }

        pick(areas.filter { a -> names(a).any { it == q } })?.let { return it }

        // Partial: every query word is in the name, or every name word is in the query.
        val partial = areas.filter { a ->
            val nameTokens = tokens(a.name).toSet() + tokens(a.id.replace('_', ' '))
            nameTokens.isNotEmpty() && (query.all { it in nameTokens } || tokens(a.name).let { nt -> nt.isNotEmpty() && nt.all { it in query } })
        }
        pick(partial)?.let { return it }

        // Prefix: "recam" → "Recámara", "office" → "Office 2".
        val prefix = areas.filter { a -> names(a).any { n -> n.startsWith(q) && q.length >= 3 } }
        pick(prefix)?.let { return it }

        val synonymsOfQuery = query.flatMap { word -> ROOM_SYNONYMS.filter { word in it }.flatten() }.toSet()
        if (synonymsOfQuery.isNotEmpty()) {
            val bySynonym = areas.filter { a -> tokens(a.name).any { it in synonymsOfQuery } }
            pick(bySynonym)?.let { return it }
        }

        val scored = areas.map { a -> a to (names(a).maxOfOrNull { similarity(it, q) } ?: 0.0) }
        val best = scored.maxOfOrNull { it.second } ?: 0.0
        if (best >= 0.75) {
            return pick(scored.filter { it.second == best }.map { it.first }) ?: PlaceMatch.None
        }
        return PlaceMatch.None
    }

    /**
     * Whether a room session can start and where. No Home Assistant or no area with a player
     * or light → the phone, without asking. A [requested] place is matched; when it does not
     * match (or matches several), ask. Nothing requested: one candidate → use it and say so;
     * several → ask, suggesting [lastUsed] when it is still a candidate.
     */
    fun decidePlace(
        homeAssistantAvailable: Boolean,
        candidates: List<HaArea>,
        requested: String?,
        lastUsed: String?
    ): PlaceDecision {
        if (!homeAssistantAvailable) return PlaceDecision.UsePhone(PhoneReason.NO_HOME_ASSISTANT)
        if (candidates.isEmpty()) return PlaceDecision.UsePhone(PhoneReason.NO_ROOMS)
        val suggestion = lastUsed?.takeIf { it.isNotBlank() }?.let { last ->
            (matchPlace(last, candidates) as? PlaceMatch.Found)?.area?.name
        }
        val options = candidates.map { it.name }
        val asked = requested?.trim().orEmpty()
        if (asked.isNotEmpty()) {
            return when (val m = matchPlace(asked, candidates)) {
                is PlaceMatch.Found -> PlaceDecision.UseArea(m.area, announce = false)
                PlaceMatch.PhoneOnly -> PlaceDecision.UsePhone(PhoneReason.USER_CHOSE_PHONE)
                is PlaceMatch.Ambiguous -> PlaceDecision.Ask(m.candidates.map { it.name }, suggestion, asked)
                PlaceMatch.None -> PlaceDecision.Ask(options, suggestion, asked)
            }
        }
        if (candidates.size == 1) return PlaceDecision.UseArea(candidates.single(), announce = true)
        return PlaceDecision.Ask(options, suggestion, unmatched = null)
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val max = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    // ---- Lights ----

    /**
     * Template reading each light as one [LightSnapshot] line (same format as
     * [encodeLight]). Ids must already be valid entity ids.
     */
    fun lightsTemplate(entityIds: List<String>): String {
        val ids = entityIds.filter { isValidEntityId(it) }.joinToString(",") { "'$it'" }
        fun list(attr: String) = "{{ (state_attr(e, '$attr') or []) | join(',') }}"
        fun value(attr: String) = "{{ state_attr(e, '$attr') }}"
        return "{%- for e in [$ids] -%}" +
            "{{ e }}|{{ states(e) }}|${value("brightness")}|${value("color_mode")}|${value("color_temp_kelvin")}|" +
            "${list("hs_color")}|${list("rgb_color")}|${list("xy_color")}|${list("rgbw_color")}|${list("rgbww_color")}|" +
            "{{ (state_attr(e, 'effect') or '') | replace('|', '/') }}|${list("supported_color_modes")}|" +
            "{{ (state_attr(e, 'entity_id') or []) | join(',') }}{{ '\\n' }}" +
            "{%- endfor -%}"
    }

    /** Parses [lightsTemplate] output; lights whose state is unknown/unavailable are skipped. */
    fun parseLights(output: String): List<LightSnapshot> =
        output.lineSequence().mapNotNull { decodeLight(it) }.toList()

    /** One-line text form of a snapshot, for templates and for the pending-restore record. */
    fun encodeLight(s: LightSnapshot): String = listOf(
        s.entityId,
        if (s.isOn) "on" else "off",
        s.brightness?.toString().orEmpty(),
        s.colorMode.orEmpty(),
        s.colorTempKelvin?.toString().orEmpty(),
        s.hsColor?.joinToString(",").orEmpty(),
        s.rgbColor?.joinToString(",").orEmpty(),
        s.xyColor?.joinToString(",").orEmpty(),
        s.rgbwColor?.joinToString(",").orEmpty(),
        s.rgbwwColor?.joinToString(",").orEmpty(),
        s.effect.orEmpty().replace('|', '/').replace('\n', ' '),
        s.supportedColorModes.joinToString(","),
        s.members.joinToString(",")
    ).joinToString("|")

    fun decodeLight(line: String): LightSnapshot? {
        val f = line.trim().split('|')
        if (f.size < 13) return null
        val eid = f[0].trim()
        if (!eid.startsWith("light.") || !isValidEntityId(eid)) return null
        val state = f[1].trim()
        if (state != "on" && state != "off") return null
        fun text(i: Int) = f[i].trim().takeUnless { it.isEmpty() || it == "None" || it == "none" || it == "unknown" }
        fun doubles(i: Int) = text(i)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.isNotEmpty() }
        fun ints(i: Int) = doubles(i)?.map { it.roundToInt() }
        return LightSnapshot(
            entityId = eid,
            isOn = state == "on",
            brightness = text(2)?.toDoubleOrNull()?.roundToInt(),
            colorMode = text(3),
            colorTempKelvin = text(4)?.toDoubleOrNull()?.roundToInt(),
            hsColor = doubles(5)?.takeIf { it.size == 2 },
            rgbColor = ints(6)?.takeIf { it.size == 3 },
            xyColor = doubles(7)?.takeIf { it.size == 2 },
            rgbwColor = ints(8)?.takeIf { it.size == 4 },
            rgbwwColor = ints(9)?.takeIf { it.size == 5 },
            effect = text(10),
            supportedColorModes = text(11)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            members = text(12)?.split(',')?.map { it.trim() }?.filter { isValidEntityId(it) }.orEmpty()
        )
    }

    /**
     * The lights a session will change: on, dimmable, and not a group whose members are
     * also in the list (dimming both would fight). Off lights are never included.
     */
    fun lightsToAdjust(snapshots: List<LightSnapshot>): List<LightSnapshot> {
        val ids = snapshots.map { it.entityId }.toSet()
        return snapshots.filter { s ->
            s.isOn && s.supportsBrightness && s.members.none { it in ids }
        }
    }

    /**
     * Calls that dim [snapshots] warm: only lights from [lightsToAdjust]; never brighter than
     * they already are; the warm colour only on lights that can show it.
     */
    fun planDim(
        snapshots: List<LightSnapshot>,
        kelvin: Int = DEFAULT_WARM_KELVIN,
        brightnessPercent: Int = DEFAULT_DIM_PERCENT,
        transitionSeconds: Int = DEFAULT_TRANSITION_S
    ): List<HaServiceCall> = lightsToAdjust(snapshots).map { s ->
        val data = linkedMapOf<String, Any>(
            "entity_id" to s.entityId,
            "brightness" to dimmedBrightness(s.brightness, brightnessPercent),
            "transition" to transitionSeconds
        )
        if (s.supportsColorTemp) data["color_temp_kelvin"] = kelvin
        HaServiceCall("light", "turn_on", data)
    }

    /**
     * Calls that put [snapshots] back exactly: lights that were on get their brightness and
     * the colour of their colour mode (and effect); lights that were off are turned off. Only
     * pass the lights that were actually changed, so untouched lights are left alone.
     */
    fun planRestore(snapshots: List<LightSnapshot>, transitionSeconds: Int = 3): List<HaServiceCall> =
        snapshots.map { s ->
            if (!s.isOn) {
                HaServiceCall("light", "turn_off", linkedMapOf("entity_id" to s.entityId, "transition" to transitionSeconds))
            } else {
                val data = linkedMapOf<String, Any>("entity_id" to s.entityId, "transition" to transitionSeconds)
                s.brightness?.let { data["brightness"] = it }
                when (s.colorMode) {
                    "color_temp" -> s.colorTempKelvin?.let { data["color_temp_kelvin"] = it }
                    "hs" -> s.hsColor?.let { data["hs_color"] = it }
                    "rgb" -> s.rgbColor?.let { data["rgb_color"] = it }
                    "xy" -> s.xyColor?.let { data["xy_color"] = it }
                    "rgbw" -> s.rgbwColor?.let { data["rgbw_color"] = it }
                    "rgbww" -> s.rgbwwColor?.let { data["rgbww_color"] = it }
                    else -> Unit
                }
                s.effect?.takeIf { it.lowercase(Locale.ROOT) !in setOf("none", "off", "") }?.let { data["effect"] = it }
                HaServiceCall("light", "turn_on", data)
            }
        }

    /** Calls that turn [snapshots] off slowly (the end of a sleep session); only lights Treko dimmed. */
    fun planTurnOff(snapshots: List<LightSnapshot>, transitionSeconds: Int = SLEEP_OFF_TRANSITION_S): List<HaServiceCall> =
        snapshots.map { s ->
            HaServiceCall("light", "turn_off", linkedMapOf("entity_id" to s.entityId, "transition" to transitionSeconds))
        }

    /** scene.create call snapshotting [entityIds] under [RESTORE_SCENE_ID]. */
    fun planSceneSnapshot(entityIds: List<String>): HaServiceCall =
        HaServiceCall("scene", "create", linkedMapOf("scene_id" to RESTORE_SCENE_ID, "snapshot_entities" to entityIds))

    // ---- Volume ----

    /**
     * Volume levels for a fade from [from] to [to] in [steps] steps, the last one exactly
     * [to]; each value in 0..1 with two decimals.
     */
    fun volumeFadeSteps(from: Double, to: Double, steps: Int): List<Double> {
        val n = steps.coerceAtLeast(1)
        val a = from.coerceIn(0.0, 1.0)
        val b = to.coerceIn(0.0, 1.0)
        return (1..n).map { i -> ((a + (b - a) * i / n) * 100).roundToInt() / 100.0 }
    }

    /** Parses `state|volume` from the player template; null when the player is unknown. */
    fun parsePlayer(entityId: String, output: String): PlayerSnapshot? {
        val f = output.trim().split('|')
        val state = f.getOrNull(0)?.trim().orEmpty()
        if (state.isEmpty() || state == "unknown" && f.getOrNull(1)?.trim() in setOf(null, "", "None")) return null
        return PlayerSnapshot(entityId, state, f.getOrNull(1)?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0))
    }

    // ---- Pending restore record ----

    private const val RECORD_VERSION = "treko-room-restore-v1"

    /** Text form of [record], safe to keep in preferences; [decodeRecord] reads it back. */
    fun encodeRecord(record: RoomRestoreRecord): String = buildString {
        appendLine(RECORD_VERSION)
        appendLine("scene=${record.sceneEntityId.orEmpty()}")
        appendLine("player=${record.mediaPlayer.orEmpty()}")
        appendLine("volume=${record.volume?.toString().orEmpty()}")
        appendLine("started=${record.startedAtMs}")
        appendLine("dim=${record.dimBrightnessPercent?.toString().orEmpty()},${record.dimKelvin?.toString().orEmpty()}")
        appendLine("level=${record.playerLevel?.toString().orEmpty()}")
        appendLine("end=${if (record.lightsOffAtEnd) "off" else "restore"}")
        record.lights.forEach { appendLine("L=${encodeLight(it)}") }
    }.trimEnd()

    /** Reads a record written by [encodeRecord]; null for empty or unreadable text. */
    fun decodeRecord(text: String?): RoomRestoreRecord? {
        val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        if (lines.firstOrNull() != RECORD_VERSION) return null
        fun field(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.ifEmpty { null }
        val scene = field("scene")?.takeIf { isValidEntityId(it) }
        val player = field("player")?.takeIf { isValidEntityId(it) }
        val dim = field("dim")?.split(',')
        return RoomRestoreRecord(
            sceneEntityId = scene,
            lights = lines.filter { it.startsWith("L=") }.mapNotNull { decodeLight(it.removePrefix("L=")) },
            mediaPlayer = player,
            volume = field("volume")?.toDoubleOrNull(),
            startedAtMs = field("started")?.toLongOrNull() ?: 0L,
            dimBrightnessPercent = dim?.getOrNull(0)?.trim()?.toIntOrNull(),
            dimKelvin = dim?.getOrNull(1)?.trim()?.toIntOrNull(),
            playerLevel = field("level")?.toDoubleOrNull(),
            lightsOffAtEnd = field("end") == "off"
        )
    }

    // ---- Late restore ----

    /** How far (0..255) a light's brightness may be from what Treko set and still count as Treko's. */
    private const val BRIGHTNESS_TOLERANCE = 26
    private const val KELVIN_TOLERANCE = 350
    private const val VOLUME_TOLERANCE = 0.03

    /** Brightness (0..255) [planDim] gives a light that was at [original]. */
    fun dimmedBrightness(original: Int?, brightnessPercent: Int): Int {
        val target = (brightnessPercent.coerceIn(1, 100) * 255 + 50) / 100
        return minOf(target, original ?: target)
    }

    /** What a late restore of [record] may still put back, and whether to stop its player. */
    data class LateRestorePlan(val record: RoomRestoreRecord, val stopPlayer: Boolean)

    /**
     * For a restore that comes late (after a crash, or a retry once Home Assistant is back):
     * only what is still as Treko left it. A light the user turned off is never turned on, a
     * light set to another level or colour is left alone, and the player is touched only while
     * it is still at Treko's volume (stopped only if it is still playing). [lights] is the
     * current state of the record's lights (a light missing from it is not restored);
     * [player] the current state of its player (null: gone).
     */
    fun planLateRestore(record: RoomRestoreRecord, lights: List<LightSnapshot>, player: PlayerSnapshot?): LateRestorePlan {
        val now = lights.associateBy { it.entityId }
        val kept = record.lights.filter { saved ->
            val cur = now[saved.entityId] ?: return@filter false
            if (!cur.isOn) return@filter false
            val percent = record.dimBrightnessPercent
            if (percent != null && cur.brightness != null &&
                kotlin.math.abs(cur.brightness - dimmedBrightness(saved.brightness, percent)) > BRIGHTNESS_TOLERANCE
            ) {
                return@filter false
            }
            val kelvin = record.dimKelvin
            !(kelvin != null && saved.supportsColorTemp && cur.colorMode == "color_temp" && cur.colorTempKelvin != null &&
                kotlin.math.abs(cur.colorTempKelvin - kelvin) > KELVIN_TOLERANCE)
        }
        // The scene would also put back the lights the user changed: only when all are kept.
        val scene = record.sceneEntityId.takeIf { kept.size == record.lights.size }
        var stop = false
        val keepPlayer = record.mediaPlayer != null && player != null && run {
            val level = record.playerLevel
            val volume = player.volume
            // Unknown volume or not at Treko's level: the user has the player now.
            if (record.volume == null || level == null || volume == null || volume > level + VOLUME_TOLERANCE) return@run false
            stop = player.state == "playing" || player.state == "buffering"
            true
        }
        return LateRestorePlan(
            record.copy(
                sceneEntityId = scene,
                lights = kept,
                mediaPlayer = record.mediaPlayer.takeIf { keepPlayer },
                volume = record.volume.takeIf { keepPlayer }
            ),
            stopPlayer = stop
        )
    }
}

private val UNAVAILABLE_STATES = setOf("unavailable", "unknown")

/** Colour modes on which Home Assistant can show a colour temperature (converting it if needed). */
private val COLOR_TEMP_CAPABLE_MODES = setOf("color_temp", "hs", "rgb", "rgbw", "rgbww", "xy")
