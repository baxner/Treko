package com.treko.assistant.meditation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeditationSessionTest {

    /** Virtual time: sleeping advances it and lets other coroutines run. */
    private class FakeClock : MeditationClock {
        var now = 0L
        override fun nowMs(): Long = now
        override suspend fun sleep(ms: Long) {
            now += ms
            yield()
        }
    }

    /**
     * A voice that takes [speakMs] of virtual time per cue. [failOn] cue numbers fail;
     * [onSpeak] runs before a cue is said (the test can pause or stop from there).
     */
    private class FakeVoice(
        override val label: String,
        private val clock: FakeClock,
        var available: Boolean = true,
        private val speakMs: Long = 3_000L
    ) : MeditationVoice {
        val said = mutableListOf<String>()
        val failOn = mutableSetOf<Int>()
        var onSpeak: (suspend (index: Int, text: String) -> Unit)? = null
        var calls = 0
        @Volatile private var interrupted = false
        var released = false

        override fun isAvailable() = available

        override suspend fun speak(text: String): SpeakResult {
            val index = calls++
            interrupted = false
            onSpeak?.invoke(index, text)
            if (interrupted) return SpeakResult.CANCELLED
            if (index in failOn) {
                clock.sleep(1_000L)
                return SpeakResult.FAILED
            }
            var left = speakMs
            while (left > 0) {
                clock.sleep(250L)
                left -= 250L
                if (interrupted) return SpeakResult.CANCELLED
            }
            said += text
            return SpeakResult.SAID
        }

        var warmed = false

        override fun warmUp() {
            warmed = true
        }

        override fun stop() {
            interrupted = true
        }

        override fun release() {
            released = true
        }
    }

    private class FakeRoom(private val musicOk: Boolean = true) : MeditationRoom {
        val events = mutableListOf<String>()
        var prepareHang: CompletableDeferred<Unit>? = null
        override suspend fun prepare(): Boolean {
            events += "prepare"
            prepareHang?.await()
            return musicOk
        }
        override suspend fun duck() {
            events += "duck"
        }
        override suspend fun unduck() {
            events += "unduck"
        }
        var completed: Boolean? = null
        override suspend fun finish(completed: Boolean) {
            this.completed = completed
            events += "finish"
        }
    }

    private val lines = MeditationLines(musicFailed = "Sin música.", closing = "Hemos terminado.")

    private val texts = MeditationScriptTexts(
        intro = listOf("Intro uno.", "Intro dos."),
        body = listOf("Cuerpo uno.", "Cuerpo dos.", "Cuerpo tres."),
        reminders = listOf("Recuerda respirar.")
    )

    private fun timeline(minutes: Int = 3) = MeditationTimelineBuilder.build(MeditationType.MINDFULNESS, texts, minutes)

    private class Rig(
        val clock: FakeClock,
        val treko: FakeVoice,
        val phone: FakeVoice,
        val room: FakeRoom,
        val session: MeditationSession,
        val log: MutableList<String>,
        val idle: java.util.concurrent.atomic.AtomicBoolean
    )

    private fun rig(cuesWithTreko: Boolean = true, musicOk: Boolean = true, minutes: Int = 3): Rig {
        val clock = FakeClock()
        val treko = FakeVoice("treko", clock)
        val phone = FakeVoice("phone", clock)
        val room = FakeRoom(musicOk)
        val log = mutableListOf<String>()
        val idle = java.util.concurrent.atomic.AtomicBoolean(true)
        val session = MeditationSession(
            timeline = timeline(minutes),
            cuesWithTreko = cuesWithTreko,
            trekoVoice = treko,
            phoneVoice = phone,
            room = room,
            lines = lines,
            clock = clock,
            assistantIdle = { idle.get() },
            log = { log += it }
        )
        return Rig(clock, treko, phone, room, session, log, idle)
    }

    private val Rig.cueTexts get() = session.timeline.cues.map { it.text }

    @Test
    fun runsEveryCueOnScheduleThenRestoresAndCloses() = runBlocking {
        val r = rig()
        val outcome = r.session.run()
        assertTrue(outcome.finishedNaturally)
        assertEquals(1.0, outcome.completedFraction, 0.0)
        assertEquals(r.cueTexts + "Hemos terminado.", r.treko.said)
        assertTrue(r.phone.said.isEmpty())
        // Room prepared once, restored once, before the closing line.
        assertEquals(listOf("prepare", "finish"), r.room.events)
        assertEquals(MeditationState.IDLE, r.session.snapshot.value.state)
        // The session lasts what was asked (plus the closing line).
        val total = r.session.timeline.totalMs
        assertTrue("ran ${r.clock.now} ms", r.clock.now in (total - 20_000L)..(total + 10_000L))
        assertTrue(r.phone.released)
    }

    @Test
    fun phoneVoiceChoiceReadsCuesButTrekoCloses() = runBlocking {
        val r = rig(cuesWithTreko = false)
        r.session.run()
        assertEquals(r.cueTexts, r.phone.said)
        assertEquals(listOf("Hemos terminado."), r.treko.said)
        assertTrue(r.session.snapshot.value.usingPhoneVoice)
    }

    @Test
    fun failedMusicIsMentionedOnceAndTheSessionGoesOn() = runBlocking {
        val r = rig(musicOk = false)
        r.session.run()
        assertEquals("Sin música.", r.treko.said.first())
        assertEquals(1, r.treko.said.count { it == "Sin música." })
        assertTrue(r.treko.said.containsAll(r.cueTexts))
    }

    @Test
    fun pauseMidCueDucksWaitsAndRepeatsTheCueOnResume() = runBlocking {
        val r = rig()
        val target = r.cueTexts[2]
        var pausedAt = -1L
        r.treko.onSpeak = { _, text ->
            if (text == target && pausedAt < 0) {
                pausedAt = r.clock.now
                r.session.pause(PauseReason.USER)
            }
        }
        val run = async { r.session.run() }
        while (r.session.snapshot.value.state != MeditationState.PAUSED) yield()
        val activeAtPause = r.session.activeMs()
        // Ten virtual minutes paused: none of it counts.
        while (r.clock.now < pausedAt + 600_000L) yield()
        assertEquals(activeAtPause, r.session.activeMs())
        assertEquals(PauseReason.USER, r.session.currentPauseReason)
        assertTrue(r.session.resume())
        val outcome = run.await()
        assertTrue(outcome.finishedNaturally)
        assertEquals(listOf("prepare", "duck", "unduck", "finish"), r.room.events)
        // The interrupted cue was said once, in full, after resuming; nothing was skipped.
        assertEquals(r.cueTexts + "Hemos terminado.", r.treko.said)
        // An interruption is not a voice failure.
        assertEquals(0, r.session.trekoFailures)
        assertFalse(r.session.trekoVoiceDisabled)
    }

    @Test
    fun stopRestoresEverythingWithoutTheClosingLine() = runBlocking {
        val r = rig(minutes = 10)
        r.treko.onSpeak = { index, _ -> if (index == 3) r.session.stop() }
        val outcome = r.session.run()
        assertFalse(outcome.finishedNaturally)
        assertTrue(outcome.completedFraction < 0.8)
        assertEquals(listOf("prepare", "finish"), r.room.events)
        assertFalse("Hemos terminado." in r.treko.said)
        assertEquals(3, r.treko.said.size)
        assertEquals(MeditationState.IDLE, r.session.snapshot.value.state)
        assertFalse(r.session.pause(PauseReason.USER))
    }

    @Test
    fun stopWhilePausedStillRestores() = runBlocking {
        val r = rig()
        r.treko.onSpeak = { index, _ -> if (index == 1) r.session.pause(PauseReason.INTERRUPTION) }
        val run = async { r.session.run() }
        while (r.session.snapshot.value.state != MeditationState.PAUSED) yield()
        assertEquals(PauseReason.INTERRUPTION, r.session.currentPauseReason)
        // A user "pausa" during an interruption sticks (no auto-resume).
        r.session.pause(PauseReason.USER)
        assertEquals(PauseReason.USER, r.session.currentPauseReason)
        // Stopped while it waits out the pause (music ducked).
        while ("duck" !in r.room.events) yield()
        r.session.stop()
        val outcome = run.await()
        assertFalse(outcome.finishedNaturally)
        assertEquals(false, r.room.completed)
        assertEquals(listOf("prepare", "duck", "finish"), r.room.events)
    }

    @Test
    fun twoTrekoFailuresSwitchToThePhoneForTheRest() = runBlocking {
        val r = rig()
        r.treko.failOn += setOf(1, 2)
        r.session.run()
        val cues = r.cueTexts
        // Each failed cue is repeated with the phone, so nothing is lost.
        assertEquals(listOf(cues[0]), r.treko.said)
        assertEquals(cues.drop(1) + "Hemos terminado.", r.phone.said)
        assertEquals(2, r.session.trekoFailures)
        assertTrue(r.session.trekoVoiceDisabled)
        assertTrue(r.log.any { it.contains("failed 2 times") })
        assertEquals(3, r.treko.calls)
    }

    @Test
    fun oneFailureKeepsTrekosVoice() = runBlocking {
        val r = rig()
        r.treko.failOn += 1
        r.session.run()
        val cues = r.cueTexts
        assertEquals(listOf(cues[1]), r.phone.said)
        assertEquals(cues.filterIndexed { i, _ -> i != 1 } + "Hemos terminado.", r.treko.said)
        assertFalse(r.session.trekoVoiceDisabled)
    }

    @Test
    fun disconnectedXiaozhiUsesThePhoneAtOnce() = runBlocking {
        val r = rig()
        r.treko.available = false
        r.session.run()
        assertEquals(0, r.treko.calls)
        assertEquals(r.cueTexts + "Hemos terminado.", r.phone.said)
        assertTrue(r.session.snapshot.value.usingPhoneVoice)
    }

    @Test
    fun cancellationStillRestoresTheRoom() = runBlocking {
        val r = rig()
        val started = CompletableDeferred<Unit>()
        r.treko.onSpeak = { index, _ -> if (index == 1) started.complete(Unit) }
        val run = async { r.session.run() }
        started.await()
        run.cancel()
        try {
            run.await()
        } catch (_: Exception) {
        }
        assertEquals("finish", r.room.events.last())
        assertEquals(MeditationState.IDLE, r.session.snapshot.value.state)
    }

    @Test
    fun wakeWordOverTheClosingLineIsNoVoiceFailure() = runBlocking {
        val r = rig()
        r.treko.onSpeak = { _, text ->
            if (text == "Hemos terminado.") {
                // The user says "Treko" while the closing line is being said.
                r.idle.set(false)
                r.session.cancelClosing()
            }
        }
        val outcome = r.session.run()
        assertTrue(outcome.finishedNaturally)
        // Not repeated with the phone's voice over the user's turn, not counted as a failure.
        assertTrue(r.phone.said.isEmpty())
        assertEquals(0, r.session.trekoFailures)
        assertFalse("Hemos terminado." in r.treko.said)
    }

    @Test
    fun aFailedCueIsNotRepeatedWithThePhoneWhileTheAssistantIsBusy() = runBlocking {
        val r = rig()
        r.treko.failOn += 1
        // The user is talking while cue 1 fails; quiet again for the rest of the session.
        r.treko.onSpeak = { index, _ -> r.idle.set(index != 1) }
        r.session.run()
        assertEquals(0, r.phone.calls)
        assertTrue(r.log.any { it.contains("repeat skipped") })
        assertEquals(1, r.session.trekoFailures)
    }

    @Test
    fun aCueStillConnectingIsCancelledByPauseAndNotSaidWhilePaused() = runBlocking {
        val clock = FakeClock()
        val said = mutableListOf<String>()
        var cancelledOnce = 0
        val connecting = CompletableDeferred<Unit>()
        // Treko's voice spends a while reconnecting before it speaks (cancellable, no stop() hook).
        val slow = object : MeditationVoice {
            override val label = "treko"
            override fun isAvailable() = true
            override suspend fun speak(text: String): SpeakResult {
                try {
                    if (said.size == 1 && cancelledOnce == 0) connecting.complete(Unit)
                    repeat(40) { clock.sleep(250L) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelledOnce++
                    throw e
                }
                said += text
                return SpeakResult.SAID
            }
            override fun stop() {}
        }
        val phone = FakeVoice("phone", clock)
        val session = MeditationSession(timeline(), true, slow, phone, FakeRoom(), lines, clock)
        val run = async { session.run() }
        connecting.await()
        assertTrue(session.pause(PauseReason.USER))
        val pausedSaid = said.size
        val until = clock.now + 120_000L
        while (clock.now < until) yield()
        // Nothing was said during the pause: the cue that was connecting was cancelled.
        assertEquals(pausedSaid, said.size)
        assertEquals(1, cancelledOnce)
        assertTrue(session.resume())
        run.await()
        assertEquals(timeline().cues.map { it.text } + "Hemos terminado.", said)
        assertTrue(phone.said.isEmpty())
        assertEquals(0, session.trekoFailures)
    }

    @Test
    fun aCallDuringAWakeWordPauseIsNotResumedByTheWakeWordRule() = runBlocking {
        val r = rig()
        r.treko.onSpeak = { index, _ -> if (index == 1) r.session.pause(PauseReason.INTERRUPTION) }
        val run = async { r.session.run() }
        while (r.session.snapshot.value.state != MeditationState.PAUSED) yield()
        r.session.pause(PauseReason.CALL)
        assertEquals(PauseReason.CALL, r.session.currentPauseReason)
        // A weaker reason never replaces a stronger one.
        r.session.pause(PauseReason.INTERRUPTION)
        assertEquals(PauseReason.CALL, r.session.currentPauseReason)
        r.session.stop()
        run.await()
        assertTrue(r.phone.warmed)
    }

    @Test
    fun cancelledWhilePreparingRestoresAndSaysNothing() = runBlocking {
        val r = rig()
        r.room.prepareHang = CompletableDeferred()
        val run = async { r.session.run() }
        while (r.room.events.isEmpty()) yield()
        run.cancel()
        try {
            run.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertEquals(listOf("prepare", "finish"), r.room.events)
        assertTrue(r.treko.said.isEmpty())
        assertTrue(r.phone.said.isEmpty())
    }

    @Test
    fun theRoomIsToldWhetherTheSessionRanToItsEnd() = runBlocking {
        val full = rig()
        full.session.run()
        assertEquals(true, full.room.completed)

        val early = rig()
        early.treko.onSpeak = { index, _ -> if (index == 1) early.session.stop() }
        early.session.run()
        assertEquals(false, early.room.completed)

        // "Buenas noches" during a sleep session: ended as if complete, without the closing line.
        val asleep = rig()
        asleep.treko.onSpeak = { index, _ -> if (index == 1) asleep.session.stop(asCompleted = true) }
        val outcome = asleep.session.run()
        assertFalse(outcome.finishedNaturally)
        assertEquals(true, asleep.room.completed)
        assertFalse("Hemos terminado." in asleep.treko.said)
    }

    @Test
    fun snapshotExtrapolatesRemainingTimeWhileRunning() {
        val s = MeditationSnapshot(MeditationState.RUNNING, MeditationType.BOX_BREATHING, totalMs = 60_000L, activeMs = 10_000L, stampMs = 1_000L)
        assertEquals(15_000L, s.activeAt(6_000L))
        assertEquals(45_000L, s.remainingAt(6_000L))
        val paused = s.copy(state = MeditationState.PAUSED)
        assertEquals(10_000L, paused.activeAt(99_000L))
        assertEquals(0L, s.remainingAt(500_000L))
    }
}
