package com.treko.assistant.meditation

import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.HaRoomControl
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.RoomRestoreRecord

/** Calm audio played on the phone itself (no room player, or no Home Assistant). */
interface PhoneMeditationMusic {
    suspend fun start(type: MeditationType): Boolean
    fun setLevel(level: Float)
    suspend fun fadeOutAndStop()
}

/** How a session sets the room. */
data class MeditationRoomSettings(
    val adjustLights: Boolean = true,
    val playMusic: Boolean = true,
    val preferAlexa: Boolean = false
)

/**
 * The room side of a session on any home: the area's lights dimmed warm (only those that
 * were on) and calm music on its player, or music on the phone when there is no room.
 * Every change is first written to the [store], so it is undone even after a crash.
 */
class HomeMeditationRoom(
    private val rooms: HaRoomControl?,
    private val area: HaArea?,
    private val type: MeditationType,
    private val settings: MeditationRoomSettings,
    private val store: MeditationRestoreStore,
    /** Starts [query] on the Home Assistant player [entityId]; true when it plays. */
    private val playOnPlayer: suspend (query: String, entityId: String) -> Boolean,
    private val musicQuery: String,
    private val phoneMusic: PhoneMeditationMusic?,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {}
) : MeditationRoom {
    private var record = RoomRestoreRecord(sceneEntityId = null, lights = emptyList())
    private var player: String? = null
    private var playerLevel = PLAYER_LEVEL
    private var phonePlaying = false
    private var finished = false

    override suspend fun prepare(): Boolean {
        record = RoomRestoreRecord(sceneEntityId = null, lights = emptyList(), startedAtMs = wallClock())
        var musicOk = !settings.playMusic
        val control = rooms
        if (control != null && area != null) {
            if (settings.playMusic) {
                val target = area.preferredPlayer(settings.preferAlexa)
                if (target != null) {
                    val before = control.getPlayer(target.entityId)
                    record = record.copy(mediaPlayer = target.entityId, volume = before?.volume)
                    store.save(record)
                    player = target.entityId
                    control.setVolume(target.entityId, PLAYER_LEVEL)
                    musicOk = playOnPlayer(musicQuery, target.entityId)
                    // Some players reset the volume when new media starts.
                    if (musicOk) control.setVolume(target.entityId, PLAYER_LEVEL)
                    else log("Room music did not start on ${target.entityId}.")
                }
            }
            if (settings.adjustLights && area.hasLights) {
                val dimmed = control.dimLightsWarm(
                    area.lights,
                    kelvin = if (type == MeditationType.SLEEP) SLEEP_KELVIN else HaRooms.DEFAULT_WARM_KELVIN,
                    brightnessPercent = if (type == MeditationType.SLEEP) SLEEP_PERCENT else HaRooms.DEFAULT_DIM_PERCENT,
                    transitionSeconds = HaRooms.DEFAULT_TRANSITION_S
                )
                if (dimmed != null) {
                    record = record.copy(sceneEntityId = dimmed.sceneEntityId, lights = dimmed.lights)
                    store.save(record)
                } else {
                    log("Could not read the lights of ${area.name}; leaving them as they are.")
                }
            }
        }
        if (settings.playMusic && player == null && phoneMusic != null) {
            phonePlaying = phoneMusic.start(type)
            musicOk = phonePlaying
            if (phonePlaying) phoneMusic.setLevel(PHONE_LEVEL)
        }
        return musicOk
    }

    override suspend fun duck() {
        player?.let {
            rooms?.setVolume(it, PLAYER_DUCKED)
            playerLevel = PLAYER_DUCKED
        }
        if (phonePlaying) phoneMusic?.setLevel(PHONE_DUCKED)
    }

    override suspend fun unduck() {
        player?.let {
            rooms?.setVolume(it, PLAYER_LEVEL)
            playerLevel = PLAYER_LEVEL
        }
        if (phonePlaying) phoneMusic?.setLevel(PHONE_LEVEL)
    }

    override suspend fun finish() {
        if (finished) return
        finished = true
        try {
            player?.let { rooms?.fadeVolume(it, playerLevel, 0.0, steps = 5, stepMs = 700L) }
            if (phonePlaying) phoneMusic?.fadeOutAndStop()
        } catch (e: Exception) {
            log("Music fade failed: ${e.message}")
        }
        val result = store.restore(rooms, record, stopPlayer = true)
        log("Room restore after meditation: $result.")
    }

    companion object {
        const val PLAYER_LEVEL = 0.18
        const val PLAYER_DUCKED = 0.06
        const val PHONE_LEVEL = 0.22f
        const val PHONE_DUCKED = 0.07f
        const val SLEEP_KELVIN = 2200
        const val SLEEP_PERCENT = 20
    }
}
