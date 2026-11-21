package com.treko.assistant.meditation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeditationTimelineTest {

    private val texts = MeditationScriptTexts(
        intro = listOf("Siéntate cómodo y suelta los hombros.", "Respira despacio por la nariz."),
        body = (1..8).map { "Indicación del cuerpo número $it, dicha con calma y despacio." },
        reminders = listOf("Si te distraes, vuelve a la respiración.", "Suelta los hombros y respira.")
    )

    private fun MeditationTimeline.assertWellFormed() {
        assertTrue("no cues", cues.isNotEmpty())
        assertEquals(0L, cues.first().startAtMs)
        cues.zipWithNext().forEach { (a, b) ->
            assertTrue("cues overlap: $a then $b", b.startAtMs >= a.startAtMs + MeditationTimelineBuilder.estimateSpeechMs(a.text))
        }
        val last = cues.last()
        assertTrue("last cue runs into the closing", last.startAtMs + MeditationTimelineBuilder.estimateSpeechMs(last.text) <= closingAtMs)
        assertEquals(totalMs - MeditationTimelineBuilder.CLOSING_RESERVE_MS, closingAtMs)
    }

    @Test
    fun minutesAreClampedWithTenByDefault() {
        assertEquals(10, MeditationTimelineBuilder.clampMinutes(null))
        assertEquals(10, MeditationTimelineBuilder.clampMinutes(0))
        assertEquals(3, MeditationTimelineBuilder.clampMinutes(1))
        assertEquals(30, MeditationTimelineBuilder.clampMinutes(90))
        assertEquals(15, MeditationTimelineBuilder.clampMinutes(15))
        assertEquals(30 * 60_000L, MeditationTimelineBuilder.build(MeditationType.MINDFULNESS, texts, 45).totalMs)
    }

    @Test
    fun tenMinutesKeepsEveryBodyCueInOrderWithinTheGapLimit() {
        val t = MeditationTimelineBuilder.build(MeditationType.MINDFULNESS, texts, 10)
        t.assertWellFormed()
        assertEquals(10 * 60_000L, t.totalMs)
        val bodyOrder = t.cues.map { it.text }.filter { it in texts.body }
        assertEquals(texts.body, bodyOrder)
        val maxGap = MeditationTimelineBuilder.maxGapMs(MeditationType.MINDFULNESS)
        t.cues.zipWithNext().forEach { (a, b) ->
            val silence = b.startAtMs - a.startAtMs - MeditationTimelineBuilder.estimateSpeechMs(a.text)
            assertTrue("silence $silence ms", silence <= maxGap + 1)
        }
    }

    @Test
    fun shortSessionsThinTheBodyButKeepFirstAndLast() {
        val scan = texts.copy(body = (1..16).map { "Parte del cuerpo número $it: nótala y suéltala con calma." })
        val t = MeditationTimelineBuilder.build(MeditationType.BODY_SCAN, scan, 3)
        t.assertWellFormed()
        val body = t.cues.map { it.text }.filter { it in scan.body }
        assertTrue("kept ${body.size}", body.size in 2 until scan.body.size)
        assertEquals(scan.body.first(), body.first())
        assertEquals(scan.body.last(), body.last())
        assertEquals(body, scan.body.filter { it in body })
        t.cues.zipWithNext().forEach { (a, b) ->
            val silence = b.startAtMs - a.startAtMs - MeditationTimelineBuilder.estimateSpeechMs(a.text)
            // Intro lines get a short settling pause; every other cue at least MIN_GAP_MS.
            val min = if (a.text in scan.intro) 6_000L else MeditationTimelineBuilder.MIN_GAP_MS
            assertTrue("silence $silence ms after '${a.text}'", silence >= min - 1)
        }
    }

    @Test
    fun longSessionsAreFilledWithRemindersAndScaleUp() {
        val short = MeditationTimelineBuilder.build(MeditationType.BREATH_478, texts, 10)
        val long = MeditationTimelineBuilder.build(MeditationType.BREATH_478, texts, 30)
        long.assertWellFormed()
        assertTrue(long.cues.size > short.cues.size)
        assertTrue(long.cues.any { it.text in texts.reminders })
        // The cues spread over the whole session, not only its start.
        assertTrue(long.cues.last().startAtMs > long.totalMs * 3 / 4)
    }

    @Test
    fun selectEvenlyAndInterleave() {
        with(MeditationTimelineBuilder) {
            assertEquals(listOf(1, 5), listOf(1, 2, 3, 4, 5).selectEvenly(2))
            assertEquals(listOf(1, 3, 5), listOf(1, 2, 3, 4, 5).selectEvenly(3))
            assertEquals(listOf(1), listOf(1, 2, 3).selectEvenly(1))
        }
        val mixed = MeditationTimelineBuilder.interleave(listOf("a", "b"), listOf("r"), 3)
        assertEquals(listOf("a", "r", "r", "b", "r"), mixed)
        assertEquals(listOf("r1", "r2", "r1"), MeditationTimelineBuilder.interleave(emptyList(), listOf("r1", "r2"), 3))
    }

    @Test
    fun breathPatternsFollowThePhases() {
        val p = BreathPattern.FOUR_SEVEN_EIGHT
        assertEquals(19_000L, p.cycleMs)
        assertEquals(BreathPhase.INHALE, p.positionAt(0).phase)
        assertEquals(0f, p.positionAt(0).fullness, 0.001f)
        assertEquals(1f, p.positionAt(4_000).fullness, 0.001f)
        assertEquals(BreathPhase.HOLD_IN, p.positionAt(6_000).phase)
        assertEquals(BreathPhase.EXHALE, p.positionAt(12_000).phase)
        assertEquals(0.5f, p.positionAt(15_000).fullness, 0.01f)
        assertEquals(BreathPhase.INHALE, p.positionAt(19_500).phase)
        assertEquals(BreathPhase.HOLD_OUT, BreathPattern.BOX.positionAt(13_000).phase)
        assertEquals(0f, BreathPattern.BOX.positionAt(13_000).fullness, 0.001f)
    }

    @Test
    fun typesParseFromToolValuesAndLoosePhrases() {
        assertEquals(MeditationType.BREATH_478, MeditationType.parse("respiracion_478"))
        assertEquals(MeditationType.BREATH_478, MeditationType.parse("4-7-8"))
        assertEquals(MeditationType.BOX_BREATHING, MeditationType.parse("respiración en caja"))
        assertEquals(MeditationType.BODY_SCAN, MeditationType.parse("Escaneo corporal"))
        assertEquals(MeditationType.SLEEP, MeditationType.parse("una respiración para dormir"))
        assertEquals(MeditationType.MINDFULNESS, MeditationType.parse("libre"))
        // The short values of the tool enum.
        assertEquals(MeditationType.BREATH_478, MeditationType.parse("478"))
        assertEquals(MeditationType.BOX_BREATHING, MeditationType.parse("caja"))
        assertEquals(MeditationType.BODY_SCAN, MeditationType.parse("escaneo"))
        assertEquals(MeditationType.SLEEP, MeditationType.parse("dormir"))
        assertEquals(MeditationType.MINDFULNESS, MeditationType.parse(null))
        assertEquals(MeditationType.MINDFULNESS, MeditationType.parse("algo raro"))
        assertEquals(MeditationVoiceChoice.TREKO, MeditationVoiceChoice.fromId(null))
        assertEquals(MeditationVoiceChoice.PHONE, MeditationVoiceChoice.fromId("phone"))
    }
}
