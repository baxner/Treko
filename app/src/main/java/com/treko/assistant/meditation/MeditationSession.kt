package com.treko.assistant.meditation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class MeditationState { IDLE, PREPARING, RUNNING, PAUSED, ENDING }

/** Why a session is paused: [USER] waits for "sigue"; the others may resume by themselves. */
enum class PauseReason { USER, INTERRUPTION, CALL }

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

    /** Fade the music out and put lights and volume back as they were. Never throws. */
    suspend fun finish()
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
    @Volatile private var started = false
    private var accumulatedMs = 0L
    private var runningSinceMs = 0L
    @Volatile private var speakingVoice: MeditationVoice? = null

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
                // A user "pausa" wins over an interruption, so it does not resume by itself.
                if (reason == PauseReason.USER) {
                    pauseReason = reason
                    publishLocked(clock.nowMs())
                }
                return true
            }
            if (state != MeditationState.RUNNING && state != MeditationState.PREPARING) return false
            setState(MeditationState.PAUSED, reason)
        }
        log("Meditation paused ($reason).")
        speakingVoice?.stop()
        return true
    }

    fun resume(): Boolean {
        synchronized(lock) {
            if (state != MeditationState.PAUSED || stopRequested) return false
            setState(MeditationState.RUNNING)
        }
        log("Meditation resumed.")
        return true
    }

    /** Ends the session early; [run] then restores the room and returns. */
    fun stop(): Boolean {
        synchronized(lock) {
            if (stopRequested || state == MeditationState.IDLE && started) return false
            stopRequested = true
        }
        log("Meditation stop requested.")
        speakingVoice?.stop()
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
            // Stopped before it began: nothing to change, nothing to put back.
            val musicOk = if (stopRequested) true else try {
                room.prepare()
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
                    room.finish()
                } catch (e: Exception) {
                    log("Room restore failed: ${e.message}")
                }
                if (natural) {
                    // Closing line with Treko's voice (the phone's if Treko's is out).
                    speakRouted(lines.closing, withTreko = true)
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
            awaitAssistantIdle()
            if (interrupted) continue
            if (speakRouted(text, withTreko)) return
            if (!interrupted) return
        }
    }

    /**
     * Speaks with the right voice. Returns false only when interrupted (pause or stop);
     * a voice failure is handled here (fallback to the phone) and counts as said.
     */
    private suspend fun speakRouted(text: String, withTreko: Boolean): Boolean {
        var useTreko = withTreko && !trekoVoiceDisabled
        if (useTreko && !trekoVoice.isAvailable()) {
            trekoVoiceDisabled = true
            useTreko = false
            log("Treko's voice is not connected; the phone's voice reads the rest of the session.")
            publish()
        }
        val voice = if (useTreko) trekoVoice else phoneVoice
        speakingVoice = voice
        val ok = try {
            voice.speak(text)
        } catch (e: Exception) {
            log("Meditation voice ${voice.label} failed: ${e.message}")
            false
        } finally {
            speakingVoice = null
        }
        if (ok) return true
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
        speakingVoice = phoneVoice
        val phoneOk = try {
            phoneVoice.speak(text)
        } catch (_: Exception) {
            false
        } finally {
            speakingVoice = null
        }
        return phoneOk || !(interrupted && state != MeditationState.ENDING)
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
