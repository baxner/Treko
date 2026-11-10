package com.treko.core.kws

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

/**
 * Tokenizador y formateador BPE para Sherpa-ONNX Keyword Spotting (KWS).
 * Carga el vocabulario de subpalabras de models/en/tokens.txt y genera
 * las secuencias de tokens requeridas para detección local de palabras de activación personalizadas.
 */
class KwsTokenizer(val tokenSet: Set<String>) {

    companion object {
        const val SPACE_TOKEN = "\u2581" // Token ID 20:   (Lower One Eighth Block)
        const val SPACE_TOKEN_ID = 20

        private val DIGIT_WORDS = mapOf(
            '0' to "ZERO", '1' to "ONE", '2' to "TWO", '3' to "THREE", '4' to "FOUR",
            '5' to "FIVE", '6' to "SIX", '7' to "SEVEN", '8' to "EIGHT", '9' to "NINE"
        )

        fun loadFromAssets(context: Context, path: String = "models/en/tokens.txt"): KwsTokenizer {
            context.assets.open(path).use { inputStream ->
                return loadFromStream(inputStream)
            }
        }

        fun loadFromStream(inputStream: InputStream): KwsTokenizer {
            val set = HashSet<String>()
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isNotEmpty()) {
                        val lastSpace = line.lastIndexOf(' ')
                        if (lastSpace > 0) {
                            val token = line.substring(0, lastSpace)
                            set.add(token)
                        }
                    }
                }
            }
            return KwsTokenizer(set)
        }
    }

    /**
     * Segmenta una palabra individual en tokens según el vocabulario de subpalabras BPE.
     * Incorpora fallback robusto para iniciales como L, M, N, U, V, X, Y, Z que no tienen
     * variante combinada con espacio ' ' emitiendo el token de espacio independiente ('\u2581', ID 20).
     */
    fun tokenizeWord(word: String): List<String> {
        // Descomponer tildes/acentos (NFD) y eliminar marcas diacríticas (e.g. María -> Maria, Niño -> Nino)
        val normalized = Normalizer.normalize(word, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // Reemplazar dígitos numéricos por su representación fonética (e.g. 2 -> TWO)
        val sb = StringBuilder()
        for (ch in normalized) {
            val digitWord = DIGIT_WORDS[ch]
            if (digitWord != null) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                sb.append(digitWord).append(' ')
            } else {
                sb.append(ch)
            }
        }

        val cleanWord = sb.toString().uppercase(Locale.US).filter { it in 'A'..'Z' || it == '\'' || it == '-' }
        if (cleanWord.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()

        // 1. Intentar prefijar la palabra con el token de espacio \u2581
        val prefixed = SPACE_TOKEN + cleanWord
        var matchedPrefix: String? = null
        for (len in prefixed.length downTo 2) {
            val candidate = prefixed.substring(0, len)
            if (tokenSet.contains(candidate)) {
                matchedPrefix = candidate
                break
            }
        }

        var remaining: String
        if (matchedPrefix != null) {
            tokens.add(matchedPrefix)
            remaining = prefixed.substring(matchedPrefix.length)
        } else {
            // Fallback robusto para letras como L, M, N, U, V, X, Y, Z:
            // Emitir el token de espacio independiente ' ' (\u2581, Token ID 20)
            tokens.add(SPACE_TOKEN)
            remaining = cleanWord
        }

        // 2. Segmentar el resto de la palabra usando greedy longest matching
        while (remaining.isNotEmpty()) {
            var matchedSub: String? = null
            for (len in remaining.length downTo 1) {
                val candidate = remaining.substring(0, len)
                if (tokenSet.contains(candidate)) {
                    matchedSub = candidate
                    break
                }
            }

            if (matchedSub != null) {
                tokens.add(matchedSub)
                remaining = remaining.substring(matchedSub.length)
            } else {
                // Fallback carácter individual si existe en el vocabulario
                val singleChar = remaining.substring(0, 1)
                if (tokenSet.contains(singleChar)) {
                    tokens.add(singleChar)
                }
                remaining = remaining.substring(1)
            }
        }

        return tokens
    }

    /**
     * Segmenta una frase completa (múltiples palabras) en una lista plana de tokens.
     */
    fun tokenizePhrase(phrase: String): List<String> {
        val words = phrase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val allTokens = mutableListOf<String>()
        for (w in words) {
            allTokens.addAll(tokenizeWord(w))
        }
        return allTokens
    }

    /**
     * Formatea una frase arbitraria en el formato Sherpa-ONNX KWS:
     * TOKEN_1 TOKEN_2 ... :<boost> #<threshold> @<LABEL>
     */
    fun formatKeywordLine(
        phrase: String,
        boost: Float,
        threshold: Float,
        label: String? = null
    ): String {
        val tokens = tokenizePhrase(phrase)
        if (tokens.isEmpty()) return ""

        val tokenString = tokens.joinToString(" ")
        val rawLabel = label ?: phrase.trim()
        val normalizedLabel = Normalizer.normalize(rawLabel, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "KEYWORD" }

        return String.format(
            Locale.US,
            "%s :%.1f #%.2f @%s",
            tokenString,
            boost,
            threshold,
            normalizedLabel
        )
    }
}
