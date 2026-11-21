package com.treko.assistant.meditation

import android.content.Context
import android.media.AudioManager
import androidx.annotation.ArrayRes
import com.treko.assistant.R
import com.treko.core.common.data.TrekoHabitManager
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoSkills
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.mcp.RadioBrowserClient
import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.PhoneReason
import com.treko.core.mcp.rooms.PlaceDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * The `meditation` tool and the session it runs: asks where the user is when the home has
 * several rooms, prepares the room, runs the [MeditationSession], pauses it for the wake
 * word, calls and urgent alerts, and marks a meditation habit at the end.
 *
 * The assistant service is the [Host]: it owns Xiaozhi, Home Assistant and the turn state.
 */
class MeditationController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host
) {
    interface Host {
        fun haClient(): HomeAssistantClient?
        fun detectedSkills(): Set<String>
        val xiaozhiChannel: XiaozhiMeditationVoice.Channel
        val radio: RadioBrowserClient

        /** True while the assistant is neither listening, thinking nor speaking. */
        fun isAssistantIdle(): Boolean

        /** Plays [query] on the Home Assistant player [entityId]; true when it started. */
        suspend fun playOnPlayer(query: String, entityId: String): Boolean
    }

    private val ctx = context.applicationContext
    private val store = MeditationRestoreStore(SharedPrefsMeditationKeyValue(ctx))
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var session: MeditationSession? = null
    private var sessionJob: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var pendingStart: MeditationPlanner.PendingStart? = null

    /** A session is preparing, running, paused or ending. */
    val isActive: Boolean get() = session != null

    /**
     * True while Treko must not open the mic after speaking: the session is running (cues
     * and the opening line end in silence, with the wake word armed). Paused = normal talk.
     */
    fun holdsMicClosed(): Boolean = session?.let { it.currentState != MeditationState.PAUSED } ?: false

    // ---- Tool ----

    suspend fun handleTool(args: JSONObject): String {
        val action = normalize(args.optString("action", "start")).ifBlank { "start" }
        return when (action) {
            "start", "iniciar", "empezar", "comenzar", "run" -> start(args)
            "stop", "para", "parar", "detener", "terminar", "termina", "cancel", "cancelar", "end" -> stopFromTool()
            "pause", "pausa", "pausar" -> pauseFromTool()
            "resume", "sigue", "seguir", "continuar", "continua", "reanudar" -> resumeFromTool()
            else -> status()
        }
    }

    private suspend fun start(args: JSONObject): String {
        session?.let { return ctx.getString(R.string.meditation_tool_already, typeName(it.timeline.type), remainingMinutes(it)) }
        val prefs = TrekoPreferences(ctx)
        val (type, minutes) = MeditationPlanner.resolveStart(
            rawType = args.optString("type", "").takeIf { it.isNotBlank() },
            rawMinutes = args.optInt("minutes", 0).takeIf { it > 0 },
            pending = pendingStart,
            nowMs = System.currentTimeMillis()
        )
        val place = args.optString("place", "").trim().takeIf { it.isNotEmpty() }
        val settings = MeditationRoomSettings(
            adjustLights = prefs.meditationAdjustLights,
            playMusic = prefs.meditationMusic,
            preferAlexa = TrekoSkills.ALEXA_MEDIA in host.detectedSkills()
        )
        val ha = host.haClient()
        val areas = if (ha != null && (settings.adjustLights || settings.playMusic)) ha.rooms.listRoomCandidates() else null
        val decision = MeditationPlanner.decide(areas, settings, place, prefs.lastMeditationPlace.takeIf { it.isNotBlank() })
        TrekoLogBuffer.i(TAG, "Meditation start: type=${type.id} minutes=$minutes decision=${decision.javaClass.simpleName}")
        return when (decision) {
            is PlaceDecision.Ask -> {
                pendingStart = MeditationPlanner.PendingStart(type, minutes, System.currentTimeMillis())
                askText(decision)
            }
            is PlaceDecision.UseArea -> {
                pendingStart = null
                prefs.lastMeditationPlace = decision.area.name
                launch(type, minutes, decision.area, ha, settings, prefs)
                val opening = ctx.getString(R.string.meditation_open_place, minutes, typeName(type), decision.area.name)
                sayText(opening)
            }
            is PlaceDecision.UsePhone -> {
                pendingStart = null
                if (decision.reason == PhoneReason.USER_CHOSE_PHONE) prefs.lastMeditationPlace = ""
                launch(type, minutes, null, ha, settings, prefs)
                val opening = if (decision.reason == PhoneReason.USER_CHOSE_PHONE) {
                    ctx.getString(R.string.meditation_open_phone, minutes, typeName(type))
                } else {
                    ctx.getString(R.string.meditation_open, minutes, typeName(type))
                }
                sayText(opening)
            }
        }
    }

    private fun askText(ask: PlaceDecision.Ask): String {
        val sb = StringBuilder()
        ask.unmatched?.let { sb.append(ctx.getString(R.string.meditation_tool_ask_unmatched, it)).append(' ') }
        sb.append(ctx.getString(R.string.meditation_tool_ask, ask.options.joinToString(", ")))
        ask.suggestion?.let { sb.append(' ').append(ctx.getString(R.string.meditation_tool_ask_suggest, it)) }
        return sb.toString()
    }

    private fun sayText(line: String): String = ctx.getString(R.string.meditation_tool_say, line)

    private fun stopFromTool(): String {
        val s = session ?: return ctx.getString(R.string.meditation_tool_none)
        s.stop()
        return sayText(ctx.getString(R.string.meditation_close_early))
    }

    private fun pauseFromTool(): String {
        val s = session ?: return ctx.getString(R.string.meditation_tool_none)
        s.pause(PauseReason.USER)
        return ctx.getString(R.string.meditation_tool_paused)
    }

    private fun resumeFromTool(): String {
        val s = session ?: return ctx.getString(R.string.meditation_tool_none)
        return if (s.resume() || s.currentState == MeditationState.RUNNING) {
            ctx.getString(R.string.meditation_tool_resumed)
        } else {
            status()
        }
    }

    private fun status(): String {
        val s = session ?: return ctx.getString(R.string.meditation_tool_none)
        val res = if (s.currentState == MeditationState.PAUSED) R.string.meditation_tool_status_paused else R.string.meditation_tool_status
        return ctx.getString(res, typeName(s.timeline.type), remainingMinutes(s))
    }

    private fun remainingMinutes(s: MeditationSession): Int =
        ((s.timeline.totalMs - s.activeMs()).coerceAtLeast(0L) / 60_000L).toInt() + 1

    // ---- Session ----

    private fun launch(
        type: MeditationType,
        minutes: Int,
        area: HaArea?,
        ha: HomeAssistantClient?,
        settings: MeditationRoomSettings,
        prefs: TrekoPreferences
    ) {
        val timeline = MeditationTimelineBuilder.build(type, scriptTexts(type), minutes)
        val room = HomeMeditationRoom(
            rooms = if (area != null) ha?.rooms else null,
            area = area,
            type = type,
            settings = settings,
            store = store,
            playOnPlayer = { q, id -> host.playOnPlayer(q, id) },
            musicQuery = ctx.getString(if (type == MeditationType.SLEEP) R.string.meditation_music_query_sleep else R.string.meditation_music_query),
            phoneMusic = if (settings.playMusic) MeditationPhonePlayer(host.radio) else null,
            log = { TrekoLogBuffer.i(TAG, it) }
        )
        val s = MeditationSession(
            timeline = timeline,
            cuesWithTreko = MeditationVoiceChoice.fromId(prefs.meditationVoice) == MeditationVoiceChoice.TREKO,
            trekoVoice = XiaozhiMeditationVoice(host.xiaozhiChannel),
            phoneVoice = PhoneTtsMeditationVoice(ctx),
            room = room,
            lines = MeditationLines(
                musicFailed = ctx.getString(R.string.meditation_music_failed),
                closing = ctx.getString(if (type == MeditationType.SLEEP) R.string.meditation_close_sleep else R.string.meditation_close)
            ),
            placeName = area?.name,
            awaitAssistantIdle = { awaitAssistantIdle() },
            log = { TrekoLogBuffer.i(TAG, it) }
        )
        session = s
        val mirror = scope.launch { s.snapshot.collect { _snapshot.value = it } }
        monitorJob?.cancel()
        monitorJob = scope.launch { monitor(s) }
        sessionJob = scope.launch(Dispatchers.Default) {
            try {
                // Let the opening line start first, so the first cue never talks over it.
                delay(OPENING_GRACE_MS)
                val outcome = s.run()
                if (outcome.completedFraction >= MeditationPlanner.HABIT_DONE_FRACTION) markHabitDone()
            } finally {
                monitorJob?.cancel()
                mirror.cancel()
                if (session === s) session = null
                _snapshot.value = MeditationSnapshot()
            }
        }
    }

    /** Before each cue: the assistant quiet for a moment (opening line, a reply after "sigue"). */
    private suspend fun awaitAssistantIdle() {
        var waited = 0L
        var idleFor = 0L
        while (waited < IDLE_WAIT_MAX_MS) {
            if (host.isAssistantIdle()) {
                idleFor += 250L
                if (idleFor >= IDLE_SETTLE_MS) return
            } else {
                idleFor = 0L
            }
            delay(250L)
            waited += 250L
        }
    }

    /**
     * While a session lives: pauses it during a phone call (resumes after), and resumes a
     * session paused by the wake word or an alert once the assistant has been quiet a while.
     */
    private suspend fun monitor(s: MeditationSession) {
        var idleMs = 0L
        var normalMs = 0L
        while (scope.isActive && session === s) {
            delay(MONITOR_TICK_MS)
            val inCall = audioManager?.mode.let { it == AudioManager.MODE_RINGTONE || it == AudioManager.MODE_IN_CALL }
            val state = s.currentState
            if (inCall) {
                normalMs = 0L
                if (state == MeditationState.RUNNING || state == MeditationState.PREPARING) s.pause(PauseReason.CALL)
                continue
            }
            normalMs += MONITOR_TICK_MS
            if (state != MeditationState.PAUSED) {
                idleMs = 0L
                continue
            }
            idleMs = if (host.isAssistantIdle()) idleMs + MONITOR_TICK_MS else 0L
            when (s.currentPauseReason) {
                PauseReason.CALL -> if (normalMs >= CALL_RESUME_MS) s.resume()
                PauseReason.INTERRUPTION -> if (idleMs >= AUTO_RESUME_MS) s.resume()
                else -> Unit
            }
        }
    }

    private fun markHabitDone() {
        try {
            val manager = TrekoHabitManager(ctx)
            manager.ensureCurrentDay()
            val habit = TrekoHabitManager.habitsFlow.value.firstOrNull { MeditationPlanner.isMeditationHabit(it.title) }
                ?: return
            if (!habit.isDone) {
                manager.toggleHabit(habit.id)
                TrekoLogBuffer.i(TAG, "Habit '${habit.title}' marked done after the meditation.")
            }
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Could not mark the meditation habit: ${e.message}")
        }
    }

    // ---- Interruptions ----

    /** The wake word or a tap started a turn: pause (the music ducks); "sigue" resumes. */
    fun onConversationStarted() {
        val s = session ?: return
        if (s.currentState == MeditationState.RUNNING || s.currentState == MeditationState.PREPARING) {
            s.pause(PauseReason.INTERRUPTION)
        }
    }

    /** An urgent alert must be spoken: pause now, resume by itself after it. */
    fun pauseForAlert(): Boolean {
        val s = session ?: return false
        return s.pause(PauseReason.INTERRUPTION) || s.currentState == MeditationState.PAUSED
    }

    // ---- UI ----

    fun stopFromUi() {
        session?.stop()
    }

    fun togglePauseFromUi() {
        val s = session ?: return
        if (s.currentState == MeditationState.PAUSED) s.resume() else s.pause(PauseReason.USER)
    }

    // ---- Service life ----

    /** At service start: put back a room left changed by a session the app did not finish. */
    fun restorePendingOnStart() {
        if (store.load() == null) return
        scope.launch(Dispatchers.IO) {
            repeat(RESTORE_TRIES_ON_START) { attempt ->
                if (attempt > 0) delay(RESTORE_RETRY_MS)
                if (session != null) return@launch
                val result = store.restore(host.haClient()?.rooms)
                TrekoLogBuffer.i(TAG, "Leftover meditation restore: $result.")
                if (result != RestoreResult.KEPT) return@launch
            }
        }
    }

    /** Service going away: stop the session; its restore runs regardless (and is on disk). */
    fun shutdown() {
        session?.stop()
    }

    private fun scriptTexts(type: MeditationType): MeditationScriptTexts {
        val (intro, body, reminders) = when (type) {
            MeditationType.BREATH_478 -> Triple(R.array.meditation_478_intro, R.array.meditation_478_body, R.array.meditation_478_reminders)
            MeditationType.BOX_BREATHING -> Triple(R.array.meditation_box_intro, R.array.meditation_box_body, R.array.meditation_box_reminders)
            MeditationType.BODY_SCAN -> Triple(R.array.meditation_scan_intro, R.array.meditation_scan_body, R.array.meditation_scan_reminders)
            MeditationType.SLEEP -> Triple(R.array.meditation_sleep_intro, R.array.meditation_sleep_body, R.array.meditation_sleep_reminders)
            MeditationType.MINDFULNESS -> Triple(R.array.meditation_mindful_intro, R.array.meditation_mindful_body, R.array.meditation_mindful_reminders)
        }
        return MeditationScriptTexts(array(intro), array(body), array(reminders))
    }

    private fun array(@ArrayRes id: Int): List<String> = ctx.resources.getStringArray(id).toList()

    private fun typeName(type: MeditationType): String = ctx.getString(
        when (type) {
            MeditationType.BREATH_478 -> R.string.meditation_type_478
            MeditationType.BOX_BREATHING -> R.string.meditation_type_box
            MeditationType.BODY_SCAN -> R.string.meditation_type_scan
            MeditationType.SLEEP -> R.string.meditation_type_sleep
            MeditationType.MINDFULNESS -> R.string.meditation_type_mindful
        }
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .trim()

    companion object {
        private const val TAG = "Meditation"
        private const val OPENING_GRACE_MS = 1_500L
        private const val IDLE_SETTLE_MS = 1_500L
        private const val IDLE_WAIT_MAX_MS = 90_000L
        private const val MONITOR_TICK_MS = 1_000L
        private const val CALL_RESUME_MS = 4_000L
        private const val AUTO_RESUME_MS = 12_000L
        private const val RESTORE_TRIES_ON_START = 3
        private const val RESTORE_RETRY_MS = 60_000L

        private val _snapshot = MutableStateFlow(MeditationSnapshot())

        /** The current session for the UI (state IDLE when there is none). */
        val snapshot: StateFlow<MeditationSnapshot> = _snapshot.asStateFlow()
    }
}
