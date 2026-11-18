package com.treko.assistant.service

import java.text.Normalizer
import java.util.Locale

/**
 * Reconoce cuándo el usuario quiere terminar la conversación ("adiós", "eso es todo").
 *
 * Antes bastaba con que la transcripción contuviera una frase de la lista, así que
 * "pon el celular en silencio" o "música para descansar" cerraban el turno. Ahora:
 *
 * 1. Se quitan acentos, mayúsculas y puntuación, y se descartan palabras de relleno
 *    ("treko", el nombre del asistente, "ya", "por favor", "gracias", "ok"…).
 * 2. Es despedida si lo que queda es exactamente una frase de despedida, o si tiene
 *    como mucho [maxWordsForPrefix] palabras y empieza por una.
 * 3. Las palabras sueltas ambiguas ([soloOnlyPhrases]: "silencio", "quiet", "descansa")
 *    solo cuentan si son lo único que queda.
 */
class SleepIntentDetector(
    assistantNames: Collection<String> = listOf("treko"),
    private val maxWordsForPrefix: Int = 4
) {
    private val fillerWords: Set<String> = (FILLER_WORDS + assistantNames.flatMap { tokenize(it) }).toSet()

    fun isSleepIntent(transcript: String): Boolean {
        val words = tokenize(transcript).filterNot { it in fillerWords }
        if (words.isEmpty()) return false
        val text = words.joinToString(" ")

        if (text in SOLO_ONLY_PHRASES) return true
        if (text in PHRASES) return true
        if (words.size > maxWordsForPrefix) return false
        return PHRASES.any { phrase -> text.startsWith("$phrase ") }
    }

    companion object {
        /** Frases normalizadas (sin acentos, minúsculas) que bastan para despedirse. */
        private val PHRASES = setOf(
            "adios", "hasta luego", "hasta manana", "nos vemos", "chao", "chau",
            "duermete", "a dormir", "vete a dormir", "buenas noches",
            "callate", "apagate", "deja de escuchar",
            "eso es todo", "eso seria todo", "es todo", "nada mas", "nada mas por ahora",
            "bye", "goodbye", "good night", "go to sleep", "stop listening",
            "thats all", "that is all", "shut up"
        )

        /** Palabras que solo indican despedida cuando van solas. */
        private val SOLO_ONLY_PHRASES = setOf("silencio", "descansa", "quiet", "be quiet")

        private val FILLER_WORDS = setOf(
            "ya", "por", "favor", "gracias", "muchas", "ok", "okay", "vale", "bueno",
            "pues", "entonces", "oye", "hey", "y", "eh", "please", "thanks", "thank", "you"
        )

        private val NON_WORD = Regex("[^a-z0-9ñ]+")
        private val DIACRITICS = Regex("\\p{Mn}+")

        fun normalize(text: String): String {
            val lower = text.lowercase(Locale.ROOT)
            // Conserva la ñ: se descompone y recompone fuera de la limpieza de diacríticos.
            val protectedN = lower.replace('ñ', '\u0000')
            val stripped = Normalizer.normalize(protectedN, Normalizer.Form.NFD).replace(DIACRITICS, "")
            return stripped.replace('\u0000', 'ñ').replace("'", "").replace("’", "")
        }

        fun tokenize(text: String): List<String> =
            normalize(text).split(NON_WORD).filter { it.isNotBlank() }
    }
}
