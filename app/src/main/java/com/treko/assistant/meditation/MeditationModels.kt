package com.treko.assistant.meditation

import java.text.Normalizer
import java.util.Locale

/** Phases of one breath, as the face animation shows them. */
enum class BreathPhase { INHALE, HOLD_IN, EXHALE, HOLD_OUT }

data class BreathStep(val phase: BreathPhase, val ms: Long)

/** Where a breathing cycle is at a moment: the step, how far into it (0..1) and lung fullness (0..1). */
data class BreathPosition(val phase: BreathPhase, val progress: Float, val fullness: Float)

/** A breathing rhythm repeated during a session (also drives the face animation). */
data class BreathPattern(val steps: List<BreathStep>) {
    init {
        require(steps.isNotEmpty() && steps.all { it.ms > 0 }) { "A breath pattern needs positive steps" }
    }

    val cycleMs: Long get() = steps.sumOf { it.ms }

    /** Position in the cycle after [elapsedMs] of breathing (negative counts as 0). */
    fun positionAt(elapsedMs: Long): BreathPosition {
        var t = elapsedMs.coerceAtLeast(0L) % cycleMs
        for (step in steps) {
            if (t < step.ms) {
                val p = t.toFloat() / step.ms
                return BreathPosition(step.phase, p, fullness(step.phase, p))
            }
            t -= step.ms
        }
        val last = steps.last()
        return BreathPosition(last.phase, 1f, fullness(last.phase, 1f))
    }

    private fun fullness(phase: BreathPhase, p: Float): Float {
        // Smooth in and out, so the circle does not jerk at the turns.
        val eased = (1f - kotlin.math.cos(Math.PI * p).toFloat()) / 2f
        return when (phase) {
            BreathPhase.INHALE -> eased
            BreathPhase.HOLD_IN -> 1f
            BreathPhase.EXHALE -> 1f - eased
            BreathPhase.HOLD_OUT -> 0f
        }
    }

    companion object {
        private fun of(vararg s: Pair<BreathPhase, Int>) = BreathPattern(s.map { BreathStep(it.first, it.second * 1000L) })

        val FOUR_SEVEN_EIGHT = of(BreathPhase.INHALE to 4, BreathPhase.HOLD_IN to 7, BreathPhase.EXHALE to 8)
        val BOX = of(BreathPhase.INHALE to 4, BreathPhase.HOLD_IN to 4, BreathPhase.EXHALE to 4, BreathPhase.HOLD_OUT to 4)
        val CALM = of(BreathPhase.INHALE to 4, BreathPhase.EXHALE to 6)
        val SLEEP = of(BreathPhase.INHALE to 4, BreathPhase.HOLD_IN to 2, BreathPhase.EXHALE to 7)
    }
}

/** The guided sessions Treko knows. [id] is the value the `meditation` tool uses. */
enum class MeditationType(val id: String, val pattern: BreathPattern) {
    BREATH_478("respiracion_478", BreathPattern.FOUR_SEVEN_EIGHT),
    BOX_BREATHING("respiracion_caja", BreathPattern.BOX),
    BODY_SCAN("escaneo_corporal", BreathPattern.CALM),
    SLEEP("dormir", BreathPattern.SLEEP),
    MINDFULNESS("atencion_plena", BreathPattern.CALM);

    companion object {
        val DEFAULT = MINDFULNESS

        /** The type for a tool value or a loose phrase ("4-7-8", "caja", "para dormir"…); [DEFAULT] when unknown. */
        fun parse(raw: String?): MeditationType {
            val n = normalize(raw.orEmpty())
            if (n.isBlank()) return DEFAULT
            entries.firstOrNull { normalize(it.id) == n || it.name.lowercase(Locale.ROOT) == n.replace(' ', '_') }?.let { return it }
            val compact = n.replace(" ", "")
            return when {
                "478" in compact -> BREATH_478
                listOf("caja", "cuadrad", "box", "square").any { it in n } -> BOX_BREATHING
                listOf("escaneo", "cuerpo", "corporal", "body", "scan").any { it in n } -> BODY_SCAN
                listOf("dormir", "sueno", "noche", "sleep", "insomn").any { it in n } -> SLEEP
                listOf("libre", "atencion", "plena", "mindful", "presente", "free").any { it in n } -> MINDFULNESS
                listOf("respir", "breath").any { it in n } -> BREATH_478
                else -> DEFAULT
            }
        }

        internal fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}

/** Who reads the cues between Treko's opening and closing lines. */
enum class MeditationVoiceChoice(val id: String) {
    TREKO("treko"),
    PHONE("phone");

    companion object {
        fun fromId(id: String?): MeditationVoiceChoice = entries.firstOrNull { it.id == id } ?: TREKO
    }
}
