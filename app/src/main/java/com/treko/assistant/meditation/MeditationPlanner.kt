package com.treko.assistant.meditation

import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.PhoneReason
import com.treko.core.mcp.rooms.PlaceDecision

/** Pure decisions of the `meditation` tool: which rooms qualify, where to run, which habit to mark. */
object MeditationPlanner {

    /**
     * Areas worth asking about with these settings: with lights when lights may be
     * adjusted, with a player when music is on. Null in → null out (no Home Assistant).
     */
    fun candidates(areas: List<HaArea>?, settings: MeditationRoomSettings): List<HaArea>? =
        areas?.let { list ->
            HaRooms.roomCandidates(list).filter { a ->
                (settings.adjustLights && a.hasLights) || (settings.playMusic && a.hasPlayer)
            }
        }

    /**
     * Where to run: with lights and music both off, or no room that qualifies, the phone
     * without asking; otherwise [HaRooms.decidePlace] (ask when there are several).
     */
    fun decide(
        roomCandidates: List<HaArea>?,
        settings: MeditationRoomSettings,
        requested: String?,
        lastUsed: String?
    ): PlaceDecision {
        if (!settings.adjustLights && !settings.playMusic) {
            return if (requested != null && HaRooms.isPhoneOnlyAnswer(requested)) {
                PlaceDecision.UsePhone(PhoneReason.USER_CHOSE_PHONE)
            } else {
                PlaceDecision.UsePhone(if (roomCandidates == null) PhoneReason.NO_HOME_ASSISTANT else PhoneReason.NO_ROOMS)
            }
        }
        val filtered = candidates(roomCandidates, settings)
        return HaRooms.decidePlace(
            homeAssistantAvailable = filtered != null,
            candidates = filtered.orEmpty(),
            requested = requested?.takeIf { it.isNotBlank() },
            lastUsed = lastUsed
        )
    }

    /** A start that had to ask where: remembered so the answer ("en la sala") keeps type and minutes. */
    data class PendingStart(val type: MeditationType, val minutes: Int?, val atMs: Long)

    const val PENDING_START_TTL_MS = 5 * 60_000L

    /** Type and minutes for a start, filling what the call left out from a recent [pending] one. */
    fun resolveStart(
        rawType: String?,
        rawMinutes: Int?,
        pending: PendingStart?,
        nowMs: Long
    ): Pair<MeditationType, Int> {
        val fresh = pending?.takeIf { nowMs - it.atMs in 0..PENDING_START_TTL_MS }
        val type = if (!rawType.isNullOrBlank()) MeditationType.parse(rawType) else fresh?.type ?: MeditationType.DEFAULT
        val minutes = rawMinutes?.takeIf { it > 0 } ?: fresh?.minutes
        return type to MeditationTimelineBuilder.clampMinutes(minutes)
    }

    /** True for a habit such as "Meditar", "Meditación", "Respiración" or "Meditation". */
    fun isMeditationHabit(title: String): Boolean =
        MeditationType.normalize(title).split(' ').any { w ->
            HABIT_PREFIXES.any { w.startsWith(it) }
        }

    private val HABIT_PREFIXES = listOf("medit", "respir", "mindful", "breath")

    /** Share of the session that counts it as done for a habit. */
    const val HABIT_DONE_FRACTION = 0.8
}
