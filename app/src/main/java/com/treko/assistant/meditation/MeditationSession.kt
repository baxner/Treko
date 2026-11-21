package com.treko.assistant.meditation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class MeditationState { IDLE, PREPARING, RUNNING, PAUSED, ENDING }

/**
 * Why a session is paused: [USER] waits for "sigue"; the others may resume by themselves
 * ([FOCUS]: another app took the audio for a while). A stronger reason replaces a weaker
 * one ([rank]), so a call during a wake-word pause does not resume by itself mid-call.
 */
enum class PauseReason(val rank: Int) { USER(2), INTERRUPTION(0), CALL(1), FOCUS(1) }

/** Time source of a session; tests use a fake one. */
interface MeditationClock {
    fun nowMs(): Long
    suspend fun sleep(ms: Long)

    object System : MeditationClock {
        override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
        override suspend fun sleep(ms: Long) = delay(ms)
    }
}

/** Everything in the room a session changes: music and lights. Every call must be safe to repeat. */
interface MeditationRoom {
    /** Dims lights and starts music. False when music was wanted but could not start. */
    suspend fun prepare(): Boolean

    /** Lower the music (session paused). */
    suspend fun duck()

    /** Music back to the session level. */
    suspend fun unduck()

    /**
     * Fade the music out and put lights and volume back as they were. Never throws.
     * [completed]: the session ran to its end (a sleep session then turns its lights off
     * instead of putting them back); false when it was stopped early.
     */
    suspend fun finish(completed: Boolean)
}

/** Lines the session says itself (from string resources). */
data class MeditationLines(
    /** Said once, before the first cue, when the music could not start. */
    val musicFailed: String,
    /** Treko's closing line after a session that ran to the end. */
    val closing: String
)

/** What the UI shows. [activeMs] was measured at [stampMs] (clock time). */
data class MeditationSnapshot(
    val state: MeditationState = MeditationState.IDLE,
    val type: MeditationType? = null,
    val totalMs: Long = 0L,
    val activeMs: Long = 0L,
    val stampMs: Long = 0L,
    val placeName: String? = null,
    val pauseReason: PauseReason? = null,
    val usingPhoneVoice: Boolean = false
) {
    val isActive: Boolean get() = state != MeditationState.IDLE

    /** Active time at [nowMs], extrapolated while running. */
    fun activeAt(nowMs: Long): Long =
        if (state == MeditationState.RUNNING) (activeMs + (nowMs - stampMs).coerceAtLeast(0L)).coerceAtMost(totalMs) else activeMs

    fun remainingAt(nowMs: Long): Long = (totalMs - activeAt(nowMs)).coerceAtLeast(0L)
}

/** How a session ended. [completedFraction] is the share of the planned time that ran. */
data class MeditationOutcome(val finishedNaturally: Boolean, val completedFraction: Double)

/**
 * One guided session: IDLE → PREPARING (room) → RUNNING ⇄ PAUSED → ENDING (restore) → IDLE.
 *
 * Cues are said at their planned offsets of active time; time spent paused does not count.
 * Cues use Treko's voice when [cuesWithTreko], else the phone's. If Treko's voice is not
 * connected, or fails twice, the phone's voice takes over for the rest of the session
 * (a failed cue is repeated with it, so nothing is lost). The closing line is Treko's.
 * The room is always restored, also when stopped or cancelled.
 */
class MeditationSession(
    val timeline: MeditationTimeline,
    private val cuesWithTreko: Boolean,
    private val trekoVoice: MeditationVoice,
    private val phoneVoice: MeditationVoice,
    private val room: MeditationRoom,
    private val lines: MeditationLines,
    private val clock: MeditationClock = MeditationClock.System,
    private val placeName: String? = null,
    /** Suspends until the assistant is not talking or listening (before each cue). */
    private val awaitAssistantIdle: suspend () -> Unit = {},
    /** True while the assistant is not talking or listening (checked before a phone-voice repeat). */
    private val assistantIdle: () -> Boolean = { true },
    private val log: (String) -> Unit = {}
) {
    private val _snapshot = MutableStateFlow(
        MeditationSnapshot(type = timeline.type, totalMs = timeline.totalMs, placeName = placeName)
    )
    val snapshot: StateFlow<MeditationSnapshot> = _snapshot.asStateFlow()

    private val lock = Any()
    @Volatile private var state = MeditationState.IDLE
    @Volatile private var pauseReason: PauseReason? = null
    @Volatile private var stopRequested = false
    @Volatile private var endAsCompleted = false
    @Volatile private var started = false
    private var accumulatedMs = 0L
    private var runningSinceMs = 0L
    @Volatile private var speakingVoice: MeditationVoice? = null

    /** The cue being said; pause and stop cancel it, also before the voice really began. */
    @Volatile private var cueJob: Deferred<SpeakResult>? = null

    /** Bumped by every pause, stop or cut closing: a cue started before it must not be said. */
    private val cueGeneration = AtomicInteger()

    /** The closing line was cut (wake word or an alert while ending): it is not said again. */
    @Volatile private var closingCancelled = false

    /** Treko's voice is no longer used this session (disconnected or failed twice). */
    @Volatile var trekoVoiceDisabled = false
        private set
    @Volatile var trekoFailures = 0
        private set

    val currentState: MeditationState get() = state
    val currentPauseReason: PauseReason? get() = pauseReason

    fun activeMs(): Long = synchronized(lock) {
        accumulatedMs + if (state == MeditationState.RUNNING) clock.nowMs() - runningSinceMs else 0L
    }

    private fun setState(newState: MeditationState, reason: PauseReason? = null) = synchronized(lock) {
        val now = clock.nowMs()
        if (state == MeditationState.RUNNING) accumulatedMs += now - runningSinceMs
        if (newState == MeditationState.RUNNING) runningSinceMs = now
        state = newState
        pauseReason = if (newState == MeditationState.PAUSED) reason else null
        publishLocked(now)
    }

    private fun publish() = synchronized(lock) { publishLocked(clock.nowMs()) }

    private fun publishLocked(now: Long) {
        val active = accumulatedMs + if (state == MeditationState.RUNNING) now - runningSinceMs else 0L
        _snapshot.value = MeditationSnapshot(
            state = state,
            type = timeline.type,
            totalMs = timeline.totalMs,
            activeMs = active.coerceAtMost(timeline.totalMs),
            stampMs = now,
            placeName = placeName,
            pauseReason = pauseReason,
            usingPhoneVoice = !cuesWithTreko || trekoVoiceDisabled
        )
    }

    /** Pauses a preparing or running session; false when there is nothing to pause. */
    fun pause(reason: PauseReason): Boolean {
        synchronized(lock) {
            if (stopRequested) return false
            if (state == MeditationState.PAUSED) {
                // A user "pausa" wins over an interruption, a call over the wake word.
                val now = pauseReason
                if (now == null || reason.rank > now.rank) {
                    pauseReason = reason
                    publishLocked(clock.nowMs())
                }
                return true
            }
            if (state != MeditationState.RUNNING && state != MeditationState.PREPARING) return false
            setState(MeditationState.PAUSED, reason)
        }
        log("Meditation paused ($reason).")
        cutCue()
        return true
    }

    /** The closing line is being said and must stop (the user talks, an alert): it is not repeated. */
    fun cancelClosing() {
        if (state != MeditationState.ENDING) return
        closingCancelled = true
        log("Closing line cut.")
        cutCue()
    }

    /** Stops the cue in flight: the voice first (synchronous), then its coroutine. */
    private fun cutCue() {
        cueGeneration.incrementAndGet()
        speakingVoice?.stop()
        cueJob?.cancel()
    }

    fun resume(): Boolean {
        synchronized(lock) {
            if (state != MeditationState.PAUSED || stopRequested) return false
            setState(MeditationState.RUNNING)
        }
        log("Meditation resumed.")
        return true
    }

    /**
     * Ends the session early; [run] then restores the room and returns. [asCompleted]: end
     * the room as after a full session (no closing line), e.g. "buenas noches" during a sleep
     * session turns its lights off instead of back on.
     */
    fun stop(asCompleted: Boolean = false): Boolean {
        synchronized(lock) {
            if (stopRequested || state == MeditationState.IDLE && started) return false
            stopRequested = true
            endAsCompleted = asCompleted
        }
        log("Meditation stop requested.")
        cutCue()
        return true
    }

    private val interrupted: Boolean get() = stopRequested || state == MeditationState.PAUSED

    /** Runs the whole session; call once. The room is restored however it ends. */
    suspend fun run(): MeditationOutcome {
        synchronized(lock) {
            check(!started) { "A session runs once" }
            started = true
        }
        var natural = false
        try {
            setState(MeditationState.PREPARING)
            // The phone's voice may have to repeat a cue at any moment: start its engine now.
            phoneVoice.warmUp()
            // Stopped before it began: nothing to change, nothing to put back.
            val musicOk = if (stopRequested) true else try {
                room.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Room preparation failed: ${e.message}")
                true
            }
            synchronized(lock) {
                if (state == MeditationState.PREPARING) setState(MeditationState.RUNNING)
            }
            if (!stopRequested && !musicOk) sayOnce(lines.musicFailed, withTreko = cuesWithTreko)

            for (cue in timeline.cues) {
                if (stopRequested) break
                waitUntilActive(cue.startAtMs)
                if (stopRequested) break
                sayOnce(cue.text, withTreko = cuesWithTreko)
                if (stopRequested) break
                waitActiveFor(POST_CUE_GAP_MS)
            }
            if (!stopRequested) waitUntilActive(timeline.closingAtMs)
            natural = !stopRequested
            setState(MeditationState.ENDING)
        } finally {
            withContext(NonCancellable) {
                if (state != MeditationState.ENDING) setState(MeditationState.ENDING)
                try {
                    room.finish(completed = natural || endAsCompleted)
                } catch (e: Exception) {
                    log("Room restore failed: ${e.message}")
                }
                if (natural && !closingCancelled) {
                    // Closing line with Treko's voice (the phone's if Treko's is out), once
                    // the assistant is quiet; never over the user's own turn.
                    val gen = cueGeneration.get()
                    awaitAssistantIdle()
                    if (!closingCancelled && cueGeneration.get() == gen && assistantIdle()) {
                        speakRouted(lines.closing, withTreko = true, gen = gen)
                    } else {
                        log("Closing line skipped: the assistant is busy.")
                    }
                }
                trekoVoice.stop()
                phoneVoice.release()
                if (natural) synchronized(lock) { accumulatedMs = timeline.totalMs }
                setState(MeditationState.IDLE)
            }
        }
        val fraction = if (natural) 1.0 else (activeMs().toDouble() / timeline.totalMs).coerceIn(0.0, 1.0)
        log("Meditation ended (natural=$natural, ${(fraction * 100).toInt()}%).")
        return MeditationOutcome(natural, fraction)
    }

    /** Says [text], waiting out pauses; an interrupted cue is said again after resuming. */
    private suspend fun sayOnce(text: String, withTreko: Boolean) {
        while (!stopRequested) {
            holdWhilePaused()
            if (stopRequested) return
            val gen = cueGeneration.get()
            awaitAssistantIdle()
            if (interrupted || cueGeneration.get() != gen) continue
            if (speakRouted(text, withTreko, gen)) return
            if (!interrupted) return
        }
    }

    /** A pause, stop or cut closing happened since [gen] was read. */
    private fun cutSince(gen: Int): Boolean = cueGeneration.get() != gen

    /**
     * Says [text] with [voice] in a child job that [cutCue] cancels, so a cue that is still
     * connecting or waiting is never said after a pause or stop.
     */
    private suspend fun speakWith(voice: MeditationVoice, text: String, gen: Int): SpeakResult {
        if (cutSince(gen)) return SpeakResult.CANCELLED
        speakingVoice = voice
        return try {
            coroutineScope {
                val job = async {
                    try {
                        voice.speak(text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Meditation voice ${voice.label} failed: ${e.message}")
                        SpeakResult.FAILED
                    }
                }
                cueJob = job
                // Cut between the check above and here: cancel it now.
                if (cutSince(gen)) job.cancel()
                try {
                    job.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    SpeakResult.CANCELLED
                }
            }
        } finally {
            cueJob = null
            speakingVoice = null
        }
    }

    /**
     * Speaks with the right voice. Returns false only when interrupted (pause or stop);
     * a voice failure is handled here (fallback to the phone) and counts as said.
     */
    private suspend fun speakRouted(text: String, withTreko: Boolean, gen: Int): Boolean {
        var useTreko = withTreko && !trekoVoiceDisabled
        if (useTreko && !trekoVoice.isAvailable()) {
            trekoVoiceDisabled = true
            useTreko = false
            log("Treko's voice is not connected; the phone's voice reads the rest of the session.")
            publish()
        }
        val voice = if (useTreko) trekoVoice else phoneVoice
        val result = speakWith(voice, text, gen)
        if (result == SpeakResult.SAID) return true
        // Cut on purpose (pause, stop, the wake word): an interruption, not a voice failure.
        if (result == SpeakResult.CANCELLED || cutSince(gen) || closingCancelled) return !(interrupted && state != MeditationState.ENDING)
        if (interrupted && state != MeditationState.ENDING) return false
        if (!useTreko) {
            log("Phone voice could not say a cue; continuing.")
            return true
        }
        trekoFailures++
        log("Treko's voice failed on a cue ($trekoFailures).")
        if (trekoFailures >= MAX_TREKO_FAILURES) {
            trekoVoiceDisabled = true
            log("Treko's voice failed $trekoFailures times; the phone's voice reads the rest of the session.")
            publish()
        }
        // The phone's voice has no echo reference: never speak it while the mic may be open.
        awaitAssistantIdle()
        if (cutSince(gen) || closingCancelled || !assistantIdle()) {
            log("Phone voice repeat skipped: the assistant is busy.")
            return !(interrupted && state != MeditationState.ENDING)
        }
        val phone = speakWith(phoneVoice, text, gen)
        return phone == SpeakResult.SAID || !(interrupted && state != MeditationState.ENDING)
    }

    private suspend fun holdWhilePaused() {
        if (state != MeditationState.PAUSED) return
        try {
            room.duck()
        } catch (e: Exception) {
            log("Could not lower the music: ${e.message}")
        }
        while (state == MeditationState.PAUSED && !stopRequested) clock.sleep(TICK_MS)
        if (!stopRequested) {
            try {
                room.unduck()
            } catch (e: Exception) {
                log("Could not raise the music: ${e.message}")
            }
        }
    }

    private suspend fun waitUntilActive(targetMs: Long) {
        while (!stopRequested) {
            if (state == MeditationState.PAUSED) {
                holdWhilePaused()
                continue
            }
            val left = targetMs - activeMs()
            if (left <= 0) return
            clock.sleep(left.coerceAtMost(TICK_MS))
            publish()
        }
    }

    private suspend fun waitActiveFor(ms: Long) = waitUntilActive(activeMs() + ms)

    companion object {
        const val MAX_TREKO_FAILURES = 2
        private const val TICK_MS = 250L

        /** Least silence after any cue, even when the session runs late. */
        private const val POST_CUE_GAP_MS = 4_000L
    }
}
