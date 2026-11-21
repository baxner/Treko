package com.treko.core.common.util

import java.text.Normalizer
import java.util.Locale

/**
 * Resultado de buscar un elemento (tarea, hábito) por id o por título.
 * Solo [Found] autoriza a actuar; el resto se le explica al modelo.
 */
sealed class TargetLookup<out T> {
    data class Found<T>(val item: T) : TargetLookup<T>()
    /** No llegó id ni título. */
    data object MissingQuery : TargetLookup<Nothing>()
    data class NotFound(val query: String) : TargetLookup<Nothing>()
    data class Ambiguous<T>(val candidates: List<T>) : TargetLookup<T>()

    companion object {
        /**
         * Reglas: con id válido se busca solo por id. Sin id y con título vacío no se
         * elige nada. Con título, gana la coincidencia exacta (sin mayúsculas ni acentos);
         * si no hay, la parcial, pero solo si es única.
         */
        fun <T> find(
            items: List<T>,
            id: Int?,
            title: String?,
            idOf: (T) -> Int,
            titleOf: (T) -> String
        ): TargetLookup<T> {
            if (id != null && id >= 0) {
                return items.firstOrNull { idOf(it) == id }?.let { Found(it) } ?: NotFound("#$id")
            }
            val query = normalize(title.orEmpty())
            if (query.isEmpty()) return MissingQuery

            val exact = items.filter { normalize(titleOf(it)) == query }
            if (exact.size == 1) return Found(exact.single())
            if (exact.size > 1) return Ambiguous(exact)

            val partial = items.filter { normalize(titleOf(it)).contains(query) }
            return when (partial.size) {
                0 -> NotFound(title.orEmpty().trim())
                1 -> Found(partial.single())
                else -> Ambiguous(partial)
            }
        }

        private val DIACRITICS = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .replace(SPACES, " ")
    }
}

/**
 * Texto para el modelo cuando no se puede actuar, en español: [kind] y [kindPlural] son el
 * sustantivo ("tarea", "tareas"). Siempre dice que no se cambió nada.
 */
fun <T> TargetLookup<T>.failureMessage(
    kind: String,
    kindPlural: String,
    allItems: List<T>,
    describe: (T) -> String
): String? {
    val current = allItems.joinToString("; ") { describe(it) }.ifBlank { "nada" }
    return when (this) {
        is TargetLookup.Found -> null
        TargetLookup.MissingQuery ->
            "No dijiste cuál ($kind), así que no cambié nada; pregunta al usuario. Actuales ($kindPlural): $current."
        is TargetLookup.NotFound ->
            "No encontré $kind «$query», así que no cambié nada. Actuales ($kindPlural): $current."
        is TargetLookup.Ambiguous ->
            "Hay varias coincidencias (${candidates.joinToString("; ") { describe(it) }}); no cambié nada. " +
                "Pregunta al usuario cuál o usa su id."
    }
}
