package com.treko.assistant.data

import java.util.Calendar
import java.util.UUID

/**
 * Categoría principal del hallazgo proactivo generado por Treko.
 */
enum class ProactiveCategory {
    CAMERA_ANOMALY,
    HOME_STATUS,
    HABITS_AND_WATER,
    TASKS_AND_REMINDERS,
    MEMORY_FOLLOWUP,
    DAILY_BRIEFING
}

/**
 * Resultado del análisis de visión en una cámara prioritaria del hogar.
 */
data class CameraAnomalyCheckResult(
    val cameraName: String,
    val entityId: String,
    val visionReport: String,
    val hasAnomaly: Boolean
)

/**
 * Insight proactivo sintetizado listo para mostrarse en notificación/banner o hablarse por voz.
 */
data class ProactiveInsight(
    val id: String,
    val category: ProactiveCategory,
    val title: String,
    val summaryText: String,
    val spokenPrompt: String,
    val fingerprint: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Fuentes de contexto consultadas por el motor proactivo y centinela de Treko.
 */
interface ProactiveDataSources {
    suspend fun getHomeStatusSummary(): String
    suspend fun scanPriorityCameraForAnomalies(): CameraAnomalyCheckResult?
    suspend fun getHabitsSummary(): String
    suspend fun getTasksSummary(): String
    suspend fun getVaultMemories(): List<Pair<String, String>>
    fun getActiveTimersCount(): Int
}

/**
 * Motor proactivo ("Centinela y Acompañante") inspirado en arquitecturas como
 * Seeed SenseCAP Watcher, Xiaozhi AI y Home Assistant LLM Vision.
 *
 * Evalúa estado del hogar, anomalías en cámaras, hábitos/hidratación, tareas y memoria personal
 * con deduplicación temporal y respeto a horarios silenciosos.
 */
class TrekoProactiveManager(
    private val sources: ProactiveDataSources,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val recentFingerprints = LinkedHashMap<String, Long>()

    companion object {
        private const val COOLDOWN_MS = 45L * 60_000L

        private val NEGATIVE_CAMERA_PHRASES = listOf(
            "no se observa",
            "no se observan",
            "sin anomal",
            "ninguna anomal",
            "ninguna persona",
            "no hay nadie",
            "no hay personas",
            "no hay movimiento",
            "todo tranquilo",
            "entorno tranquilo",
            "todo normal",
            "despejado",
            "vacio",
            "sin novedad",
            "vehiculo estacionado",
            "vehiculos estacionados",
            "auto estacionado",
            "autos estacionados",
            "coche estacionado",
            "coches estacionados",
            "no anomaly",
            "nothing unusual",
            "all clear"
        )

        private val ANOMALY_CAMERA_KEYWORDS = listOf(
            "anomal",
            "persona",
            "alguien",
            "intruso",
            "desconocid",
            "paquete",
            "caja",
            "puerta abierta",
            "porton abierto",
            "ventana abierta",
            "movimiento",
            "vehiculo",
            "coche",
            "auto ",
            "camioneta",
            "sospech",
            "humo",
            "fuego",
            "person",
            "someone",
            "package",
            "open door",
            "intruder"
        )

        /**
         * Determina si la hora actual cae en horario nocturno silencioso (23:00 a 06:59).
         */
        fun isWithinQuietHours(hourOfDay: Int, quietHoursEnabled: Boolean): Boolean {
            if (!quietHoursEnabled) return false
            val h = ((hourOfDay % 24) + 24) % 24
            return h >= 23 || h < 7
        }

        private val NEGATED_CLAUSE_REGEX = Regex(
            """\b(?:no\s+se\s+observa[n]?|no\s+se\s+ve[n]?|no\s+detect[ao]n?|no\s+hay|sin|ningun[ao]?|ni)\s+(?:(?:una|un|el|la|los|las|de)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|(?:puertas?|porton(?:es)?|ventanas?)\s+abiert[ao]s?)(?:\s*(?:,|ni|o|y)\s*(?:(?:una|un|el|la|los|las|de|ningun[ao]?)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|(?:puertas?|porton(?:es)?|ventanas?)\s+abiert[ao]s?))*"""
        )

        /**
         * Analiza un reporte de LLM Vision para distinguir anomalías reales de escenas tranquilas.
         */
        fun detectCameraAnomaly(visionReport: String): Boolean {
            val normalized = visionReport
                .lowercase()
                .replace("á", "a")
                .replace("é", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ú", "u")
                .replace("ñ", "n")
                .trim()
            if (normalized.isBlank()) return false

            var cleaned = NEGATED_CLAUSE_REGEX.replace(normalized, " ")
            for (neg in NEGATIVE_CAMERA_PHRASES) {
                cleaned = cleaned.replace(neg, " ")
            }
            return ANOMALY_CAMERA_KEYWORDS.any { cleaned.contains(it) }
        }
    }

    /**
     * Evalúa todas las fuentes configuradas y genera un [ProactiveInsight] si hay novedades relevantes
     * o si [force] es verdadero.
     */
    suspend fun evaluateNow(
        includeCameraScan: Boolean,
        force: Boolean = false,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        assistantName: String = "Treko"
    ): ProactiveInsight? {
        val now = clockMs()
        val cleanAssistantName = assistantName.trim().ifBlank { "Treko" }

        val cameraResult = if (includeCameraScan) {
            runCatching { sources.scanPriorityCameraForAnomalies() }.getOrNull()
        } else {
            null
        }
        val homeSummary = runCatching { sources.getHomeStatusSummary() }.getOrDefault("")
        val habitsSummary = runCatching { sources.getHabitsSummary() }.getOrDefault("")
        val tasksSummary = runCatching { sources.getTasksSummary() }.getOrDefault("")
        val memories = runCatching { sources.getVaultMemories() }.getOrDefault(emptyList())
        val activeTimers = runCatching { sources.getActiveTimersCount() }.getOrDefault(0)

        val homeAlertsLine = extractSectionPrefix(homeSummary, "Sensores y accesos activos")
        val lightsOnLine = extractSectionPrefix(homeSummary, "Luces encendidas")

        val pendingHabits = parseTaggedItems(habitsSummary, "[Pendiente]")
        val completedHabitsCount = parseCompletedCount(habitsSummary)
        val waterProgress = parseWaterProgress(habitsSummary)
        val pendingTasks = parseTaggedItems(tasksSummary, "[Pendiente]")

        // Prioridad 1: Anomalía visual real detectada en cámara
        if (cameraResult != null && cameraResult.hasAnomaly) {
            val cleanReport = cameraResult.visionReport.trim().take(220)
            val fp = "cam_anomaly:${cameraResult.entityId}:${cleanReport.take(60).lowercase()}"
            if (!force && isInCooldown(fp, now)) return null
            recordFingerprint(fp, now)

            val summary = "Alerta visual en ${cameraResult.cameraName}: $cleanReport"
            val spokenPrompt = buildString {
                append("[MODO CENTINELA PROACTIVO] Acabas de revisar la cámara '${cameraResult.cameraName}' y detectaste algo importante: ")
                append(cleanReport)
                append(". ")
                if (!homeAlertsLine.isNullOrBlank()) {
                    append("Además en Home Assistant figura: $homeAlertsLine. ")
                }
                append("Inicia la conversación con el usuario en español de forma natural, clara y tranquila informándole exactamente lo visto en la cámara y pregúntale si desea abrir la vista de cámaras o tomar alguna acción.")
            }
            return ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.CAMERA_ANOMALY,
                title = "Centinela · ${cameraResult.cameraName}",
                summaryText = summary,
                spokenPrompt = spokenPrompt,
                fingerprint = fp,
                createdAtMs = now
            )
        }

        val highlights = mutableListOf<String>()
        val promptContext = mutableListOf<String>()

        if (!homeAlertsLine.isNullOrBlank()) {
            highlights.add(homeAlertsLine)
            promptContext.add("Estado de Home Assistant: $homeAlertsLine.")
        } else if (!lightsOnLine.isNullOrBlank() && (hourOfDay >= 22 || hourOfDay < 6 || force)) {
            highlights.add(lightsOnLine)
            promptContext.add("Iluminación actual: $lightsOnLine.")
        }

        if (pendingTasks.isNotEmpty()) {
            val preview = pendingTasks.take(2).joinToString(", ")
            highlights.add("Tareas pendientes (${pendingTasks.size}): $preview")
            promptContext.add("Tareas pendientes del usuario (${pendingTasks.size}): ${pendingTasks.take(4).joinToString("; ")}.")
        }

        if (pendingHabits.isNotEmpty() && (hourOfDay >= 11 || force)) {
            val preview = pendingHabits.take(2).joinToString(", ")
            highlights.add("Hábitos por cumplir (${pendingHabits.size}): $preview")
            promptContext.add("Hábitos de hoy: $completedHabitsCount completados y ${pendingHabits.size} pendientes (${pendingHabits.take(3).joinToString("; ")}).")
        } else if (completedHabitsCount > 0 && force) {
            highlights.add("Hábitos cumplidos hoy: $completedHabitsCount")
            promptContext.add("El usuario ya cumplió $completedHabitsCount hábitos hoy.")
        }

        if (waterProgress != null && waterProgress.first < 6 && (hourOfDay >= 14 || force)) {
            val waterText = "Hidratación: ${waterProgress.first}/${waterProgress.second} vasos"
            highlights.add(waterText)
            promptContext.add("Progreso de hidratación hoy: ${waterProgress.first} de ${waterProgress.second} vasos recomendados.")
        }

        if (activeTimers > 0) {
            highlights.add("Temporizadores activos: $activeTimers")
            promptContext.add("Hay $activeTimers temporizadores activos en curso.")
        }

        if (cameraResult != null && !cameraResult.hasAnomaly && force) {
            val cleanCam = cameraResult.visionReport.trim().take(120)
            highlights.add("${cameraResult.cameraName}: $cleanCam")
            promptContext.add("Revisión visual reciente de cámara '${cameraResult.cameraName}': $cleanCam.")
        }

        val featuredMemory = memories.firstOrNull { (k, v) -> k.isNotBlank() && v.isNotBlank() }
        if (featuredMemory != null) {
            promptContext.add("Memoria guardada relevante (${featuredMemory.first}): ${featuredMemory.second}.")
            if (highlights.isEmpty()) {
                highlights.add("Nota en memoria (${featuredMemory.first}): ${featuredMemory.second.take(80)}")
            }
        }

        if (highlights.isEmpty()) {
            if (!force) return null
            val fallbackSummary = "Todo tranquilo en tu hogar, sin anomalías en cámaras y al día con tu jornada."
            val fallbackPrompt = "[ASISTENTE PROACTIVO] Acabas de hacer un chequeo general de la casa, cámaras, hábitos y tareas. Todo está en orden. Saluda brevemente al usuario en español, dale un reporte tranquilo de 1 o 2 frases y pregúntale si necesita algo o quiere poner música."
            return ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.DAILY_BRIEFING,
                title = "Resumen proactivo",
                summaryText = fallbackSummary,
                spokenPrompt = fallbackPrompt,
                fingerprint = "briefing:all_clear:$now",
                createdAtMs = now
            )
        }

        val category = when {
            !homeAlertsLine.isNullOrBlank() || !lightsOnLine.isNullOrBlank() -> ProactiveCategory.HOME_STATUS
            pendingTasks.isNotEmpty() -> ProactiveCategory.TASKS_AND_REMINDERS
            pendingHabits.isNotEmpty() || waterProgress != null -> ProactiveCategory.HABITS_AND_WATER
            featuredMemory != null -> ProactiveCategory.MEMORY_FOLLOWUP
            else -> ProactiveCategory.DAILY_BRIEFING
        }

        val title = when (category) {
            ProactiveCategory.HOME_STATUS -> "Estado del hogar"
            ProactiveCategory.TASKS_AND_REMINDERS -> "Tus tareas pendientes"
            ProactiveCategory.HABITS_AND_WATER -> "Hábitos e hidratación"
            ProactiveCategory.MEMORY_FOLLOWUP -> "Recordatorio de memoria"
            ProactiveCategory.DAILY_BRIEFING -> "Resumen proactivo"
            ProactiveCategory.CAMERA_ANOMALY -> "Centinela"
        }

        val summaryText = highlights.take(3).joinToString(" · ")
        val fp = "${category.name}:${highlights.take(2).joinToString("|").lowercase()}"
        if (!force && isInCooldown(fp, now)) return null
        recordFingerprint(fp, now)

        val spokenPrompt = buildString {
            append("[ASISTENTE PROACTIVO] Eres $cleanAssistantName iniciando una conversación útil, cercana y breve en español (máximo 3 frases) basada en tu revisión automática actual: ")
            append(promptContext.joinToString(" "))
            append(" Coméntale esto al usuario con un tono natural y cordial, y hazle una pregunta práctica para continuar la conversación (por ejemplo, si quiere completar un hábito, ajustar luces, revisar cámaras o crear un temporizador).")
        }

        return ProactiveInsight(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            summaryText = summaryText,
            spokenPrompt = spokenPrompt,
            fingerprint = fp,
            createdAtMs = now
        )
    }

    private fun isInCooldown(fingerprint: String, nowMs: Long): Boolean {
        val last = recentFingerprints[fingerprint] ?: return false
        return (nowMs - last) < COOLDOWN_MS
    }

    private fun recordFingerprint(fingerprint: String, nowMs: Long) {
        recentFingerprints[fingerprint] = nowMs
        if (recentFingerprints.size > 24) {
            val oldest = recentFingerprints.entries.minByOrNull { it.value }?.key
            if (oldest != null) recentFingerprints.remove(oldest)
        }
    }

    private fun extractSectionPrefix(homeSummary: String, prefix: String): String? {
        if (homeSummary.isBlank()) return null
        return homeSummary
            .lines()
            .firstOrNull { it.trim().startsWith(prefix, ignoreCase = true) }
            ?.trim()
            ?.removeSuffix(".")
    }

    private fun parseTaggedItems(summary: String, tag: String): List<String> {
        if (summary.isBlank()) return emptyList()
        return summary.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.contains(tag, ignoreCase = true)) return@mapNotNull null
            trimmed
                .removePrefix("•")
                .substringBefore("[")
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }

    private fun parseCompletedCount(habitsSummary: String): Int {
        if (habitsSummary.isBlank()) return 0
        return habitsSummary.lines().count { it.contains("[Completado", ignoreCase = true) }
    }

    private fun parseWaterProgress(habitsSummary: String): Pair<Int, Int>? {
        val match = Regex("""Hidrataci[oó]n:\s*(\d+)\s*de\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(habitsSummary) ?: return null
        val current = match.groupValues[1].toIntOrNull() ?: return null
        val target = match.groupValues[2].toIntOrNull() ?: 8
        return current to target
    }
}
