package com.treko.core.mcp.rooms

import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The two Home Assistant calls room control needs; [com.treko.core.mcp.HomeAssistantClient] provides them. */
interface HaRoomBackend {
    /** True when a Home Assistant URL and token are set. */
    fun isConfigured(): Boolean

    /** Rendered template text, or null when the call fails. */
    suspend fun renderTemplate(template: String): String?

    /** Runs a service with exact entity ids (no fuzzy correction); true on HTTP success. */
    suspend fun callService(call: HaServiceCall): Boolean
}

/**
 * Room-level control for sessions such as a meditation: list areas with players and lights,
 * dim lights warm and put them back exactly, and handle a player's volume. Every change it
 * makes can be undone from a [RoomRestoreRecord], also after the app was killed.
 */
class HaRoomControl(
    private val backend: HaRoomBackend,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private companion object {
        const val TAG = "HaRoomControl"
    }

    /** Every area with its players and lights; null without Home Assistant or template API. */
    suspend fun listAreas(): List<HaArea>? {
        if (!backend.isConfigured()) return null
        val out = backend.renderTemplate(HaRooms.AREAS_TEMPLATE) ?: return null
        return HaRooms.parseAreas(out)
    }

    /** Areas with a media player and/or a light ([HaRooms.roomCandidates]); null as [listAreas]. */
    suspend fun listRoomCandidates(): List<HaArea>? = listAreas()?.let { HaRooms.roomCandidates(it) }

    /** Current state of [lightIds]; null when Home Assistant cannot be read. */
    suspend fun snapshotLights(lightIds: List<String>): List<LightSnapshot>? {
        val ids = lightIds.filter { HaRooms.isValidEntityId(it) && it.startsWith("light.") }.distinct()
        if (ids.isEmpty()) return emptyList()
        if (!backend.isConfigured()) return null
        val out = backend.renderTemplate(HaRooms.lightsTemplate(ids)) ?: return null
        return HaRooms.parseLights(out).filter { it.entityId in ids }
    }

    /**
     * Snapshots [lightIds], saves a Home Assistant scene of the ones that are on (best
     * effort) and dims them warm. Off lights are not touched. Returns what must be restored
     * (empty record when nothing was changed); null when the lights could not be read.
     * Save the record before or right after calling, so a crash can still restore.
     */
    suspend fun dimLightsWarm(
        lightIds: List<String>,
        kelvin: Int = HaRooms.DEFAULT_WARM_KELVIN,
        brightnessPercent: Int = HaRooms.DEFAULT_DIM_PERCENT,
        transitionSeconds: Int = HaRooms.DEFAULT_TRANSITION_S
    ): RoomRestoreRecord? {
        val snapshots = snapshotLights(lightIds) ?: return null
        val toAdjust = HaRooms.lightsToAdjust(snapshots)
        if (toAdjust.isEmpty()) return RoomRestoreRecord(sceneEntityId = null, lights = emptyList())
        val sceneOk = backend.callService(HaRooms.planSceneSnapshot(toAdjust.map { it.entityId }))
        if (!sceneOk) TrekoLogBuffer.w(TAG, "scene.create failed; lights will be restored from the local snapshot.")
        val record = RoomRestoreRecord(
            sceneEntityId = if (sceneOk) HaRooms.RESTORE_SCENE_ENTITY else null,
            lights = toAdjust
        )
        HaRooms.planDim(toAdjust, kelvin, brightnessPercent, transitionSeconds).forEach { call ->
            if (!backend.callService(call)) TrekoLogBuffer.w(TAG, "Could not dim ${call.data["entity_id"]}.")
        }
        return record
    }

    /**
     * Puts the lights of [record] back as they were: through its scene when it still exists
     * in Home Assistant, else (or if that fails) from the local snapshot, light by light.
     * True when every light was restored; on false keep the record and retry later.
     */
    suspend fun restoreLights(record: RoomRestoreRecord, transitionSeconds: Int = 3): Boolean {
        if (record.lights.isEmpty() && record.sceneEntityId == null) return true
        if (!backend.isConfigured()) return false
        val scene = record.sceneEntityId
        if (scene != null && sceneExists(scene)) {
            val ok = backend.callService(
                HaServiceCall("scene", "turn_on", linkedMapOf("entity_id" to scene, "transition" to transitionSeconds))
            )
            if (ok) {
                deleteScene(scene)
                return true
            }
            TrekoLogBuffer.w(TAG, "scene.turn_on failed; restoring lights one by one.")
        }
        var allOk = true
        HaRooms.planRestore(record.lights, transitionSeconds).forEach { call ->
            if (!backend.callService(call)) {
                allOk = false
                TrekoLogBuffer.w(TAG, "Could not restore ${call.data["entity_id"]}.")
            }
        }
        if (allOk && scene != null) deleteScene(scene)
        return allOk
    }

    private suspend fun sceneExists(sceneEntityId: String): Boolean {
        if (!HaRooms.isValidEntityId(sceneEntityId)) return false
        val state = backend.renderTemplate("{{ states('$sceneEntityId') }}")?.trim() ?: return false
        return state.isNotEmpty() && state != "unknown" && state != "unavailable"
    }

    /** scene.delete only exists in newer Home Assistant; failing is harmless. */
    private suspend fun deleteScene(sceneEntityId: String) {
        backend.callService(HaServiceCall("scene", "delete", linkedMapOf("entity_id" to sceneEntityId)))
    }

    /** State and volume (0..1, null if the player does not report it) of a media player. */
    suspend fun getPlayer(entityId: String): PlayerSnapshot? {
        if (!HaRooms.isValidEntityId(entityId) || !backend.isConfigured()) return null
        val out = backend.renderTemplate("{{ states('$entityId') }}|{{ state_attr('$entityId', 'volume_level') }}")
            ?: return null
        return HaRooms.parsePlayer(entityId, out)
    }

    /** Sets a player's volume (clamped to 0..1). */
    suspend fun setVolume(entityId: String, level: Double): Boolean {
        if (!HaRooms.isValidEntityId(entityId)) return false
        val lvl = (level.coerceIn(0.0, 1.0) * 100).roundToInt() / 100.0
        return backend.callService(
            HaServiceCall("media_player", "volume_set", linkedMapOf("entity_id" to entityId, "volume_level" to lvl))
        )
    }

    /**
     * Fades a player's volume from [from] to [to] in [steps] steps, [stepMs] apart. True when
     * every step was applied.
     */
    suspend fun fadeVolume(entityId: String, from: Double, to: Double, steps: Int = 6, stepMs: Long = 800L): Boolean {
        var ok = true
        HaRooms.volumeFadeSteps(from, to, steps).forEachIndexed { i, level ->
            if (i > 0) sleep(stepMs)
            if (!setVolume(entityId, level)) ok = false
        }
        return ok
    }

    /** Stops playback (media_stop, then media_pause for players without stop). */
    suspend fun stopPlayback(entityId: String): Boolean {
        if (!HaRooms.isValidEntityId(entityId)) return false
        val target = linkedMapOf<String, Any>("entity_id" to entityId)
        return backend.callService(HaServiceCall("media_player", "media_stop", target)) ||
            backend.callService(HaServiceCall("media_player", "media_pause", target))
    }

    /** Pauses playback. */
    suspend fun pausePlayback(entityId: String): Boolean {
        if (!HaRooms.isValidEntityId(entityId)) return false
        return backend.callService(HaServiceCall("media_player", "media_pause", linkedMapOf("entity_id" to entityId)))
    }

    /** Resumes playback. */
    suspend fun resumePlayback(entityId: String): Boolean {
        if (!HaRooms.isValidEntityId(entityId)) return false
        return backend.callService(HaServiceCall("media_player", "media_play", linkedMapOf("entity_id" to entityId)))
    }

    /**
     * Undoes everything in [record]: stops the player and puts its volume back, then the
     * lights. For the end of a session and for the leftover record after a crash. True when
     * everything was restored (then the record can be deleted).
     */
    suspend fun restoreAll(record: RoomRestoreRecord, stopPlayer: Boolean = true): Boolean {
        var ok = true
        val player = record.mediaPlayer
        if (player != null) {
            if (stopPlayer) stopPlayback(player)
            record.volume?.let { if (!setVolume(player, it)) ok = false }
        }
        if (!restoreLights(record)) ok = false
        return ok
    }
}
