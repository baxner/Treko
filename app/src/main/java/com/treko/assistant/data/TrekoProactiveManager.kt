package com.treko.assistant.data

import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

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
 * Candidato de cámara deduplicada para selección inteligente del Centinela.
 */
data class ProactiveCameraCandidate(
    val friendlyName: String,
    val entityId: String,
    val state: String = "idle"
)

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
 * Evaluación inteligente del ritmo de hidratación diario.
 */
data class HydrationPacingAssessment(
    val currentGlasses: Int,
    val targetGlasses: Int,
    val remainingGlasses: Int,
    val minutesSinceLastGlass: Int?,
    val hoursUntilBedtime: Double,
    val idealCadenceMinutes: Int,
    val expectedGlassesByNow: Int,
    val shouldRemind: Boolean,
    val shortSummary: String,
    val spokenCoaching: String
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
    val createdAtMs: Long = System.currentTimeMillis(),
    val spokenSpeechText: String = summaryText
)

/**
 * Fuentes de contexto consultadas por el motor proactivo y centinela de Treko.
 */
interface ProactiveDataSources {
    suspend fun getHomeStatusSummary(): String
    suspend fun scanPriorityCameraForAnomalies(): CameraAnomalyCheckResult? = null
    suspend fun scanPriorityCamerasForAnomalies(homeStatusSummary: String = ""): List<CameraAnomalyCheckResult> {
        val single = scanPriorityCameraForAnomalies()
        return if (single != null) listOf(single) else emptyList()
    }
    suspend fun getHabitsSummary(): String
    suspend fun getTasksSummary(): String
    suspend fun getVaultMemories(): List<Pair<String, String>>
    fun getActiveTimersCount(): Int
    fun getLastWaterIntakeMs(): Long = 0L
}

/**
 * Motor proactivo ("Centinela y Acompañante") con evaluación cognitiva de contexto:
 * - Selección inteligente multicámara correlacionada con sensores de Home Assistant.
 * - Regla de silencio estricto en cámaras: si una cámara está normal, no genera ruido ni texto innecesario.
 * - Ritmo adaptativo de hidratación y hábitos (calcula tiempo desde el último vaso, horas restantes antes de dormir
 *   y cadencia ideal para no repetir recordatorios cada 15 minutos).
 * - Soporte de posposición (snooze) por tema y deduplicación por estado.
 */
class TrekoProactiveManager(
    private val sources: ProactiveDataSources,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val recentFingerprints = LinkedHashMap<String, Long>()
    private val topicLastRemindedMs = HashMap<String, Long>()
    private val topicLastStateHash = HashMap<String, String>()
    private val topicSnoozedUntilMs = HashMap<String, Long>()
    private var cameraRotationOffset: Int = 0

    @Volatile
    var lastDeliveredInsight: ProactiveInsight? = null
        private set

    fun recordDeliveredInsight(insight: ProactiveInsight) {
        lastDeliveredInsight = insight
    }

    /**
     * Pospone (snooze) recordatorios proactivos de un tema específico ("water"/"agua",
     * "habits"/"habitos", "tasks"/"tareas", "home"/"casa", "cameras"/"camaras", o "all"/"todo").
     */
    fun snoozeTopic(topic: String, durationMinutes: Int = 120, nowMs: Long = clockMs()): String {
        val clean = normalizeAscii(topic)
        val mins = durationMinutes.coerceIn(15, 720)
        val until = nowMs + mins * 60_000L
        val keys = when {
            clean.contains("agua") || clean.contains("water") || clean.contains("hidrat") -> listOf("water")
            clean.contains("habit") -> listOf("habits", "water")
            clean.contains("tarea") || clean.contains("task") || clean.contains("pendiente") -> listOf("tasks")
            clean.contains("casa") || clean.contains("home") || clean.contains("luz") || clean.contains("luces") -> listOf("home")
            clean.contains("camara") || clean.contains("camera") || clean.contains("centinela") -> listOf("cameras")
            else -> listOf("water", "habits", "tasks", "home")
        }
        keys.forEach { k -> topicSnoozedUntilMs[k] = until }
        return "Recordatorios de ${keys.joinToString(", ")} pospuestos por $mins minutos."
    }

    fun isTopicSnoozed(topicKey: String, nowMs: Long = clockMs()): Boolean {
        val until = topicSnoozedUntilMs[topicKey] ?: return false
        return nowMs < until
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 45L * 60_000L
        private const val HABITS_TASK_REPEAT_COOLDOWN_MS = 120L * 60_000L

        const val SENTINEL_VISION_PROMPT =
            "Actúa como un sistema centinela de seguridad doméstica. Evalúa esta cámara e identifica ÚNICAMENTE si existe una ANOMALÍA REAL dentro de la propiedad que requiera atención inmediata del propietario (por ejemplo: desconocido detenido frente a la puerta o intentando entrar, intruso dentro del jardín, patio o cochera, paquete entregado en la entrada, puerta de casa o portón abierto de par en par, humo, fuego o fuga). " +
                "IGNORA por completo escenas cotidianas normales: calle o escena tranquila, autos estacionados en la vía pública, peatones caminando por la acera pública, jardín despejado o habitación en calma. " +
                "Si NO hay ninguna anomalía real que reportar, responde exactamente: [NORMAL]. " +
                "Si SÍ hay una anomalía real, responde: [ANOMALIA] seguido de una descripción breve y directa en una sola frase."

        private val PERIMETER_CAMERA_KEYWORDS = listOf(
            "entrada", "puerta", "porton", "jardin", "patio", "cochera", "garage", "garaje",
            "terraza", "balcon", "exterior", "afuera", "frente", "calle", "acceso", "timbre",
            "porche", "trasero", "lateral", "front", "door", "yard", "garden", "gate",
            "driveway", "porch", "doorbell", "backyard", "outside", "perimeter"
        )

        private val INDOOR_COMMON_KEYWORDS = listOf(
            "sala", "living", "pasillo", "hall", "cocina", "kitchen", "comedor", "estancia", "lobby"
        )

        private val NEGATIVE_CAMERA_PHRASES = listOf(
            "[normal]",
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
            "escena tranquila",
            "calle tranquila",
            "ambiente tranquilo",
            "todo normal",
            "todo en orden",
            "sin actividad inusual",
            "no hay actividad inusual",
            "despejado",
            "vacio",
            "sin novedad",
            "vehiculo estacionado",
            "vehiculos estacionados",
            "vehiculo aparcado",
            "vehiculos aparcados",
            "auto estacionado",
            "autos estacionados",
            "coche estacionado",
            "coches estacionados",
            "coches aparcados",
            "camina junto a un coche",
            "caminando por la acera",
            "caminando por la calle",
            "pasa caminando",
            "no anomaly",
            "nothing unusual",
            "all clear"
        )

        private val ANOMALY_CAMERA_KEYWORDS = listOf(
            "[anomalia]",
            "anomal",
            "persona",
            "alguien",
            "intruso",
            "desconocid",
            "esperando en la puerta",
            "frente a la puerta",
            "tocando la puerta",
            "paquete",
            "caja",
            "entrega",
            "puerta abierta",
            "porton abierto",
            "ventana abierta",
            "cochera abierta",
            "sospech",
            "humo",
            "fuego",
            "incendio",
            "fuga",
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

        internal fun normalizeAscii(text: String): String =
            text.lowercase(Locale.ROOT)
                .replace("á", "a")
                .replace("é", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ú", "u")
                .replace("ü", "u")
                .replace("ñ", "n")
                .trim()

        private val NEGATED_CLAUSE_REGEX = Regex(
            """\b(?:no\s+se\s+observa[n]?|no\s+se\s+ve[n]?|no\s+detect[ao]n?|no\s+hay|sin|ningun[ao]?|ni)\s+(?:(?:una|un|el|la|los|las|de)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|actividad(?:es)?\s+inusual(?:es)?|(?:puertas?|porton(?:es)?|ventanas?|cocheras?)\s+abiert[ao]s?)(?:\s*(?:,|ni|o|y)\s*(?:(?:una|un|el|la|los|las|de|ningun[ao]?)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|actividad(?:es)?\s+inusual(?:es)?|(?:puertas?|porton(?:es)?|ventanas?|cocheras?)\s+abiert[ao]s?))*"""
        )

        /**
         * Analiza un reporte de LLM Vision para distinguir anomalías reales de escenas tranquilas.
         * Si el modelo reporta una escena tranquila o [NORMAL], devuelve false para no generar avisos falsos.
         */
        fun detectCameraAnomaly(visionReport: String): Boolean {
            val normalized = normalizeAscii(visionReport)
            if (normalized.isBlank()) return false

            if (normalized.startsWith("[normal]") || normalized == "normal") {
                return false
            }
            if (normalized.startsWith("[anomalia]")) {
                val afterTag = normalized.removePrefix("[anomalia]").trim()
                if (afterTag.isNotBlank() && !afterTag.startsWith("ninguna") && !afterTag.startsWith("no hay")) {
                    return true
                }
            }

            // Si el reporte describe explícitamente una escena tranquila o normal sin prefijo de alerta,
            // y no menciona intrusos, paquetes ni puertas abiertas, se considera normal.
            val ExplicitlyCalmScene = normalized.contains("escena tranquila") ||
                normalized.contains("entorno tranquilo") ||
                normalized.contains("calle tranquila") ||
                normalized.contains("todo tranquilo") ||
                normalized.contains("todo normal") ||
                normalized.contains("todo en orden") ||
                normalized.contains("sin anomalias") ||
                normalized.contains("ninguna anomalia") ||
                normalized.contains("no se observan anomalias") ||
                normalized.contains("sin actividad inusual")

            val highSeverityKeywords = listOf(
                "intruso", "desconocid", "paquete", "puerta abierta", "porton abierto",
                "cochera abierta", "humo", "fuego", "sospech", "intentando entrar", "tocando la puerta"
            )
            var cleaned = NEGATED_CLAUSE_REGEX.replace(normalized, " ")
            for (neg in NEGATIVE_CAMERA_PHRASES) {
                cleaned = cleaned.replace(neg, " ")
            }

            if (ExplicitlyCalmScene && highSeverityKeywords.none { cleaned.contains(it) }) {
                return false
            }

            return ANOMALY_CAMERA_KEYWORDS.any { cleaned.contains(it) }
        }

        /**
         * Selecciona dinámicamente las cámaras más importantes a revisar según las entidades del usuario
         * y el estado actual de Home Assistant (priorizando cámaras donde haya sensores activos y cámaras
         * perimetrales como entrada, jardín, patio, cochera).
         */
        fun selectImportantCamerasForScan(
            candidates: List<ProactiveCameraCandidate>,
            homeStatusSummary: String = "",
            maxCameras: Int = 3,
            rotationOffset: Int = 0
        ): List<ProactiveCameraCandidate> {
            val available = candidates.filter {
                it.state.lowercase(Locale.ROOT) !in listOf("unavailable", "unknown", "off")
            }
            if (available.isEmpty()) return emptyList()
            if (available.size <= maxCameras) return available

            val normalizedHomeAlerts = normalizeAscii(
                homeStatusSummary.lines()
                    .firstOrNull { it.trim().startsWith("Sensores y accesos activos", ignoreCase = true) }
                    .orEmpty()
            )
            val alertTokens = normalizedHomeAlerts
                .split(Regex("""[^a-z0-9]+"""))
                .filter { it.length >= 4 && it !in setOf("sensores", "accesos", "activos", "movimiento", "abierto", "abierta") }
                .toSet()

            data class ScoredCamera(
                val candidate: ProactiveCameraCandidate,
                val score: Int,
                val originalIndex: Int
            )

            val scored = available.mapIndexed { idx, cam ->
                val text = normalizeAscii("${cam.friendlyName} ${cam.entityId}")
                var score = 10
                if (alertTokens.isNotEmpty() && alertTokens.any { token -> text.contains(token) }) {
                    score += 100
                }
                if (PERIMETER_CAMERA_KEYWORDS.any { kw -> text.contains(kw) }) {
                    score += 60
                } else if (INDOOR_COMMON_KEYWORDS.any { kw -> text.contains(kw) }) {
                    score += 30
                }
                val rotatedOrder = (idx + rotationOffset.coerceAtLeast(0)) % available.size
                ScoredCamera(cam, score, rotatedOrder)
            }.sortedWith(compareByDescending<ScoredCamera> { it.score }.thenBy { it.originalIndex })

            return scored.take(maxCameras).map { it.candidate }
        }

        /**
         * Calcula de forma inteligente si conviene recordar tomar agua ahora mismo, evitando
         * recordatorios repetitivos cada 15 minutos y calculando el ritmo necesario antes de dormir (23:00).
         */
        fun evaluateHydrationPacing(
            currentGlasses: Int,
            targetGlasses: Int,
            lastWaterIntakeMs: Long,
            lastWaterReminderMs: Long,
            isWaterSnoozed: Boolean,
            nowMs: Long,
            hourOfDay: Int,
            minuteOfHour: Int = 0,
            force: Boolean = false
        ): HydrationPacingAssessment {
            val remaining = (targetGlasses - currentGlasses).coerceAtLeast(0)
            val currentMinuteOfDay = (hourOfDay.coerceIn(0, 23) * 60) + minuteOfHour.coerceIn(0, 59)
            val bedtimeMinuteOfDay = 23 * 60 // 23:00
            val minutesUntilBedtime = (bedtimeMinuteOfDay - currentMinuteOfDay).coerceAtLeast(30)
            val hoursUntilBedtime = (minutesUntilBedtime / 60.0 * 10.0).roundToInt() / 10.0

            val idealCadenceMinutes = if (remaining > 0) {
                (minutesUntilBedtime / remaining).coerceIn(60, 180)
            } else {
                180
            }

            // Curva esperada entre las 07:00 (420m) y las 23:00 (1380m) = 960 min de día activo
            val activeDayElapsedMin = (currentMinuteOfDay - 7 * 60).coerceIn(0, 960)
            val expectedByNow = ((activeDayElapsedMin / 960.0) * targetGlasses).roundToInt().coerceIn(0, targetGlasses)

            val minutesSinceLastGlass = if (lastWaterIntakeMs in 1..nowMs) {
                ((nowMs - lastWaterIntakeMs) / 60_000L).toInt().coerceAtLeast(0)
            } else {
                null
            }

            val minutesSinceLastReminder = if (lastWaterReminderMs in 1..nowMs) {
                ((nowMs - lastWaterReminderMs) / 60_000L).toInt().coerceAtLeast(0)
            } else {
                null
            }

            val minReminderGapMin = maxOf(90, (idealCadenceMinutes * 3) / 4)
            val recentlyDrank = minutesSinceLastGlass != null && minutesSinceLastGlass < 75
            val recentlyReminded = minutesSinceLastReminder != null && minutesSinceLastReminder < minReminderGapMin
            val dueByElapsed = minutesSinceLastGlass != null && minutesSinceLastGlass >= idealCadenceMinutes
            val behindSchedule = currentGlasses < expectedByNow && hourOfDay >= 10

            val shouldRemind = when {
                remaining <= 0 -> false
                isWaterSnoozed && !force -> false
                force -> true
                hourOfDay < 8 || hourOfDay >= 23 -> false
                recentlyDrank -> false
                recentlyReminded -> false
                dueByElapsed || behindSchedule -> true
                minutesSinceLastGlass == null && hourOfDay >= 12 && currentGlasses < targetGlasses -> true
                else -> false
            }

            val cadenceLabel = if (idealCadenceMinutes >= 120) {
                val h = idealCadenceMinutes / 60
                val m = idealCadenceMinutes % 60
                if (m >= 20) "${h} h ${m} min" else "${h} h"
            } else {
                "$idealCadenceMinutes min"
            }

            val lastDrinkSuffix = when {
                minutesSinceLastGlass == null -> ""
                minutesSinceLastGlass < 60 -> " (último hace $minutesSinceLastGlass min)"
                else -> " (último hace ${minutesSinceLastGlass / 60} h ${minutesSinceLastGlass % 60} min)"
            }

            val shortSummary = if (remaining > 0) {
                "Hidratación: $currentGlasses/$targetGlasses vasos$lastDrinkSuffix · faltan $remaining antes de dormir (~1 cada $cadenceLabel)"
            } else {
                "Hidratación completada: $currentGlasses/$targetGlasses vasos"
            }

            val spokenCoaching = if (remaining > 0) {
                val sincePhrase = when {
                    minutesSinceLastGlass == null -> "llevas $currentGlasses de $targetGlasses vasos de agua hoy"
                    minutesSinceLastGlass < 60 -> "llevas $currentGlasses de $targetGlasses vasos de agua y tomaste el último hace $minutesSinceLastGlass minutos"
                    else -> {
                        val h = minutesSinceLastGlass / 60
                        val m = minutesSinceLastGlass % 60
                        val timeStr = if (m >= 15) "$h horas y $m minutos" else "$h horas"
                        "llevas $currentGlasses de $targetGlasses vasos de agua y pasaron $timeStr desde tu último vaso"
                    }
                }
                val hoursStr = if (hoursUntilBedtime >= 2.0) "${hoursUntilBedtime.roundToInt()} horas" else "poco tiempo"
                "En hidratación, $sincePhrase; te faltan $remaining vasos en las próximas $hoursStr antes de dormir, aproximadamente uno cada $cadenceLabel."
            } else {
                "Ya cumpliste tu meta de hidratación de $targetGlasses vasos hoy."
            }

            return HydrationPacingAssessment(
                currentGlasses = currentGlasses,
                targetGlasses = targetGlasses,
                remainingGlasses = remaining,
                minutesSinceLastGlass = minutesSinceLastGlass,
                hoursUntilBedtime = hoursUntilBedtime,
                idealCadenceMinutes = idealCadenceMinutes,
                expectedGlassesByNow = expectedByNow,
                shouldRemind = shouldRemind,
                shortSummary = shortSummary,
                spokenCoaching = spokenCoaching
            )
        }
    }

    /**
     * Evalúa todas las fuentes configuradas y genera un [ProactiveInsight] cuando realmente
     * aporta valor al usuario (o cuando [force] es verdadero).
     *
     * Regla de oro para cámaras: si las cámaras no presentan ninguna anomalía real,
     * se omiten por completo del reporte ("si no ve nada anormal que se lo ahorre").
     */
    suspend fun evaluateNow(
        includeCameraScan: Boolean,
        force: Boolean = false,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        assistantName: String = "Treko",
        minuteOfHour: Int = Calendar.getInstance().get(Calendar.MINUTE),
        /** Only the camera sentinel (run when a detector saw something); no other topics. */
        camerasOnly: Boolean = false
    ): ProactiveInsight? {
        val now = clockMs()
        val cleanAssistantName = assistantName.trim().ifBlank { "Treko" }

        val homeSummary = runCatching { sources.getHomeStatusSummary() }.getOrDefault("")
        val cameraResults = if (includeCameraScan && (!isTopicSnoozed("cameras", now) || force)) {
            cameraRotationOffset = (cameraRotationOffset + 1) % 12
            runCatching { sources.scanPriorityCamerasForAnomalies(homeSummary) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val habitsSummary = runCatching { sources.getHabitsSummary() }.getOrDefault("")
        val tasksSummary = runCatching { sources.getTasksSummary() }.getOrDefault("")
        val memories = runCatching { sources.getVaultMemories() }.getOrDefault(emptyList())
        val activeTimers = runCatching { sources.getActiveTimersCount() }.getOrDefault(0)
        val lastWaterIntakeMs = runCatching { sources.getLastWaterIntakeMs() }
            .getOrDefault(0L)
            .takeIf { it > 0L } ?: parseLastWaterIntakeFromSummary(habitsSummary, now)

        val homeAlertsLine = extractSectionPrefix(homeSummary, "Sensores y accesos activos")
        val lightsOnLine = extractSectionPrefix(homeSummary, "Luces encendidas")
        val climateLine = extractSectionPrefix(homeSummary, "Ambiente")

        val pendingHabits = parseTaggedItems(habitsSummary, "[Pendiente")
        val completedHabitsCount = parseCompletedCount(habitsSummary)
        val waterProgress = parseWaterProgress(habitsSummary)
        val pendingTasks = parseTaggedItems(tasksSummary, "[Pendiente")

        // Prioridad 1: Anomalía visual REAL detectada en una o más cámaras.
        // Si no hay anomalía real, NO se menciona nada sobre las cámaras.
        val anomalousCameras = cameraResults.filter { it.hasAnomaly }
        if (anomalousCameras.isNotEmpty()) {
            val camNames = anomalousCameras.joinToString(", ") { it.cameraName }
            val combinedReport = anomalousCameras.joinToString(" | ") { cam ->
                val clean = cleanAnomalyPrefix(cam.visionReport).take(180)
                if (anomalousCameras.size > 1) "${cam.cameraName}: $clean" else clean
            }
            val primaryCam = anomalousCameras.first()
            val fp = "cam_anomaly:${primaryCam.entityId}:${combinedReport.take(60).lowercase()}"
            if (!force && isInCooldown(fp, now)) return null
            recordFingerprint(fp, now)

            val summary = "Alerta visual en $camNames: $combinedReport"
            val spokenSpeech = buildString {
                append("Atención. Revisé tus cámaras y detecté una anomalía en $camNames: ")
                append(combinedReport.removeSuffix("."))
                append(". ")
                if (!homeAlertsLine.isNullOrBlank()) {
                    append("Además, en Home Assistant aparece: ${homeAlertsLine.removeSuffix(".")}. ")
                }
                append("¿Quieres que abra el panel de cámaras o ejecutemos alguna acción en casa?")
            }
            val spokenPrompt = buildString {
                append("[MODO CENTINELA PROACTIVO] Acabas de detectar una anomalía real en cámara ($camNames): $combinedReport. ")
                if (!homeAlertsLine.isNullOrBlank()) {
                    append("Además en Home Assistant figura: $homeAlertsLine. ")
                }
                append("Conversa con el usuario en español sobre esta alerta y ayúdale con cualquier acción que solicite.")
            }
            return ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.CAMERA_ANOMALY,
                title = "Centinela · $camNames",
                summaryText = summary,
                spokenPrompt = spokenPrompt,
                fingerprint = fp,
                createdAtMs = now,
                spokenSpeechText = spokenSpeech
            ).also { recordDeliveredInsight(it) }
        }

        if (camerasOnly) return null

        // Evaluación inteligente de Casa (Home Assistant), Hábitos, Hidratación y Tareas
        val highlights = mutableListOf<String>()
        val spokenParts = mutableListOf<String>()
        val promptContext = mutableListOf<String>()

        // 1. Estado de Home Assistant (Sensores activos, puertas/ventanas, luces)
        val homeAllowed = !isTopicSnoozed("home", now) || force
        if (homeAllowed) {
            val homeStateHash = "${homeAlertsLine.orEmpty()}|${lightsOnLine.orEmpty()}"
            val homeChanged = topicLastStateHash["home"] != homeStateHash
            val homeLastMs = topicLastRemindedMs["home"] ?: 0L
            val canSurfaceHome = force || homeChanged || (now - homeLastMs) >= DEFAULT_COOLDOWN_MS

            if (canSurfaceHome) {
                if (!homeAlertsLine.isNullOrBlank()) {
                    highlights.add(homeAlertsLine)
                    spokenParts.add("En Home Assistant tienes ${homeAlertsLine.lowercase(Locale.getDefault())}.")
                    promptContext.add("Estado de Home Assistant: $homeAlertsLine.")
                    topicLastStateHash["home"] = homeStateHash
                    topicLastRemindedMs["home"] = now
                } else if (!lightsOnLine.isNullOrBlank() && (hourOfDay >= 21 || hourOfDay < 7 || force)) {
                    highlights.add(lightsOnLine)
                    spokenParts.add("En tu casa figuran ${lightsOnLine.lowercase(Locale.getDefault())}.")
                    promptContext.add("Iluminación actual en Home Assistant: $lightsOnLine.")
                    topicLastStateHash["home"] = homeStateHash
                    topicLastRemindedMs["home"] = now
                } else if (force && !climateLine.isNullOrBlank()) {
                    highlights.add(climateLine)
                    spokenParts.add("El estado ambiental de tu casa reporta: ${climateLine.removePrefix("Ambiente:").trim()}.")
                    promptContext.add("Ambiente del hogar: $climateLine.")
                }
            }
        }

        // 2. Tareas pendientes (con deduplicación para no repetir las mismas cada 15 min)
        val tasksAllowed = !isTopicSnoozed("tasks", now) || force
        if (tasksAllowed && pendingTasks.isNotEmpty()) {
            val tasksHash = pendingTasks.joinToString("|").lowercase(Locale.ROOT)
            val tasksChanged = topicLastStateHash["tasks"] != tasksHash
            val tasksLastMs = topicLastRemindedMs["tasks"] ?: 0L
            val canSurfaceTasks = force || tasksChanged || (now - tasksLastMs) >= HABITS_TASK_REPEAT_COOLDOWN_MS

            if (canSurfaceTasks) {
                val preview = pendingTasks.take(3).joinToString(", ")
                highlights.add("Tareas pendientes (${pendingTasks.size}): $preview")
                val extraCount = (pendingTasks.size - 3).coerceAtLeast(0)
                val extraPhrase = if (extraCount > 0) " y $extraCount más" else ""
                spokenParts.add("Tienes ${pendingTasks.size} ${if (pendingTasks.size == 1) "tarea pendiente" else "tareas pendientes"}: $preview$extraPhrase.")
                promptContext.add("Tareas pendientes del usuario (${pendingTasks.size}): ${pendingTasks.take(4).joinToString("; ")}.")
                topicLastStateHash["tasks"] = tasksHash
                topicLastRemindedMs["tasks"] = now
            }
        }

        // 3. Hábitos pendientes y cumplidos (con enfriamiento inteligente)
        val habitsAllowed = !isTopicSnoozed("habits", now) || force
        if (habitsAllowed && (pendingHabits.isNotEmpty() || (completedHabitsCount > 0 && force))) {
            val habitsHash = "$completedHabitsCount|${pendingHabits.joinToString("|").lowercase(Locale.ROOT)}"
            val habitsChanged = topicLastStateHash["habits"] != habitsHash
            val habitsLastMs = topicLastRemindedMs["habits"] ?: 0L
            val canSurfaceHabits = force || (hourOfDay >= 10 && (habitsChanged || (now - habitsLastMs) >= HABITS_TASK_REPEAT_COOLDOWN_MS))

            if (canSurfaceHabits) {
                if (pendingHabits.isNotEmpty()) {
                    val preview = pendingHabits.take(3).joinToString(", ")
                    val donePrefix = if (completedHabitsCount > 0) "$completedHabitsCount cumplidos, " else ""
                    highlights.add("Hábitos (${donePrefix}faltan ${pendingHabits.size}): $preview")
                    val doneSpoken = if (completedHabitsCount > 0) "Ya cumpliste $completedHabitsCount hábitos hoy y te " else "Te "
                    spokenParts.add("${doneSpoken}quedan ${pendingHabits.size} hábitos por completar: $preview.")
                    promptContext.add("Hábitos de hoy: $completedHabitsCount completados y ${pendingHabits.size} pendientes (${pendingHabits.take(4).joinToString("; ")}).")
                } else if (completedHabitsCount > 0 && force) {
                    highlights.add("Hábitos al día ($completedHabitsCount completados hoy)")
                    spokenParts.add("Ya completaste todos tus $completedHabitsCount hábitos de hoy.")
                    promptContext.add("El usuario ya cumplió sus $completedHabitsCount hábitos hoy.")
                }
                topicLastStateHash["habits"] = habitsHash
                topicLastRemindedMs["habits"] = now
            }
        }

        // 4. Hidratación inteligente (calculando ritmo hacia la hora de dormir y tiempo desde el último vaso)
        if (waterProgress != null) {
            val assessment = evaluateHydrationPacing(
                currentGlasses = waterProgress.first,
                targetGlasses = waterProgress.second,
                lastWaterIntakeMs = lastWaterIntakeMs,
                lastWaterReminderMs = topicLastRemindedMs["water"] ?: 0L,
                isWaterSnoozed = isTopicSnoozed("water", now),
                nowMs = now,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                force = force
            )
            if (assessment.shouldRemind) {
                highlights.add(assessment.shortSummary)
                spokenParts.add(assessment.spokenCoaching)
                promptContext.add(assessment.spokenCoaching)
                topicLastRemindedMs["water"] = now
            }
        }

        // 5. Temporizadores activos
        if (activeTimers > 0) {
            highlights.add("Temporizadores en curso: $activeTimers")
            spokenParts.add("Tienes $activeTimers ${if (activeTimers == 1) "temporizador activo" else "temporizadores activos"} en curso.")
            promptContext.add("Hay $activeTimers temporizadores activos en curso.")
        }

        // 6. Memoria relevante de la bóveda (solo si no hay otros pendientes o en chequeo completo)
        val featuredMemory = memories.firstOrNull { (k, v) -> k.isNotBlank() && v.isNotBlank() }
        if (featuredMemory != null) {
            promptContext.add("Memoria guardada relevante (${featuredMemory.first}): ${featuredMemory.second}.")
            if (highlights.isEmpty()) {
                highlights.add("Nota en memoria (${featuredMemory.first}): ${featuredMemory.second.take(80)}")
                spokenParts.add("Recordé tu nota sobre ${featuredMemory.first}: ${featuredMemory.second}.")
            }
        }

        if (highlights.isEmpty()) {
            if (!force) return null
            val fallbackSummary = "Hogar en orden, hábitos y tareas al día."
            val fallbackSpeech = "Hola. Revisé Home Assistant, tus hábitos y tareas: todo está en orden y estás al día. ¿En qué te puedo ayudar?"
            val fallbackPrompt = "[ASISTENTE PROACTIVO] Acabas de revisar Home Assistant, hábitos y tareas. Todo está en orden. Conversa brevemente con el usuario en español."
            return ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.DAILY_BRIEFING,
                title = "Resumen proactivo",
                summaryText = fallbackSummary,
                spokenPrompt = fallbackPrompt,
                fingerprint = "briefing:all_clear:$now",
                createdAtMs = now,
                spokenSpeechText = fallbackSpeech
            ).also { recordDeliveredInsight(it) }
        }

        val hasHome = !homeAlertsLine.isNullOrBlank() || (!lightsOnLine.isNullOrBlank() && highlights.any { it.startsWith("Luces", ignoreCase = true) })
        val category = when {
            hasHome -> ProactiveCategory.HOME_STATUS
            pendingTasks.isNotEmpty() && highlights.any { it.startsWith("Tareas", ignoreCase = true) } -> ProactiveCategory.TASKS_AND_REMINDERS
            highlights.any { it.startsWith("Hábitos", ignoreCase = true) || it.startsWith("Hidratación", ignoreCase = true) } -> ProactiveCategory.HABITS_AND_WATER
            featuredMemory != null && highlights.size == 1 -> ProactiveCategory.MEMORY_FOLLOWUP
            else -> ProactiveCategory.DAILY_BRIEFING
        }

        val activeSectionsCount = listOf(
            hasHome,
            highlights.any { it.startsWith("Tareas", ignoreCase = true) },
            highlights.any { it.startsWith("Hábitos", ignoreCase = true) || it.startsWith("Hidratación", ignoreCase = true) }
        ).count { it }

        val title = if (activeSectionsCount >= 2) {
            "Estado de casa y pendientes"
        } else {
            when (category) {
                ProactiveCategory.HOME_STATUS -> "Estado del hogar"
                ProactiveCategory.TASKS_AND_REMINDERS -> "Tus tareas pendientes"
                ProactiveCategory.HABITS_AND_WATER -> "Hábitos e hidratación"
                ProactiveCategory.MEMORY_FOLLOWUP -> "Recordatorio de memoria"
                ProactiveCategory.DAILY_BRIEFING -> "Resumen proactivo"
                ProactiveCategory.CAMERA_ANOMALY -> "Centinela"
            }
        }

        val summaryText = highlights.take(4).joinToString(" · ")
        val fp = "${category.name}:${highlights.take(3).joinToString("|").lowercase(Locale.ROOT)}"
        if (!force && isInCooldown(fp, now)) return null
        recordFingerprint(fp, now)

        val closingQuestion = when {
            hasHome && (pendingHabits.isNotEmpty() || pendingTasks.isNotEmpty()) ->
                "¿Quieres que ajustemos algo en casa, registremos algún hábito o revisemos tus tareas?"
            hasHome ->
                "¿Deseas que controle algún dispositivo de tu casa?"
            pendingHabits.isNotEmpty() || waterProgress != null ->
                "¿Quieres que marque algún hábito como cumplido, registre un vaso de agua o lo posponga para más tarde?"
            pendingTasks.isNotEmpty() ->
                "¿Quieres que marquemos alguna tarea como completada o agreguemos un recordatorio?"
            else ->
                "¿Te gustaría que te ayude con algo de esto ahora?"
        }

        val spokenSpeechText = buildString {
            append("Hola. ")
            append(spokenParts.joinToString(" "))
            append(" ")
            append(closingQuestion)
        }

        val spokenPrompt = buildString {
            append("[ASISTENTE PROACTIVO] Eres $cleanAssistantName. Acabas de entregarle al usuario este resumen proactivo por voz: \"$spokenSpeechText\". ")
            append("Contexto detallado: ")
            append(promptContext.joinToString(" "))
            append(" Si el usuario pide posponer un recordatorio usa 'manage_proactive_assistant' con action='snooze'; si pide registrar agua o hábitos usa 'manage_habits'; si pide completar tareas usa 'manage_tasks'; si pide controlar dispositivos usa las herramientas de Home Assistant.")
        }

        return ProactiveInsight(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            summaryText = summaryText,
            spokenPrompt = spokenPrompt,
            fingerprint = fp,
            createdAtMs = now,
            spokenSpeechText = spokenSpeechText
        ).also { recordDeliveredInsight(it) }
    }

    private fun cleanAnomalyPrefix(report: String): String =
        report.trim()
            .replace(Regex("""^\[?ANOMAL[IÍ]A\]?\s*:?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun isInCooldown(fingerprint: String, nowMs: Long): Boolean {
        val last = recentFingerprints[fingerprint] ?: return false
        return (nowMs - last) < DEFAULT_COOLDOWN_MS
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

    private fun parseLastWaterIntakeFromSummary(habitsSummary: String, nowMs: Long): Long {
        val match = Regex("""[uú]ltimo\s+vaso\s+hace\s+(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(habitsSummary) ?: return 0L
        val mins = match.groupValues[1].toLongOrNull() ?: return 0L
        return (nowMs - mins * 60_000L).coerceAtLeast(1L)
    }
}
