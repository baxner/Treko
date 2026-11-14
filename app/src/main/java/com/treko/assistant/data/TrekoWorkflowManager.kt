package com.treko.assistant.data

import android.content.Context
import java.text.Normalizer
import java.util.Locale

data class WorkflowStep(
    val type: String,
    val target: String = "",
    val action: String = "",
    val value: String = "",
    val query: String = "",
    val device: String = "",
    val mediaType: String = "auto",
    val provider: String = "auto",
    val message: String = "",
    val scene: String = "",
    val brightness: Int = 0,
    val color: String = "",
    val source: String = "",
    val minutes: Int = 0,
    val seconds: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    val live: Boolean = false
)

data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val steps: List<WorkflowStep>,
    val isBuiltIn: Boolean = false
)

interface WorkflowActions {
    suspend fun haControl(
        target: String,
        action: String,
        brightness: Int = 0,
        color: String = "",
        source: String = ""
    ): String

    suspend fun playMedia(
        query: String,
        device: String = "",
        mediaType: String = "auto",
        provider: String = "auto"
    ): String

    suspend fun broadcast(message: String, target: String = ""): String
    suspend fun triggerScene(scene: String): String
    suspend fun openCameraWall(live: Boolean = false): String
    suspend fun setCinemaMode(enabled: Boolean): String
    suspend fun startTimer(minutes: Int, seconds: Int = 0, label: String = ""): String
    suspend fun getWeather(): String
    suspend fun getHomeSummary(): String
    suspend fun getTasksSummary(): String
    suspend fun getHabitsSummary(): String
}

interface WorkflowStorage {
    fun loadJson(): String?
    fun saveJson(json: String)
}

class InMemoryWorkflowStorage(initialJson: String? = null) : WorkflowStorage {
    @Volatile
    private var data: String? = initialJson

    override fun loadJson(): String? = data

    override fun saveJson(json: String) {
        data = json
    }
}

private class SharedPreferencesWorkflowStorage(context: Context) : WorkflowStorage {
    private val prefs = context.applicationContext.getSharedPreferences(
        "treko_workflows_store",
        Context.MODE_PRIVATE
    )

    override fun loadJson(): String? = prefs.getString("custom_workflows_json", null)

    override fun saveJson(json: String) {
        prefs.edit().putString("custom_workflows_json", json).apply()
    }
}

class TrekoWorkflowManager(private val storage: WorkflowStorage) {

    constructor(context: Context) : this(SharedPreferencesWorkflowStorage(context))

    private val lock = Any()
    private val customWorkflows = mutableListOf<WorkflowDefinition>()

    init {
        synchronized(lock) {
            loadCustomWorkflowsLocked()
        }
    }

    companion object {
        val SUPPORTED_STEP_TYPES = setOf(
            "ha_control",
            "play_media",
            "broadcast",
            "scene",
            "camera_wall",
            "cinema_mode",
            "timer",
            "weather",
            "home_summary",
            "tasks_summary",
            "habits_summary"
        )

        private val STEP_TYPE_ALIASES = mapOf(
            "ha" to "ha_control",
            "home_assistant_control" to "ha_control",
            "device" to "ha_control",
            "light" to "ha_control",
            "play_music_or_radio" to "play_media",
            "music" to "play_media",
            "radio" to "play_media",
            "media" to "play_media",
            "home_assistant_broadcast" to "broadcast",
            "announce" to "broadcast",
            "tts" to "broadcast",
            "trigger_scene" to "scene",
            "view_all_cameras" to "camera_wall",
            "cameras" to "camera_wall",
            "multicam" to "camera_wall",
            "cinema" to "cinema_mode",
            "manage_timer" to "timer",
            "pomodoro" to "timer",
            "get_weather" to "weather",
            "home_status_summary" to "home_summary",
            "summary" to "home_summary",
            "tasks" to "tasks_summary",
            "habits" to "habits_summary"
        )

        private val STOP_WORDS = setOf(
            "de", "del", "la", "el", "los", "las", "un", "una", "para", "con", "en", "y",
            "rutina", "modo", "escena", "workflow", "flujo",
            "activar", "ejecutar", "iniciar", "poner", "pon", "hacer"
        )

        val BUILT_IN_WORKFLOWS: List<WorkflowDefinition> = listOf(
            WorkflowDefinition(
                id = "buenos_dias",
                name = "Buenos días",
                description = "Desactiva el modo cine, consulta clima, tareas, hábitos y estado del hogar, y pone música alegre de mañana.",
                aliases = listOf("buenos dias", "rutina matutina", "rutina de la manana", "despertar", "empezar el dia"),
                steps = listOf(
                    WorkflowStep(type = "cinema_mode", enabled = false),
                    WorkflowStep(type = "weather"),
                    WorkflowStep(type = "tasks_summary"),
                    WorkflowStep(type = "habits_summary"),
                    WorkflowStep(type = "home_summary"),
                    WorkflowStep(
                        type = "play_media",
                        query = "música alegre para empezar el día",
                        mediaType = "music",
                        provider = "auto"
                    )
                ),
                isBuiltIn = true
            ),
            WorkflowDefinition(
                id = "buenas_noches",
                name = "Buenas noches",
                description = "Apaga luces y televisor, envía un aviso de descanso en los altavoces y revisa el estado de la casa.",
                aliases = listOf("buenas noches", "modo noche", "a dormir", "hora de dormir", "rutina nocturna"),
                steps = listOf(
                    WorkflowStep(type = "ha_control", target = "luces", action = "turn_off"),
                    WorkflowStep(type = "ha_control", target = "tele", action = "turn_off"),
                    WorkflowStep(type = "broadcast", message = "Buenas noches, hora de descansar.", target = "todas"),
                    WorkflowStep(type = "home_summary")
                ),
                isBuiltIn = true
            ),
            WorkflowDefinition(
                id = "modo_cine",
                name = "Modo cine",
                description = "Activa el modo cine en Treko, activa escena cine o apaga la luz de la sala y enciende el televisor.",
                aliases = listOf("modo cine", "cine", "ver pelicula", "pelicula", "noche de cine"),
                steps = listOf(
                    WorkflowStep(type = "cinema_mode", enabled = true),
                    WorkflowStep(type = "scene", scene = "cine"),
                    WorkflowStep(type = "ha_control", target = "luz de la sala", action = "turn_off"),
                    WorkflowStep(type = "ha_control", target = "tele", action = "turn_on")
                ),
                isBuiltIn = true
            ),
            WorkflowDefinition(
                id = "vigilancia_total",
                name = "Vigilancia total",
                description = "Abre el muro multicámara de seguridad en vivo y muestra un resumen de sensores, puertas y cámaras.",
                aliases = listOf("vigilancia total", "vigilancia", "seguridad", "modo seguridad", "camaras", "todas las camaras", "revisar casa"),
                steps = listOf(
                    WorkflowStep(type = "camera_wall", live = true),
                    WorkflowStep(type = "home_summary")
                ),
                isBuiltIn = true
            ),
            WorkflowDefinition(
                id = "concentracion",
                name = "Concentración",
                description = "Activa modo cine para evitar interrupciones, inicia un Pomodoro de 25 min, música lo-fi y lista de tareas.",
                aliases = listOf("concentracion", "enfoque", "modo enfoque", "pomodoro", "estudiar", "trabajar", "deep work"),
                steps = listOf(
                    WorkflowStep(type = "cinema_mode", enabled = true),
                    WorkflowStep(type = "timer", minutes = 25, label = "Concentración Pomodoro"),
                    WorkflowStep(
                        type = "play_media",
                        query = "música lo-fi instrumental para concentrarse",
                        mediaType = "music",
                        provider = "auto"
                    ),
                    WorkflowStep(type = "tasks_summary")
                ),
                isBuiltIn = true
            )
        )

        internal fun normalizeSpanish(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val noAccents = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            return noAccents
                .replace(Regex("[_\\-]+"), " ")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        internal fun slugify(name: String): String {
            return normalizeSpanish(name).replace(" ", "_").trim('_')
        }

        internal fun normalizeStepType(rawType: String): String? {
            val clean = normalizeSpanish(rawType).replace(" ", "_")
            if (clean in SUPPORTED_STEP_TYPES) return clean
            return STEP_TYPE_ALIASES[clean]
        }
    }

    fun listWorkflows(): List<WorkflowDefinition> {
        synchronized(lock) {
            return BUILT_IN_WORKFLOWS + customWorkflows.toList()
        }
    }

    fun listWorkflowsSummary(): String {
        val all = listWorkflows()
        val sb = StringBuilder("Rutinas disponibles (${all.size}):\n")
        for (wf in all) {
            val kind = if (wf.isBuiltIn) "integrada" else "personalizada"
            sb.append("• ${wf.name} [${wf.id}, $kind, ${wf.steps.size} pasos]: ${wf.description}\n")
        }
        return sb.toString().trim()
    }

    fun findWorkflow(nameOrId: String): WorkflowDefinition? {
        val query = nameOrId.trim()
        if (query.isBlank()) return null

        val normQuery = normalizeSpanish(query)
        val normSlug = slugify(query)
        if (normQuery.isBlank()) return null

        val all = listWorkflows()

        // 1. Exact ID or normalized name match
        all.firstOrNull {
            it.id.equals(query, ignoreCase = true) ||
                it.id == normSlug ||
                normalizeSpanish(it.name) == normQuery
        }?.let { return it }

        // 2. Exact normalized alias match
        all.firstOrNull { wf ->
            wf.aliases.any { alias -> normalizeSpanish(alias) == normQuery }
        }?.let { return it }

        // 3. Fuzzy substring & token overlap scoring against workflow names, IDs, and aliases
        val filteredTokens = normQuery.split(" ")
            .filter { it.isNotBlank() && it !in STOP_WORDS }
        val strippedQuery = filteredTokens.joinToString(" ")
        if (strippedQuery.isBlank()) return null
        val queryTokens = filteredTokens.filter { it.length >= 3 }.toSet()

        var bestWf: WorkflowDefinition? = null
        var bestScore = 0

        for (wf in all) {
            val normName = normalizeSpanish(wf.name)
            val normId = normalizeSpanish(wf.id)
            val normAliases = wf.aliases.map { normalizeSpanish(it) }
            val allForms = listOf(normName, normId) + normAliases

            var score = 0
            for (form in allForms) {
                val formStripped = form.split(" ")
                    .filter { it.isNotBlank() && it !in STOP_WORDS }
                    .joinToString(" ")
                    .ifBlank { form }
                if (form == strippedQuery || formStripped == strippedQuery) {
                    score = maxOf(score, 100)
                } else if ((formStripped.contains(strippedQuery) && strippedQuery.length >= 4) ||
                    (strippedQuery.contains(formStripped) && formStripped.length >= 4)
                ) {
                    score = maxOf(score, 65)
                }
            }

            if (queryTokens.isNotEmpty()) {
                val targetTokens = allForms
                    .flatMap { it.split(" ") }
                    .filter { it.length >= 3 && it !in STOP_WORDS }
                    .toSet()
                val overlap = queryTokens.count { qt ->
                    targetTokens.any { tt ->
                        tt == qt || (
                            (tt.startsWith(qt) || qt.startsWith(tt)) &&
                                minOf(tt.length, qt.length) * 10 >= maxOf(tt.length, qt.length) * 7
                            )
                    }
                }
                if (overlap > 0) {
                    score += overlap * 25
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestWf = wf
            }
        }

        return if (bestScore >= 25) bestWf else null
    }

    suspend fun runWorkflow(nameOrId: String, actions: WorkflowActions): String {
        val wf = findWorkflow(nameOrId)
            ?: return "No se encontró ninguna rutina que coincida con '$nameOrId'.\n${listWorkflowsSummary()}"

        val stepResults = mutableListOf<String>()
        for ((index, step) in wf.steps.withIndex()) {
            val outcome = try {
                executeStep(step, actions)
            } catch (e: Exception) {
                "Error en paso ${index + 1} (${step.type}): ${e.message ?: "fallo inesperado"}"
            }
            if (outcome.isNotBlank()) {
                stepResults.add("${index + 1}. $outcome")
            }
        }

        val header = "Rutina '${wf.name}' ejecutada (${wf.steps.size} pasos):"
        return if (stepResults.isEmpty()) {
            header
        } else {
            "$header\n${stepResults.joinToString("\n")}"
        }
    }

    private suspend fun executeStep(step: WorkflowStep, actions: WorkflowActions): String {
        return when (step.type) {
            "ha_control" -> {
                val target = step.target.ifBlank { step.device.ifBlank { step.value } }
                val action = step.action.ifBlank { "turn_on" }
                actions.haControl(
                    target = target,
                    action = action,
                    brightness = step.brightness,
                    color = step.color,
                    source = step.source
                )
            }
            "play_media" -> {
                val query = step.query.ifBlank { step.value.ifBlank { step.target } }
                val device = step.device.ifBlank { step.target.takeIf { query != step.target }.orEmpty() }
                actions.playMedia(
                    query = query,
                    device = device,
                    mediaType = step.mediaType.ifBlank { "auto" },
                    provider = step.provider.ifBlank { "auto" }
                )
            }
            "broadcast" -> {
                val message = step.message.ifBlank { step.value.ifBlank { step.query } }
                val target = step.target.ifBlank { step.device }
                actions.broadcast(message = message, target = target)
            }
            "scene" -> {
                val sceneName = step.scene.ifBlank { step.target.ifBlank { step.value } }
                actions.triggerScene(scene = sceneName)
            }
            "camera_wall" -> actions.openCameraWall(live = step.live)
            "cinema_mode" -> actions.setCinemaMode(enabled = step.enabled)
            "timer" -> {
                val mins = if (step.minutes <= 0 && step.seconds <= 0) 5 else step.minutes
                actions.startTimer(minutes = mins, seconds = step.seconds, label = step.label)
            }
            "weather" -> actions.getWeather()
            "home_summary" -> actions.getHomeSummary()
            "tasks_summary" -> actions.getTasksSummary()
            "habits_summary" -> actions.getHabitsSummary()
            else -> "Tipo de paso no soportado: ${step.type}"
        }
    }

    fun createCustomWorkflow(
        name: String,
        description: String = "",
        stepsJson: String
    ): String {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return "Error: el nombre de la rutina es obligatorio."
        }
        val slug = slugify(cleanName)
        if (slug.isBlank()) {
            return "Error: el nombre '$cleanName' no es válido."
        }

        val builtInCollision = BUILT_IN_WORKFLOWS.firstOrNull {
            it.id == slug || normalizeSpanish(it.name) == normalizeSpanish(cleanName)
        }
        if (builtInCollision != null) {
            return "Error: '${builtInCollision.name}' es una rutina integrada y no se puede sobrescribir. Usa otro nombre."
        }

        val parsedSteps = parseStepsJson(stepsJson)
        if (parsedSteps.isEmpty()) {
            return "Error: 'steps' debe ser un arreglo JSON válido con al menos un paso reconocido " +
                "(tipos válidos: ${SUPPORTED_STEP_TYPES.joinToString(", ")})."
        }

        val cleanDesc = description.trim().ifBlank {
            "Rutina personalizada con ${parsedSteps.size} paso(s)."
        }
        val newDef = WorkflowDefinition(
            id = slug,
            name = cleanName,
            description = cleanDesc,
            aliases = listOf(normalizeSpanish(cleanName)),
            steps = parsedSteps,
            isBuiltIn = false
        )

        synchronized(lock) {
            val existingIdx = customWorkflows.indexOfFirst { it.id == slug }
            val actionWord = if (existingIdx >= 0) {
                customWorkflows[existingIdx] = newDef
                "actualizada"
            } else {
                customWorkflows.add(newDef)
                "creada"
            }
            persistCustomWorkflowsLocked()
            return "Rutina personalizada '${newDef.name}' (${newDef.id}) $actionWord con ${newDef.steps.size} paso(s)."
        }
    }

    fun deleteWorkflow(nameOrId: String): String {
        val match = findWorkflow(nameOrId)
            ?: return "No se encontró ninguna rutina que coincida con '$nameOrId'."
        if (match.isBuiltIn) {
            return "La rutina integrada '${match.name}' no se puede eliminar."
        }
        synchronized(lock) {
            val removed = customWorkflows.removeAll { it.id == match.id }
            if (!removed) {
                return "No se encontró la rutina personalizada '${match.name}'."
            }
            persistCustomWorkflowsLocked()
            return "Rutina personalizada '${match.name}' eliminada correctamente."
        }
    }

    private fun loadCustomWorkflowsLocked() {
        customWorkflows.clear()
        val raw = storage.loadJson()?.trim().orEmpty()
        if (raw.isBlank()) return
        try {
            val parsed = SimpleJson.parse(raw) as? List<*> ?: return
            for (item in parsed) {
                val obj = item as? Map<*, *> ?: continue
                val id = obj["id"]?.toString()?.trim().orEmpty()
                val name = obj["name"]?.toString()?.trim().orEmpty()
                if (id.isBlank() || name.isBlank()) continue
                val description = obj["description"]?.toString().orEmpty()
                val aliases = (obj["aliases"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: emptyList()
                val rawSteps = obj["steps"] as? List<*> ?: emptyList<Any?>()
                val steps = rawSteps.mapNotNull { parseStepMap(it as? Map<*, *>) }
                if (steps.isNotEmpty()) {
                    customWorkflows.add(
                        WorkflowDefinition(
                            id = id,
                            name = name,
                            description = description,
                            aliases = aliases,
                            steps = steps,
                            isBuiltIn = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Keep clean state if persisted JSON is corrupted
        }
    }

    private fun persistCustomWorkflowsLocked() {
        val json = buildString {
            append('[')
            customWorkflows.forEachIndexed { wfIdx, wf ->
                if (wfIdx > 0) append(',')
                append('{')
                append("\"id\":").append(SimpleJson.quote(wf.id)).append(',')
                append("\"name\":").append(SimpleJson.quote(wf.name)).append(',')
                append("\"description\":").append(SimpleJson.quote(wf.description)).append(',')
                append("\"aliases\":[")
                wf.aliases.forEachIndexed { aIdx, a ->
                    if (aIdx > 0) append(',')
                    append(SimpleJson.quote(a))
                }
                append("],\"steps\":[")
                wf.steps.forEachIndexed { sIdx, s ->
                    if (sIdx > 0) append(',')
                    append(serializeStep(s))
                }
                append("]}")
            }
            append(']')
        }
        storage.saveJson(json)
    }

    private fun serializeStep(s: WorkflowStep): String {
        return buildString {
            append('{')
            append("\"type\":").append(SimpleJson.quote(s.type))
            if (s.target.isNotEmpty()) append(",\"target\":").append(SimpleJson.quote(s.target))
            if (s.action.isNotEmpty()) append(",\"action\":").append(SimpleJson.quote(s.action))
            if (s.value.isNotEmpty()) append(",\"value\":").append(SimpleJson.quote(s.value))
            if (s.query.isNotEmpty()) append(",\"query\":").append(SimpleJson.quote(s.query))
            if (s.device.isNotEmpty()) append(",\"device\":").append(SimpleJson.quote(s.device))
            if (s.mediaType != "auto") append(",\"mediaType\":").append(SimpleJson.quote(s.mediaType))
            if (s.provider != "auto") append(",\"provider\":").append(SimpleJson.quote(s.provider))
            if (s.message.isNotEmpty()) append(",\"message\":").append(SimpleJson.quote(s.message))
            if (s.scene.isNotEmpty()) append(",\"scene\":").append(SimpleJson.quote(s.scene))
            if (s.brightness != 0) append(",\"brightness\":").append(s.brightness)
            if (s.color.isNotEmpty()) append(",\"color\":").append(SimpleJson.quote(s.color))
            if (s.source.isNotEmpty()) append(",\"source\":").append(SimpleJson.quote(s.source))
            if (s.minutes != 0) append(",\"minutes\":").append(s.minutes)
            if (s.seconds != 0) append(",\"seconds\":").append(s.seconds)
            if (s.label.isNotEmpty()) append(",\"label\":").append(SimpleJson.quote(s.label))
            if (!s.enabled) append(",\"enabled\":false")
            if (s.live) append(",\"live\":true")
            append('}')
        }
    }

    internal fun parseStepsJson(stepsJson: String): List<WorkflowStep> {
        val trimmed = stepsJson.trim()
        if (trimmed.isBlank()) return emptyList()
        return try {
            val parsed = SimpleJson.parse(trimmed)
            val rawList = when (parsed) {
                is List<*> -> parsed
                is Map<*, *> -> listOf(parsed)
                else -> return emptyList()
            }
            val result = mutableListOf<WorkflowStep>()
            for (item in rawList) {
                val step = parseStepMap(item as? Map<*, *>) ?: return emptyList()
                result.add(step)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseStepMap(map: Map<*, *>?): WorkflowStep? {
        if (map == null) return null
        val rawType = map.str("type")
        val normalizedType = normalizeStepType(rawType) ?: return null
        return WorkflowStep(
            type = normalizedType,
            target = map.str("target", "entity_id", "entityId"),
            action = map.str("action", "service"),
            value = map.str("value"),
            query = map.str("query", "song", "station"),
            device = map.str("device", "room", "speaker"),
            mediaType = map.str("mediaType", "media_type").ifBlank { "auto" },
            provider = map.str("provider").ifBlank { "auto" },
            message = map.str("message", "text"),
            scene = map.str("scene", "scene_name"),
            brightness = map.int("brightness"),
            color = map.str("color"),
            source = map.str("source", "input"),
            minutes = map.int("minutes", "duration"),
            seconds = map.int("seconds"),
            label = map.str("label", "title"),
            enabled = map.bool("enabled", default = true),
            live = map.bool("live", default = false)
        )
    }

    private fun Map<*, *>.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k] ?: continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private fun Map<*, *>.int(vararg keys: String): Int {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is Number -> return v.toInt()
                is String -> v.trim().toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return 0
    }

    private fun Map<*, *>.bool(key: String, default: Boolean): Boolean {
        val v = this[key] ?: return default
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase(Locale.ROOT)) {
                "true", "1", "yes", "si", "sí", "on" -> true
                "false", "0", "no", "off" -> false
                else -> default
            }
            else -> default
        }
    }
}

/**
 * Minimal pure-Kotlin RFC 8259 JSON parser/quoter so workflow persistence and parsing
 * run identically on Android and plain JVM unit tests (without android.jar org.json stubs).
 */
internal object SimpleJson {
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(input: String): Any? {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw IllegalArgumentException("Unexpected trailing character at index ${parser.pos}")
        }
        return value
    }

    private class Parser(private val src: String) {
        var pos = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!isAtEnd() && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd() || src[pos] != '"') {
                    throw IllegalArgumentException("Expected string key at $pos")
                }
                val key = parseString()
                skipWhitespace()
                if (isAtEnd() || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (!isAtEnd() && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val raw = src.substring(start, pos)
            return if (isFloat) raw.toDouble() else raw.toLong()
        }
    }
}
