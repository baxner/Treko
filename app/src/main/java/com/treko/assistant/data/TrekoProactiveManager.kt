package com.treko.assistant.data

import java.util.Calendar
import kotlin.coroutines.coroutineContext
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
    /** Entity the vision model looked at (the camera or its snapshot sibling). */
    val entityId: String,
    val visionReport: String,
    val hasAnomaly: Boolean,
    /** The camera itself (Home Assistant camera group's main entity), when known. */
    val cameraEntityId: String? = null,
    /** The structured verdict, when the model answered with one (else [hasAnomaly] came from keywords). */
    val verdict: VisionVerdict? = null,
    /** Detector label that started the look (person, car…), when there was a detector. */
    val label: String? = null
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
    val spokenSpeechText: String = summaryText,
    /** Camera alerts: the cameras where something was seen (main camera entity), first = primary. */
    val cameraEntityIds: List<String> = emptyList(),
    /** Camera alerts: their names, in the same order. */
    val cameraNames: List<String> = emptyList(),
    /** Camera alerts: what the vision model reported. */
    val visionReport: String? = null,
    /** Entry of the alerts log for this insight, once it was recorded. */
    val alertId: Long? = null,
    /** Camera alerts: the vision model's level ("info", "atencion", "urgente"), when it gave one. */
    val visionLevel: String? = null
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
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Words around camera alerts, in the user's language (the service passes its string resources). */
    private val cameraTexts: () -> CameraAlertTexts = { CameraAlertTexts.SPANISH }
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

        /** Sentinel decision when the vision model gave no answer (error, timeout, no picture). */
        const val DECISION_NO_VERDICT = "no_verdict"

        /** Same camera alert (camera + label + level) is not repeated for this long; urgent ones always are. */
        private const val CAMERA_ALERT_REPEAT_COOLDOWN_MS = 20L * 60_000L

        /** Follow-up context for a briefing (facts only, never the spoken text again). */
        const val BRIEFING_FOLLOWUP_PROMPT =
            "[RESUMEN PROACTIVO] Datos por si pregunta: %1\$s " +
                "Herramientas: posponer → manage_proactive_assistant action='snooze'; agua o hábitos → manage_habits; tareas → manage_tasks; aparatos → herramientas de Home Assistant."

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
            "caminando por la banqueta",
            "caminando por la calle",
            "por la banqueta",
            "pasa caminando",
            "no anomaly",
            "nothing unusual",
            "all clear"
        )

        // A person, "alguien", a box or a delivery alone is an ordinary scene (Frigate already saw
        // a person): only what makes it worth an alert counts.
        private val ANOMALY_CAMERA_KEYWORDS = listOf(
            "anomal",
            "intruso",
            "desconocid",
            "esperando en la puerta",
            "frente a la puerta",
            "tocando la puerta",
            "paquete",
            "intentando entrar",
            "intentando abrir",
            "forzando",
            "trepando",
            "puerta abierta",
            "porton abierto",
            "ventana abierta",
            "cochera abierta",
            "sospech",
            "humo",
            "fuego",
            "incendio",
            "fuga",
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

        /** "1 hora", "2 horas". */
        internal fun countPhrase(n: Int, singular: String, plural: String): String =
            "$n ${if (n == 1) singular else plural}"

        internal const val TOPIC_HOME = "home"
        internal const val TOPIC_TASKS = "tasks"
        internal const val TOPIC_HABITS = "habits"
        internal const val TOPIC_WATER = "water"
        internal const val TOPIC_MEMORY = "memory"

        private val ACCESS_ITEM_REGEX = Regex("""[^,()]+\([^()]*\)""")

        /**
         * The "Sensores y accesos activos (n): A (abierto), B (movimiento)…" line of the home
         * summary without motion, occupancy and presence sensors. Null when nothing is left.
         */
        internal fun accessAlertsOnly(line: String?): String? {
            if (line.isNullOrBlank()) return null
            val body = line.substringAfter(':', "").trim().removeSuffix(".")
                .replace(Regex("""\s+y\s+\d+\s+más$"""), "")
            val items = ACCESS_ITEM_REGEX.findAll(body)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() && !it.endsWith("(movimiento)") }
                .toList()
            if (items.isEmpty()) return null
            return "Accesos abiertos (${items.size}): ${items.joinToString(", ")}"
        }

        /** "En casa sigue abierto o sin seguro: Puerta Jardín." from [accessAlertsOnly]. */
        internal fun homeAccessSpeech(accessLine: String): String {
            val names = ACCESS_ITEM_REGEX.findAll(accessLine.substringAfter(':', accessLine))
                .map { it.value.substringBefore('(').trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (names.isEmpty()) return "En casa hay accesos abiertos."
            val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " y " + names.last()
            return if (names.size == 1) "En casa sigue abierto o sin seguro: $joined."
            else "En casa siguen abiertos o sin seguro: $joined."
        }

        /** Closing question about the topics the briefing actually spoke of; null when none asks for one. */
        internal fun closingQuestion(spokenTopics: Set<String>): String? {
            val actions = buildList {
                if (TOPIC_HOME in spokenTopics) add("ajuste algo en casa")
                if (TOPIC_HABITS in spokenTopics) add("marque algún hábito")
                if (TOPIC_WATER in spokenTopics) add("registre un vaso de agua")
                if (TOPIC_TASKS in spokenTopics) add("marque alguna tarea como hecha")
            }
            if (actions.isEmpty()) return null
            val joined = if (actions.size == 1) actions[0] else actions.dropLast(1).joinToString(", ") + " o " + actions.last()
            return "¿Quieres que $joined?"
        }

        private val WEEKDAYS_ES = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")
        private val WEEKDAYS_EN = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
        private val MONTHS_ES = listOf(
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
        )
        private val MONTHS_EN = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )

        /**
         * A saved note is worth mentioning today only when it names today: the weekday
         * ("los martes…") or the date (21/11, 2026-11-21, "21 de noviembre", "november 21").
         */
        internal fun isMemoryRelevantToday(key: String, value: String, today: Calendar): Boolean {
            val text = " " + normalizeAscii("$key $value").replace('_', ' ') + " "
            val dow = today.get(Calendar.DAY_OF_WEEK) - 1
            val day = today.get(Calendar.DAY_OF_MONTH)
            val month = today.get(Calendar.MONTH)
            val year = today.get(Calendar.YEAR)
            val word = { w: String -> Regex("""(^|[^a-z])$w([^a-z]|$)""").containsMatchIn(text) }
            if (word(WEEKDAYS_ES[dow]) || word(WEEKDAYS_EN[dow])) return true
            val d = day.toString()
            val dd = d.padStart(2, '0')
            val m = (month + 1).toString()
            val mm = m.padStart(2, '0')
            val numeric = listOf(
                Regex("""(^|[^0-9])0?$d[/\-.]0?$m([^0-9]|$)"""),
                Regex("""(^|[^0-9])$year-$mm-$dd([^0-9]|$)""")
            )
            if (numeric.any { it.containsMatchIn(text) }) return true
            val named = listOf(
                Regex("""(^|[^0-9])0?$d\s+(de\s+)?${MONTHS_ES[month]}([^a-z]|$)"""),
                Regex("""(^|[^a-z])${MONTHS_EN[month]}\s+0?$d([^0-9]|$)"""),
                Regex("""(^|[^0-9])0?$d\s+(of\s+)?${MONTHS_EN[month]}([^a-z]|$)""")
            )
            return named.any { it.containsMatchIn(text) }
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

        private val NEGATIVE_TAG_START = listOf("ninguna", "ninguno", "no hay", "no se", "nada", "sin ", "none", "no ")

        private val NEGATED_CLAUSE_REGEX = Regex(
            """\b(?:no\s+se\s+observa[n]?|no\s+se\s+ve[n]?|no\s+detect[ao]n?|no\s+hay|sin|ningun[ao]?|ni)\s+(?:(?:una|un|el|la|los|las|de|a)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|actividad(?:es)?\s+inusual(?:es)?|(?:puertas?|porton(?:es)?|ventanas?|cocheras?)\s+abiert[ao]s?)(?:\s*(?:,|ni|o|y)\s*(?:(?:una|un|el|la|los|las|de|ningun[ao]?)\s+)?(?:anomal[a-z]*|personas?|gente|alguien|intrus[ao]s?|desconocid[ao]s?|paquetes?|cajas?|movimientos?|vehiculos?|coches?|autos?|novedad(?:es)?|actividad(?:es)?\s+inusual(?:es)?|(?:puertas?|porton(?:es)?|ventanas?|cocheras?)\s+abiert[ao]s?))*"""
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
                val afterTag = normalized.removePrefix("[anomalia]").trim().trimStart(':', '-', ' ')
                // "[ANOMALIA] ninguna" / "[ANOMALIA]: no hay nada" is the model saying "nothing".
                if (afterTag.isBlank() || NEGATIVE_TAG_START.any { afterTag.startsWith(it) }) return false
                return true
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
            var cleaned = NEGATED_CLAUSE_REGEX.replace(normalized.replace("[anomalia]", " "), " ")
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
                val glasses = "$currentGlasses de $targetGlasses ${if (targetGlasses == 1) "vaso" else "vasos"} de agua"
                val sincePhrase = when {
                    minutesSinceLastGlass == null -> "llevas $glasses hoy"
                    minutesSinceLastGlass < 60 ->
                        "llevas $glasses y tomaste el último hace ${countPhrase(minutesSinceLastGlass, "minuto", "minutos")}"
                    else -> {
                        val h = minutesSinceLastGlass / 60
                        val m = minutesSinceLastGlass % 60
                        val timeStr = if (m >= 15) {
                            "${countPhrase(h, "hora", "horas")} y ${countPhrase(m, "minuto", "minutos")}"
                        } else {
                            countPhrase(h, "hora", "horas")
                        }
                        "llevas $glasses y ${if (h == 1 && m < 15) "pasó" else "pasaron"} $timeStr desde tu último vaso"
                    }
                }
                "En hidratación, $sincePhrase; te ${if (remaining == 1) "falta 1" else "faltan $remaining"} antes de dormir."
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
    @Suppress("UNUSED_PARAMETER")
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
        // Present only in a camera-sentinel run: collects what happened for its log line.
        val trace = coroutineContext[SentinelRunTrace]
        val camerasSnoozed = isTopicSnoozed("cameras", now) && !force

        // The sentinel only looks at the camera(s): home, habits, tasks and memories are not loaded.
        if (camerasOnly) {
            if (!includeCameraScan || camerasSnoozed) {
                trace?.decide("skipped", if (!includeCameraScan) "camera scan is off" else "cameras topic snoozed")
                return null
            }
            val results = scanCameras(homeSummary = "", trace = trace)
            return when (val outcome = cameraAlert(results, now, force, trace)) {
                is CameraOutcome.Alert -> outcome.insight
                CameraOutcome.Suppressed -> null
                CameraOutcome.Nothing -> {
                    // No answer from vision is not an all-clear: nobody looked at the detection.
                    if (results.isEmpty()) {
                        trace?.decide(DECISION_NO_VERDICT, "vision gave no answer")
                    } else {
                        trace?.decide("normal", "no anomaly in the verdict")
                    }
                    null
                }
            }
        }

        val homeSummary = runCatching { sources.getHomeStatusSummary() }.getOrDefault("")
        val cameraResults = if (includeCameraScan && !camerasSnoozed) scanCameras(homeSummary, trace) else emptyList()
        // Prioridad 1: anomalía visual REAL en una o más cámaras. Si no hay, no se habla de cámaras.
        when (val outcome = cameraAlert(cameraResults, now, force, trace)) {
            is CameraOutcome.Alert -> return outcome.insight
            CameraOutcome.Suppressed -> return null
            CameraOutcome.Nothing -> Unit
        }

        val habitsSummary = runCatching { sources.getHabitsSummary() }.getOrDefault("")
        val tasksSummary = runCatching { sources.getTasksSummary() }.getOrDefault("")
        val memories = runCatching { sources.getVaultMemories() }.getOrDefault(emptyList())
        val activeTimers = runCatching { sources.getActiveTimersCount() }.getOrDefault(0)
        val lastWaterIntakeMs = runCatching { sources.getLastWaterIntakeMs() }
            .getOrDefault(0L)
            .takeIf { it > 0L } ?: parseLastWaterIntakeFromSummary(habitsSummary, now)

        // Motion, occupancy and presence sensors are not news (they toggle all day and the
        // cameras already cover people): only open doors, windows, covers and unlocked locks.
        val homeAccessLine = accessAlertsOnly(extractSectionPrefix(homeSummary, "Sensores y accesos activos"))
        val lightsOnLine = extractSectionPrefix(homeSummary, "Luces encendidas")
        val climateLine = extractSectionPrefix(homeSummary, "Ambiente")

        val pendingHabits = parseTaggedItems(habitsSummary, "[Pendiente")
        val completedHabitsCount = parseCompletedCount(habitsSummary)
        val waterProgress = parseWaterProgress(habitsSummary)
        val pendingTasks = parseTaggedItems(tasksSummary, "[Pendiente")

        // Evaluación de Casa (Home Assistant), Hábitos, Hidratación y Tareas
        val highlights = mutableListOf<String>()
        val spokenParts = mutableListOf<String>()
        val promptContext = mutableListOf<String>()
        /** What this briefing actually talks about (the closing question follows it). */
        val spokenTopics = LinkedHashSet<String>()

        // 1. Estado de Home Assistant (puertas/ventanas/cerraduras abiertas; luces de noche)
        val homeAllowed = !isTopicSnoozed("home", now) || force
        if (homeAllowed) {
            val lightsMatter = hourOfDay >= 21 || hourOfDay < 7 || force
            // Only what could be said counts as a change (lights toggling by day do not).
            val homeStateHash = "${homeAccessLine.orEmpty()}|${if (lightsMatter) lightsOnLine.orEmpty() else ""}"
            val homeChanged = topicLastStateHash["home"] != homeStateHash
            val homeLastMs = topicLastRemindedMs["home"] ?: 0L
            val canSurfaceHome = force || homeChanged || (now - homeLastMs) >= DEFAULT_COOLDOWN_MS

            if (canSurfaceHome) {
                if (!homeAccessLine.isNullOrBlank()) {
                    highlights.add(homeAccessLine)
                    spokenParts.add(homeAccessSpeech(homeAccessLine))
                    promptContext.add("Estado de Home Assistant: $homeAccessLine.")
                    spokenTopics += TOPIC_HOME
                    topicLastStateHash["home"] = homeStateHash
                    topicLastRemindedMs["home"] = now
                } else if (!lightsOnLine.isNullOrBlank() && lightsMatter) {
                    highlights.add(lightsOnLine)
                    spokenParts.add("En tu casa siguen ${lightsOnLine.lowercase(Locale.getDefault())}.")
                    promptContext.add("Iluminación actual en Home Assistant: $lightsOnLine.")
                    spokenTopics += TOPIC_HOME
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
                spokenTopics += TOPIC_TASKS
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
                    val doneSpoken = if (completedHabitsCount > 0) {
                        "Ya cumpliste ${countPhrase(completedHabitsCount, "hábito", "hábitos")} hoy y te "
                    } else {
                        "Te "
                    }
                    val left = if (pendingHabits.size == 1) "queda 1 hábito" else "quedan ${pendingHabits.size} hábitos"
                    spokenParts.add("${doneSpoken}$left por completar: $preview.")
                    promptContext.add("Hábitos de hoy: $completedHabitsCount completados y ${pendingHabits.size} pendientes (${pendingHabits.take(4).joinToString("; ")}).")
                    spokenTopics += TOPIC_HABITS
                } else if (completedHabitsCount > 0 && force) {
                    highlights.add("Hábitos al día ($completedHabitsCount completados hoy)")
                    spokenParts.add(
                        if (completedHabitsCount == 1) "Ya completaste tu hábito de hoy."
                        else "Ya completaste tus $completedHabitsCount hábitos de hoy."
                    )
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
                if (assessment.remainingGlasses > 0) spokenTopics += TOPIC_WATER
                topicLastRemindedMs["water"] = now
            }
        }

        // 5. Temporizadores activos
        if (activeTimers > 0) {
            highlights.add("Temporizadores en curso: $activeTimers")
            spokenParts.add("Tienes $activeTimers ${if (activeTimers == 1) "temporizador activo" else "temporizadores activos"} en curso.")
            promptContext.add("Hay $activeTimers temporizadores activos en curso.")
        }

        // 6. Una nota de la bóveda solo si es de hoy (menciona la fecha o el día de la semana),
        // una vez al día; nunca una nota cualquiera.
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val dayKey = "${today.get(Calendar.YEAR)}-${today.get(Calendar.DAY_OF_YEAR)}"
        val relevantMemory = memories.firstOrNull { (k, v) ->
            k.isNotBlank() && v.isNotBlank() && isMemoryRelevantToday(k, v, today) &&
                topicLastStateHash["memory:$k"] != dayKey
        }
        if (relevantMemory != null) {
            val label = relevantMemory.first.replace('_', ' ').trim()
            highlights.add("Nota en memoria ($label): ${relevantMemory.second.take(80)}")
            spokenParts.add("Recuerda tu nota sobre $label: ${relevantMemory.second.trim().removeSuffix(".")}.")
            promptContext.add("Nota guardada para hoy ($label): ${relevantMemory.second}.")
            spokenTopics += TOPIC_MEMORY
            topicLastStateHash["memory:${relevantMemory.first}"] = dayKey
        }

        if (highlights.isEmpty()) {
            if (!force) return null
            val fallbackSummary = "Hogar en orden, hábitos y tareas al día."
            val fallbackSpeech = "Revisé tu casa, tus hábitos y tus tareas: todo está en orden."
            return ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.DAILY_BRIEFING,
                title = "Resumen proactivo",
                summaryText = fallbackSummary,
                spokenPrompt = String.format(Locale.ROOT, BRIEFING_FOLLOWUP_PROMPT, "todo en orden."),
                fingerprint = "briefing:all_clear:$now",
                createdAtMs = now,
                spokenSpeechText = fallbackSpeech
            ).also { recordDeliveredInsight(it) }
        }

        val category = when {
            TOPIC_HOME in spokenTopics -> ProactiveCategory.HOME_STATUS
            TOPIC_TASKS in spokenTopics -> ProactiveCategory.TASKS_AND_REMINDERS
            TOPIC_HABITS in spokenTopics || TOPIC_WATER in spokenTopics ||
                highlights.any { it.startsWith("Hábitos", ignoreCase = true) || it.startsWith("Hidratación", ignoreCase = true) } ->
                ProactiveCategory.HABITS_AND_WATER
            TOPIC_MEMORY in spokenTopics && highlights.size == 1 -> ProactiveCategory.MEMORY_FOLLOWUP
            else -> ProactiveCategory.DAILY_BRIEFING
        }

        val activeSectionsCount = listOf(
            TOPIC_HOME in spokenTopics,
            TOPIC_TASKS in spokenTopics,
            TOPIC_HABITS in spokenTopics || TOPIC_WATER in spokenTopics
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

        val spokenSpeechText = SpokenText.stripEmoji(
            listOfNotNull(spokenParts.joinToString(" "), closingQuestion(spokenTopics)).joinToString(" ")
        )

        return ProactiveInsight(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            summaryText = summaryText,
            spokenPrompt = String.format(Locale.ROOT, BRIEFING_FOLLOWUP_PROMPT, promptContext.joinToString(" ")),
            fingerprint = fp,
            createdAtMs = now,
            spokenSpeechText = spokenSpeechText
        ).also { recordDeliveredInsight(it) }
    }

    private sealed class CameraOutcome {
        class Alert(val insight: ProactiveInsight) : CameraOutcome()
        /** The same alert was already given a moment ago. */
        object Suppressed : CameraOutcome()
        object Nothing : CameraOutcome()
    }

    private suspend fun scanCameras(homeSummary: String, trace: SentinelRunTrace?): List<CameraAnomalyCheckResult> {
        cameraRotationOffset = (cameraRotationOffset + 1) % 12
        val scanStartMs = System.currentTimeMillis()
        return runCatching { sources.scanPriorityCamerasForAnomalies(homeSummary) }
            .onFailure { trace?.decide("error", "camera scan failed: ${it.message}") }
            .getOrDefault(emptyList())
            .also { trace?.stage("scan", System.currentTimeMillis() - scanStartMs) }
    }

    /**
     * The camera alert, if a camera showed something worth it. The alert says the vision
     * model's own short sentence ("aviso"), nothing more; the same camera + label + level is
     * not repeated within [CAMERA_ALERT_REPEAT_COOLDOWN_MS] (urgent ones always are).
     */
    private fun cameraAlert(
        results: List<CameraAnomalyCheckResult>,
        now: Long,
        force: Boolean,
        trace: SentinelRunTrace?
    ): CameraOutcome {
        val anomalous = results.filter { it.hasAnomaly }
        if (anomalous.isEmpty()) return CameraOutcome.Nothing
        val primary = anomalous.first()
        val level = primary.verdict?.level
        val label = primary.label ?: primary.verdict?.mainObject ?: "?"
        val fp = "cam_anomaly:${primary.cameraEntityId ?: primary.entityId}:$label:${level?.key ?: "texto"}"
        if (!force && level != VisionLevel.URGENT && isInCooldown(fp, now, CAMERA_ALERT_REPEAT_COOLDOWN_MS)) {
            trace?.decide("suppressed", "same alert already given (camera, label and level)")
            return CameraOutcome.Suppressed
        }
        recordFingerprint(fp, now)
        trace?.decide("alert")

        val texts = cameraTexts()
        val camNames = anomalous.joinToString(", ") { it.cameraName }
        val speech = SpokenText.stripEmoji(anomalous.take(2).joinToString(" ") { spokenAlertOf(it, texts) })
        val report = anomalous.joinToString(" | ") { cam ->
            val d = describeLook(cam, texts)
            if (anomalous.size > 1) "${cam.cameraName}: $d" else d
        }
        val levels = anomalous.mapNotNull { it.verdict?.level?.key }.distinct().joinToString("/").ifBlank { texts.noLevel }
        return CameraOutcome.Alert(
            ProactiveInsight(
                id = UUID.randomUUID().toString(),
                category = ProactiveCategory.CAMERA_ANOMALY,
                title = "Centinela · $camNames",
                summaryText = speech,
                spokenPrompt = String.format(Locale.ROOT, texts.followupPrompt, camNames, levels, report),
                fingerprint = fp,
                createdAtMs = now,
                spokenSpeechText = speech,
                cameraEntityIds = anomalous.map { it.cameraEntityId ?: it.entityId },
                cameraNames = anomalous.map { it.cameraName },
                visionReport = report,
                visionLevel = level?.key
            ).also { recordDeliveredInsight(it) }
        )
    }

    /**
     * The model's short sentence, or "En <camera>: <what it saw>." built from the verdict's
     * fields (also when the sentence reassures at an attention/urgent level, see
     * [VisionVerdict.spokenLine]). The raw answer is used only when the model gave no
     * structured verdict (with one, the raw answer is JSON and is never spoken).
     */
    private fun spokenAlertOf(cam: CameraAnomalyCheckResult, texts: CameraAlertTexts): String {
        val verdict = cam.verdict
        if (verdict != null && verdict.spokenAlert.isNotBlank()) return verdict.spokenLine(cam.cameraName, texts)
        val seenText = if (verdict != null) verdict.fallbackLine(texts) else cleanAnomalyPrefix(cam.visionReport)
        val seen = SpokenText.limitWords(firstSentence(seenText), 15)
        return String.format(Locale.ROOT, texts.fallbackSpeech, cam.cameraName, seen.replaceFirstChar { it.lowercase(Locale.getDefault()) })
    }

    private fun describeLook(cam: CameraAnomalyCheckResult, texts: CameraAlertTexts): String =
        cam.verdict?.fallbackLine(texts)
            ?: SpokenText.limitWords(cleanAnomalyPrefix(cam.visionReport), 40)

    private fun cleanAnomalyPrefix(report: String): String =
        report.trim()
            .replace(Regex("""^\[?ANOMAL[IÍ]A\]?\s*:?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun isInCooldown(fingerprint: String, nowMs: Long, cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        val last = recentFingerprints[fingerprint] ?: return false
        return (nowMs - last) < cooldownMs
    }

    private fun firstSentence(text: String): String {
        val t = text.trim()
        val end = Regex("""[.!?](\s|$)""").find(t)?.range?.first ?: return t
        return t.substring(0, end + 1)
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
