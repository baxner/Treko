package com.treko.assistant.meditation

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The words of one script, from string resources (one array each, in order):
 * [intro] settles in, [body] is the core guidance (kept in order, thinned out for short
 * sessions) and [reminders] are short lines repeated to fill long sessions.
 */
data class MeditationScriptTexts(
    val intro: List<String>,
    val body: List<String>,
    val reminders: List<String>
)

/** One spoken cue, planned to start [startAtMs] after the session began (active time). */
data class MeditationCue(val text: String, val startAtMs: Long)

/**
 * The plan of a session: cues at fixed offsets of active time, then the closing at
 * [closingAtMs]; [totalMs] is the length the user asked for.
 */
data class MeditationTimeline(
    val type: MeditationType,
    val minutes: Int,
    val cues: List<MeditationCue>,
    val closingAtMs: Long,
    val totalMs: Long
)

/** Turns a script into a timeline of the requested length (3-30 min). Pure. */
object MeditationTimelineBuilder {
    const val DEFAULT_MINUTES = 10
    const val MIN_MINUTES = 3
    const val MAX_MINUTES = 30

    /** Time kept at the end for the closing line and putting the room back. */
    const val CLOSING_RESERVE_MS = 20_000L

    /** Shortest silence after a cue (and the minimum gap the session keeps between two cues). */
    const val MIN_GAP_MS = 8_000L

    /** Settling-in pause after each intro line. */
    private const val INTRO_GAP_MS = 6_000L

    /** Longest silence between two cues for [type]; reminders are added to keep under it. */
    fun maxGapMs(type: MeditationType): Long = when (type) {
        MeditationType.BREATH_478, MeditationType.BOX_BREATHING -> 75_000L
        MeditationType.BODY_SCAN -> 45_000L
        MeditationType.SLEEP -> 90_000L
        MeditationType.MINDFULNESS -> 80_000L
    }

    fun clampMinutes(minutes: Int?): Int =
        (minutes?.takeIf { it > 0 } ?: DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** Rough time to say [text] slowly, plus the voice's start-up latency. */
    fun estimateSpeechMs(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return 1_500L + words * 480L
    }

    fun build(type: MeditationType, texts: MeditationScriptTexts, minutes: Int?): MeditationTimeline {
        val mins = clampMinutes(minutes)
        val totalMs = mins * 60_000L
        val closingAt = totalMs - CLOSING_RESERVE_MS
        val intro = texts.intro.filter { it.isNotBlank() }
        val body = texts.body.filter { it.isNotBlank() }
        val reminders = texts.reminders.filter { it.isNotBlank() }

        val cues = mutableListOf<MeditationCue>()
        var t = 0L
        for (line in intro) {
            if (t + estimateSpeechMs(line) + INTRO_GAP_MS > closingAt) break
            cues += MeditationCue(line, t)
            t += estimateSpeechMs(line) + INTRO_GAP_MS
        }
        val available = closingAt - t

        // As many body cues as fit with at least MIN_GAP_MS after each, spread across the script.
        var k = body.size
        while (k > 1 && body.selectEvenly(k).sumOf { estimateSpeechMs(it) + MIN_GAP_MS } > available) k--
        var chosen = if (body.isEmpty()) emptyList() else body.selectEvenly(k)
        if (chosen.size == 1 && estimateSpeechMs(chosen[0]) + MIN_GAP_MS > available) chosen = emptyList()

        // Fill long sessions with reminders so no silence is longer than maxGapMs.
        val maxGap = maxGapMs(type)
        var fillers = 0
        if (reminders.isNotEmpty()) {
            while (true) {
                val lines = interleave(chosen, reminders, fillers)
                val spoken = lines.sumOf { estimateSpeechMs(it) }
                val gap = if (lines.isEmpty()) available else (available - spoken) / lines.size
                if (gap <= maxGap) break
                val withOne = interleave(chosen, reminders, fillers + 1)
                val gapWithOne = (available - withOne.sumOf { estimateSpeechMs(it) }) / withOne.size
                if (gapWithOne < MIN_GAP_MS) break
                fillers++
            }
        }
        val lines = interleave(chosen, reminders, fillers)
        if (lines.isNotEmpty()) {
            val spoken = lines.sumOf { estimateSpeechMs(it) }
            val gap = ((available - spoken).toDouble() / lines.size).coerceAtLeast(MIN_GAP_MS.toDouble())
            var at = t.toDouble()
            for (line in lines) {
                if (at.roundToLong() + estimateSpeechMs(line) > closingAt) break
                cues += MeditationCue(line, at.roundToLong())
                at += estimateSpeechMs(line) + gap
            }
        }
        return MeditationTimeline(type, mins, cues, closingAt, totalMs)
    }

    /** [count] items spread over the list, always keeping the first and the last. */
    internal fun <T> List<T>.selectEvenly(count: Int): List<T> {
        if (count >= size) return this
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(first())
        return (0 until count).map { i -> this[(i * (size - 1).toDouble() / (count - 1)).roundToLong().toInt()] }.distinct()
    }

    /** [body] in order with [fillers] reminders (cycled) spread between and after them. */
    internal fun interleave(body: List<String>, reminders: List<String>, fillers: Int): List<String> {
        if (fillers <= 0 || reminders.isEmpty()) return body
        val total = body.size + fillers
        if (body.isEmpty()) return List(fillers) { reminders[it % reminders.size] }
        // Body cue j goes at slot round(j * total / body.size); reminders fill the rest.
        val bodySlots = body.indices.associateBy { j -> ceil(j * total.toDouble() / body.size).toInt() }
        var b = 0
        var r = 0
        return List(total) { slot ->
            if (bodySlots.containsKey(slot) && b < body.size) body[b++] else reminders[r++ % reminders.size]
        }
    }
}
