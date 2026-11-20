package com.treko.core.mcp

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a spoken-friendly 3D printer status from the Home Assistant OctoPrint integration.
 *
 * Entity ids follow `<domain>.<printer>_<key>` (e.g. `sensor.octoprint_job_percentage`); the
 * printer prefix is whatever the user named the device, so keys are matched by suffix.
 */
internal object PrinterStatus {

    data class Entity(
        val entityId: String,
        val friendlyName: String,
        val state: String,
        val unit: String = ""
    )

    private data class Key(val domain: String, val suffix: String)

    private val PERCENT = Key("sensor", "_job_percentage")
    private val STATE = Key("sensor", "_current_state")
    private val FINISH = Key("sensor", "_estimated_finish_time")
    private val START = Key("sensor", "_start_time")
    private val FILE = Key("sensor", "_current_file")
    private val TOOL_ACTUAL = Key("sensor", "_actual_tool0_temp")
    private val TOOL_TARGET = Key("sensor", "_target_tool0_temp")
    private val BED_ACTUAL = Key("sensor", "_actual_bed_temp")
    private val BED_TARGET = Key("sensor", "_target_bed_temp")
    private val PRINTING = Key("binary_sensor", "_printing")
    private val ERROR = Key("binary_sensor", "_printing_error")

    private val ALL_KEYS = listOf(PERCENT, STATE, FINISH, START, FILE, TOOL_ACTUAL, TOOL_TARGET, BED_ACTUAL, BED_TARGET, PRINTING, ERROR)

    private val UNKNOWN_STATES = setOf("unknown", "unavailable", "none", "")

    /** Fallback when the integration lookup is not available: default OctoPrint naming. */
    fun looksLikeOctoPrint(entityId: String): Boolean = entityId.substringAfter('.').contains("octoprint")

    fun summarize(
        entities: List<Entity>,
        cameraEntityIds: List<String>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val printers = groupByPrinter(entities)
        if (printers.isEmpty()) {
            return "No encontré sensores de OctoPrint en Home Assistant. Si la impresora tiene cámara, " +
                "analyze_camera puede mirarla, pero no sabrá el progreso exacto."
        }
        val blocks = printers.map { (prefix, byKey) -> describePrinter(prefix, byKey, now, zone) }
        val camera = cameraEntityIds.firstOrNull { cam -> printers.keys.any { cam.substringAfter('.').startsWith(it) } }
            ?: cameraEntityIds.firstOrNull { looksLikeOctoPrint(it) }
        val cameraHint = if (camera != null) {
            "\nCámara de la impresora: $camera. Usa analyze_camera solo si el usuario quiere ver la pieza; " +
                "el progreso y el estado de arriba son los datos reales."
        } else {
            ""
        }
        return blocks.joinToString("\n") + cameraHint
    }

    private fun groupByPrinter(entities: List<Entity>): Map<String, Map<Key, Entity>> {
        val result = LinkedHashMap<String, MutableMap<Key, Entity>>()
        // Longest suffix first: "_printing_error" must not be read as "_printing".
        val keys = ALL_KEYS.sortedByDescending { it.suffix.length }
        for (entity in entities) {
            val domain = entity.entityId.substringBefore('.')
            val body = entity.entityId.substringAfter('.')
            val key = keys.firstOrNull { it.domain == domain && body.endsWith(it.suffix) } ?: continue
            val prefix = body.removeSuffix(key.suffix)
            result.getOrPut(prefix) { mutableMapOf() }[key] = entity
        }
        // A group needs at least the state or the progress to describe a printer.
        return result.filterValues { it.containsKey(STATE) || it.containsKey(PERCENT) || it.containsKey(PRINTING) }
    }

    private fun describePrinter(prefix: String, byKey: Map<Key, Entity>, now: Instant, zone: ZoneId): String {
        val parts = mutableListOf<String>()
        val rawState = byKey[STATE]?.state.orEmpty()
        val printing = byKey[PRINTING]?.state.equals("on", ignoreCase = true) ||
            rawState.startsWith("printing", ignoreCase = true)
        val stateText = when {
            rawState.isNotBlank() && rawState.lowercase(Locale.ROOT) !in UNKNOWN_STATES -> translateState(rawState)
            printing -> "imprimiendo"
            byKey.values.all { it.state.lowercase(Locale.ROOT) in UNKNOWN_STATES } ->
                "no disponible (OctoPrint o la impresora están apagados o desconectados)"
            else -> "sin imprimir"
        }
        parts += "Estado: $stateText"

        byKey[ERROR]?.takeIf { it.state.equals("on", ignoreCase = true) }?.let {
            parts += "OctoPrint reporta un ERROR de impresión"
        }

        byKey[PERCENT]?.state?.toDoubleOrNull()?.let { pct ->
            if (printing || pct in 0.5..99.5) parts += "progreso ${pct.toInt()}%"
        }
        byKey[FILE]?.state?.takeIf { it.lowercase(Locale.ROOT) !in UNKNOWN_STATES }?.let { parts += "archivo: $it" }

        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        parseInstant(byKey[START]?.state)?.takeIf { printing }?.let { start ->
            parts += "empezó a las ${timeFormat.format(start.atZone(zone))}"
        }
        parseInstant(byKey[FINISH]?.state)?.takeIf { printing }?.let { finish ->
            val remaining = Duration.between(now, finish)
            parts += if (remaining.isNegative) {
                "debía terminar a las ${timeFormat.format(finish.atZone(zone))}"
            } else {
                "termina aprox. a las ${timeFormat.format(finish.atZone(zone))} (faltan ${formatDuration(remaining)})"
            }
        }

        temperature("boquilla", byKey[TOOL_ACTUAL], byKey[TOOL_TARGET])?.let { parts += it }
        temperature("cama", byKey[BED_ACTUAL], byKey[BED_TARGET])?.let { parts += it }

        return "Impresora 3D '$prefix' (OctoPrint): " + parts.joinToString(" · ") + "."
    }

    private fun temperature(label: String, actual: Entity?, target: Entity?): String? {
        val a = actual?.state?.toDoubleOrNull() ?: return null
        val t = target?.state?.toDoubleOrNull()
        val unit = actual.unit.ifBlank { "°C" }
        return if (t != null && t > 0) "$label ${a.toInt()}/${t.toInt()} $unit" else "$label ${a.toInt()} $unit"
    }

    internal fun translateState(state: String): String = when (state.trim().lowercase(Locale.ROOT)) {
        "printing" -> "imprimiendo"
        "printing from sd" -> "imprimiendo desde la SD"
        "operational" -> "encendida y lista, sin imprimir"
        "paused" -> "en pausa"
        "pausing" -> "pausando"
        "resuming" -> "reanudando"
        "cancelling" -> "cancelando la impresión"
        "finishing" -> "terminando"
        "offline" -> "desconectada de OctoPrint"
        "offline after error" -> "desconectada tras un error"
        "error" -> "con error"
        "closed", "closed with error" -> "conexión cerrada"
        "connecting", "opening serial connection", "detecting serial connection" -> "conectando"
        else -> state.trim()
    }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim().orEmpty()
        if (value.lowercase(Locale.ROOT) in UNKNOWN_STATES) return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDuration(d: Duration): String {
        val totalMinutes = d.toMinutes()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "${m.coerceAtLeast(1)} min"
        }
    }
}
