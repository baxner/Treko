package com.treko.assistant.data

import java.util.Locale

/** How much a camera verdict matters, as the vision model rated it. */
enum class VisionLevel(val key: String) {
    /** Nothing confirmed, or an empty scene. */
    NONE("ninguno"),
    /** Someone or something passing by, a parked vehicle: worth a note, not an alert. */
    INFO("info"),
    /** Someone inside the property or at the door, a package, a door or gate open. */
    ATTENTION("atencion"),
    /** Someone forcing, opening or climbing; smoke or fire. */
    URGENT("urgente");

    companion object {
        /** Tolerant: accents, case, English words and a few synonyms are accepted. */
        fun parse(raw: String?): VisionLevel? {
            val v = TrekoProactiveManager.normalizeAscii(raw.orEmpty()).trim('"', ' ', '.')
            if (v.isEmpty()) return null
            return when {
                v.startsWith("urgent") || v == "alta" || v == "critico" || v == "critical" -> URGENT
                v.startsWith("atenc") || v.startsWith("attent") || v == "alerta" || v == "alert" ||
                    v == "aviso" || v == "warning" || v == "media" -> ATTENTION
                v.startsWith("info") || v == "baja" || v == "low" -> INFO
                v.startsWith("ningun") || v == "none" || v == "normal" || v == "nada" || v == "no" -> NONE
                else -> null
            }
        }
    }
}

/**
 * The structured answer of the vision model about one picture (the JSON the sentinel and the
 * watch prompts ask for). Every field is optional in the model's answer; missing ones get
 * neutral values.
 */
data class VisionVerdict(
    /** The model saw what the detector reported; null when it did not say. */
    val confirmed: Boolean?,
    val people: Int,
    val vehicles: Int,
    val packageSeen: Boolean,
    /** Where, in a few words ("frente a la puerta"). */
    val where: String,
    /** What is visibly happening ("caminando de paso"). */
    val activity: String,
    val level: VisionLevel,
    /** Why this level, visible facts only. */
    val reason: String,
    /** Short sentence to say out loud. */
    val spokenAlert: String
) {
    /**
     * True when this verdict is worth an alert: attention or urgent always; an info-level
     * verdict only while the user asked to watch this place.
     */
    fun raisesAlert(watchRequested: Boolean): Boolean = when (level) {
        VisionLevel.URGENT, VisionLevel.ATTENTION -> true
        VisionLevel.INFO -> watchRequested
        VisionLevel.NONE -> false
    }

    /** Main thing seen, as a detector label (person, car, package), when any. */
    val mainObject: String?
        get() = when {
            people > 0 -> "person"
            vehicles > 0 -> "car"
            packageSeen -> "package"
            else -> null
        }

    /**
     * One readable line for the alerts log and the notification, built from the fields
     * ("Frente a la puerta: parada en la entrada. Hay una persona…"), never the raw JSON.
     */
    fun describe(): String {
        val parts = ArrayList<String>()
        val place = listOf(activity, where).filter { it.isNotBlank() }.joinToString(" ")
        if (place.isNotBlank()) parts += sentence(place)
        if (reason.isNotBlank() && !sameText(reason, place)) parts += sentence(reason)
        if (parts.isEmpty() && spokenAlert.isNotBlank()) parts += sentence(spokenAlert)
        return parts.joinToString(" ")
    }

    /**
     * A readable line that is never the model's raw answer: [describe], else a generic sentence
     * from what was counted ("hay una persona"), else "nothing seen" / "something to check".
     */
    fun fallbackLine(texts: CameraAlertTexts = CameraAlertTexts.SPANISH): String =
        describe().ifBlank { genericLine(texts) }

    /** Generic sentence built only from the counted fields and the level. */
    fun genericLine(texts: CameraAlertTexts = CameraAlertTexts.SPANISH): String {
        val parts = ArrayList<String>()
        if (people > 0) parts += texts.people(people)
        if (vehicles > 0) parts += texts.vehicles(vehicles)
        if (packageSeen) parts += texts.onePackage
        val line = when {
            parts.isNotEmpty() -> parts.joinToString(", ")
            level == VisionLevel.ATTENTION || level == VisionLevel.URGENT -> texts.somethingToCheck
            else -> texts.nothingSeen
        }
        return sentence(line)
    }

    /** Attention or urgent: the alert must not sound like "all is fine". */
    val isAlarming: Boolean get() = level == VisionLevel.ATTENTION || level == VisionLevel.URGENT

    /**
     * The model's own short sentence when it can be said as is: not blank and, at attention or
     * urgent level, not reassuring ("no hay peligro" next to nivel=atencion contradicts it).
     */
    fun usableSpokenAlert(texts: CameraAlertTexts = CameraAlertTexts.SPANISH): String? {
        val aviso = spokenAlert.trim()
        if (aviso.isEmpty()) return null
        if (isAlarming && reassures(aviso, texts.reassurances)) return null
        return SpokenText.limitWords(aviso, MAX_SPOKEN_WORDS)
    }

    /**
     * What was seen, from the fields only: who or what + activity + where ("una persona
     * caminando de paso en la banqueta"); the counted facts when those are missing.
     */
    fun composedLine(texts: CameraAlertTexts = CameraAlertTexts.SPANISH): String {
        val subject = when {
            people > 0 -> texts.subjectPeople(people)
            vehicles > 0 -> texts.subjectVehicles(vehicles)
            packageSeen -> texts.subjectPackage
            else -> ""
        }
        val line = listOf(subject, activity, where).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }.joinToString(" ")
        return if (subject.isBlank() && activity.isBlank() && where.isBlank()) genericLine(texts) else sentence(line)
    }

    /**
     * The sentence to say for this verdict on [cameraName]: [usableSpokenAlert], else
     * "En <camera>: <[composedLine]>".
     */
    fun spokenLine(cameraName: String, texts: CameraAlertTexts = CameraAlertTexts.SPANISH): String {
        usableSpokenAlert(texts)?.let { return it }
        val seen = composedLine(texts).replaceFirstChar { it.lowercase(Locale.getDefault()) }
        return SpokenText.limitWords(String.format(Locale.ROOT, texts.fallbackSpeech, cameraName, seen), MAX_SPOKEN_WORDS)
    }

    companion object {
        /** True when [text] contains one of the reassuring [phrases] (case, accents and punctuation ignored). */
        fun reassures(text: String, phrases: List<String>): Boolean {
            val t = " ${plainWords(text)} "
            return phrases.map { plainWords(it) }.any { it.isNotEmpty() && t.contains(" $it") }
        }

        private fun plainWords(text: String): String =
            TrekoProactiveManager.normalizeAscii(text).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** Longest spoken alert kept (the prompt asks for 15 words). */
        const val MAX_SPOKEN_WORDS = 18

        private fun sentence(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return t
            val capped = t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            return if (capped.last() in ".!?…") capped else "$capped."
        }

        private fun sameText(a: String, b: String): Boolean =
            TrekoProactiveManager.normalizeAscii(a).trimEnd('.') == TrekoProactiveManager.normalizeAscii(b).trimEnd('.')
    }
}

/**
 * The words Treko adds around a camera alert, in the user's language (the app passes them from
 * its string resources; [SPANISH] is the default for code without a Context and for tests).
 */
data class CameraAlertTexts(
    /** Follow-up context for the voice model: %1$s camera(s), %2$s level, %3$s visible facts. */
    val followupPrompt: String,
    /** Spoken when the model gave no short sentence: %1$s camera, %2$s what it saw. */
    val fallbackSpeech: String,
    /** Level label when the model gave none. */
    val noLevel: String,
    /** "hay una persona" / "hay 3 personas" for a count above zero. */
    val people: (Int) -> String,
    /** "hay un vehículo" / "hay 2 vehículos" for a count above zero. */
    val vehicles: (Int) -> String,
    val onePackage: String,
    val somethingToCheck: String,
    val nothingSeen: String,
    /** "una persona" / "3 personas": who was seen, at the start of a sentence. */
    val subjectPeople: (Int) -> String = { n -> if (n == 1) "una persona" else "$n personas" },
    /** "un vehículo" / "2 vehículos". */
    val subjectVehicles: (Int) -> String = { n -> if (n == 1) "un vehículo" else "$n vehículos" },
    val subjectPackage: String = "un paquete",
    /** Reassuring phrases never said in an attention or urgent alert (see [VisionVerdict.usableSpokenAlert]). */
    val reassurances: List<String> = SPANISH_REASSURANCES
) {
    companion object {
        val SPANISH = CameraAlertTexts(
            followupPrompt = "[AVISO DE CÁMARA] Cámara: %1\$s. Nivel: %2\$s. Hechos visibles: %3\$s. " +
                "Si pide verla usa view_camera. Habla con calma y en una frase; no lo llames intruso ni sospechoso salvo que el nivel sea urgente.",
            fallbackSpeech = "En %1\$s: %2\$s",
            noLevel = "sin nivel",
            people = { n -> if (n == 1) "hay una persona" else "hay $n personas" },
            vehicles = { n -> if (n == 1) "hay un vehículo" else "hay $n vehículos" },
            onePackage = "hay un paquete",
            somethingToCheck = "hay algo que conviene revisar",
            nothingSeen = "no se ve nada fuera de lo normal"
        )
    }
}

/** Default for [CameraAlertTexts.reassurances]; the app passes the list of its string resources. */
internal val SPANISH_REASSURANCES = listOf(
    "no hay peligro", "sin peligro", "no es peligros", "no hay riesgo", "sin riesgo",
    "nada de que preocupar", "nada por lo que preocupar", "no hay de que preocupar", "no hay por que preocupar",
    "no te preocupes", "todo bien", "todo en orden", "todo tranquilo", "todo normal", "nada grave",
    "no pasa nada", "nada fuera de lo normal", "nada raro"
)

/** Text helpers for what Treko says out loud. */
object SpokenText {
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]"
    )

    /** Removes emoji (habit icons, symbols) so text-to-speech does not read or skip them oddly. */
    fun stripEmoji(text: String): String =
        EMOJI.replace(text, "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .lines().joinToString("\n") { it.trim() }
            .trim()

    /**
     * At most [maxWords] words, cut at a word boundary (never mid-word), ending with a period
     * when it was cut or had no end mark.
     */
    fun limitWords(text: String, maxWords: Int): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val cut = words.size > maxWords
        var out = words.take(maxWords).joinToString(" ")
        if (cut) out = out.trimEnd(',', ';', ':', '-', '–', '—')
        return if (out.last() in ".!?…") out else "$out."
    }
}

/**
 * Reads the vision model's JSON verdict out of its answer. Tolerant: takes the first `{…}`
 * (code fences, a preamble or comments around it are fine), accepts trailing commas, unquoted
 * booleans and accents in keys, and ignores fields it does not know. Null when the answer has
 * no recognisable verdict (then the caller falls back to the keyword reading of the text).
 */
fun parseVisionVerdict(raw: String?): VisionVerdict? {
    val text = raw?.trim().orEmpty()
    val block = firstJsonObject(text) ?: return null
    val fields = HashMap<String, String>()
    for (m in FIELD_REGEX.findAll(block)) {
        val key = TrekoProactiveManager.normalizeAscii(m.groupValues[1]).replace(' ', '_')
        if (key !in fields) fields[key] = m.groupValues[2]
    }
    if (KNOWN_KEYS.none { it in fields }) return null

    val confirmed = fields["confirmado"]?.let(::asBoolean)
    val people = fields["personas"]?.let(::asInt) ?: 0
    val vehicles = (fields["vehiculos"] ?: fields["vehiculo"])?.let(::asInt) ?: 0
    val packageSeen = fields["paquete"]?.let(::asBoolean) ?: false
    val level = VisionLevel.parse(fields["nivel"]?.let(::asString)) ?: when {
        // No level given: derive a cautious one from what it did say.
        confirmed == false -> VisionLevel.NONE
        // A package next to someone passing on the street is not a delivery to this home.
        packageSeen && !passingBy(fields["actividad"], fields["donde"]) -> VisionLevel.ATTENTION
        packageSeen -> VisionLevel.INFO
        people > 0 || vehicles > 0 || confirmed == true -> VisionLevel.INFO
        else -> VisionLevel.NONE
    }
    return VisionVerdict(
        confirmed = confirmed,
        people = people.coerceAtLeast(0),
        vehicles = vehicles.coerceAtLeast(0),
        packageSeen = packageSeen,
        where = fields["donde"]?.let(::asString).orEmpty(),
        activity = fields["actividad"]?.let(::asString).orEmpty(),
        level = level,
        reason = fields["motivo"]?.let(::asString).orEmpty(),
        spokenAlert = fields["aviso"]?.let(::asString).orEmpty()
    )
}

/** Words of a verdict's actividad/donde that mean "out on the street, passing by". */
private val PASSING_WORDS = listOf(
    "de paso", "pasando", "caminando por", "banqueta", "acera", "vereda", "en la calle",
    "passing", "walking past", "walking by", "sidewalk", "pavement", "on the street", "in the street"
)

private fun passingBy(activity: String?, where: String?): Boolean {
    val text = TrekoProactiveManager.normalizeAscii(listOfNotNull(activity, where).joinToString(" ") { asString(it) })
    return PASSING_WORDS.any { text.contains(it) }
}

private val KNOWN_KEYS = listOf("nivel", "confirmado", "aviso", "personas", "vehiculos", "paquete", "motivo")

/** "key": value, where value is a string, a number or a bare word (true, false, null, urgente…). */
private val FIELD_REGEX = Regex(
    "[\"']([^\"'\\\\{}:,]{1,40})[\"']\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|-?\\d+(?:\\.\\d+)?|[\\p{L}_]+)"
)

/** The first balanced `{…}` of [text], braces inside strings ignored; an unclosed one runs to the end. */
private fun firstJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return text.substring(start)
}

private fun asString(value: String): String {
    val v = value.trim()
    if (v.equals("null", ignoreCase = true)) return ""
    if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) {
        return unescape(v.substring(1, v.length - 1)).trim()
    }
    return v
}

private fun asBoolean(value: String): Boolean? = when (TrekoProactiveManager.normalizeAscii(asString(value))) {
    "true", "si", "yes", "verdadero", "1" -> true
    "false", "no", "falso", "0" -> false
    else -> null
}

private fun asInt(value: String): Int? = asString(value).toDoubleOrNull()?.toInt()

private fun unescape(s: String): String {
    if (!s.contains('\\')) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val n = s[i + 1]
            when (n) {
                'n' -> out.append(' ')
                't' -> out.append(' ')
                'r' -> {}
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(n)
                }
                else -> out.append(n)
            }
            i += 2
            continue
        }
        out.append(c)
        i++
    }
    return out.toString()
}
