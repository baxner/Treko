package com.treko.assistant.meditation

import com.treko.core.mcp.RadioStation
import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.HaAreaPlayer
import com.treko.core.mcp.rooms.HaRoomControl
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.RoomRestoreRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Calm audio played on the phone itself (no room player, or no Home Assistant). */
interface PhoneMeditationMusic {
    suspend fun start(type: MeditationType): Boolean
    fun setLevel(level: Float)

    /** Fades out and stops; does nothing when nothing plays (safe to call always). */
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
 *
 * A record an earlier session left (the app died, or Home Assistant was away at its end)
 * is put back first, so this session's snapshot is the user's real room. What still cannot
 * be put back is carried over: those lights and that volume keep their older snapshot.
 *
 * A sleep session that runs to its end (or dies on the way) turns the lights it dimmed off
 * slowly instead of putting them back; stopped early, they go back as they were. The
 * player's volume is restored either way.
 *
 * The player's volume is only changed when its level before the session is known (a
 * player that is off reports none): an unknown level is never lowered, so it is never left
 * at Treko's low level or at 0.
 */
class HomeMeditationRoom(
    private val rooms: HaRoomControl?,
    private val area: HaArea?,
    private val type: MeditationType,
    private val settings: MeditationRoomSettings,
    private val store: MeditationRestoreStore,
    /** Starts [query] (a phrase for Alexa, or a stream URL) on the Home Assistant player [entityId]; true when it plays. */
    private val playOnPlayer: suspend (query: String, entityId: String) -> Boolean,
    /** What to ask an Alexa speaker for (Amazon Music understands a phrase). */
    private val musicQuery: String,
    /** A calm stream for players that need a URL (and for Alexa if the phrase fails); null: none found. */
    private val calmStream: suspend () -> RadioStation?,
    private val phoneMusic: PhoneMeditationMusic?,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {}
) : MeditationRoom {
    private var record = RoomRestoreRecord(sceneEntityId = null, lights = emptyList())
    private var player: String? = null

    /** True once the player's own level is saved: only then Treko changes its volume. */
    private var volumeControlled = false
    private var playerLevel = PLAYER_LEVEL
    private var phonePlaying = false
    private var finished = false
    private var savedOnce = false

    private fun saveRecord() {
        store.save(record, newSession = !savedOnce)
        savedOnce = true
    }

    override suspend fun prepare(): Boolean {
        val control = rooms
        val lightsOff = type == MeditationType.SLEEP
        record = RoomRestoreRecord(sceneEntityId = null, lights = emptyList(), startedAtMs = wallClock(), lightsOffAtEnd = lightsOff)
        if (store.load() != null) {
            val result = store.restore(control, late = true)
            log("Room left by an earlier session: $result.")
            store.load()?.let { left ->
                // Still not put back (Home Assistant away?): carried, never re-snapshotted.
                record = left.copy(startedAtMs = wallClock(), lightsOffAtEnd = lightsOff)
                saveRecord()
            }
        }
        var musicOk = !settings.playMusic
        if (control != null && area != null) {
            if (settings.playMusic) {
                val target = area.preferredPlayer(settings.preferAlexa)
                val waiting = record.mediaPlayer
                if (target != null && (waiting == null || waiting == target.entityId)) {
                    musicOk = startRoomMusic(control, target)
                } else if (target != null) {
                    log("$waiting still waits for its volume; the music plays on the phone.")
                }
            }
            if (settings.adjustLights && area.hasLights) dimLights(control, area)
        }
        if (settings.playMusic && player == null && phoneMusic != null) {
            phonePlaying = phoneMusic.start(type)
            musicOk = phonePlaying
            if (phonePlaying) phoneMusic.setLevel(PHONE_LEVEL)
        }
        return musicOk
    }

    private suspend fun startRoomMusic(control: HaRoomControl, target: HaAreaPlayer): Boolean {
        val id = target.entityId
        val carried = record.mediaPlayer == id && record.volume != null
        val before = if (carried) record.volume else control.getPlayer(id)?.volume
        // Saved before anything plays: a crash still stops the player (and puts a known volume back).
        record = record.copy(mediaPlayer = id, volume = before, playerLevel = before?.let { PLAYER_LEVEL })
        saveRecord()
        player = id
        if (before != null) {
            volumeControlled = true
            control.setVolume(id, PLAYER_LEVEL)
        }
        val ok = if (target.isAlexa) {
            playOnPlayer(musicQuery, id) || calmStream()?.let { playOnPlayer(it.streamUrl, id) } == true
        } else {
            // Other players get a calm stream URL, never a search that may find pop or news.
            val stream = calmStream()
            if (stream == null) log("No calm stream found for $id.")
            stream != null && playOnPlayer(stream.streamUrl, id)
        }
        if (!ok) {
            log("Room music did not start on $id.")
            return false
        }
        if (!volumeControlled) {
            // An off player reports its volume once it plays: save it, then lower it.
            val now = readVolume(control, id)
            if (now != null) {
                record = record.copy(volume = now, playerLevel = PLAYER_LEVEL)
                saveRecord()
                volumeControlled = true
            } else {
                log("The volume of $id is unknown; it is left as it is.")
            }
        }
        // Some players reset the volume when new media starts.
        if (volumeControlled) control.setVolume(id, PLAYER_LEVEL)
        return true
    }

    private suspend fun readVolume(control: HaRoomControl, id: String): Double? {
        repeat(VOLUME_READS) { i ->
            if (i > 0) sleep(VOLUME_READ_GAP_MS)
            control.getPlayer(id)?.volume?.let { return it }
        }
        return null
    }

    private suspend fun dimLights(control: HaRoomControl, area: HaArea) {
        val carriedLights = record.lights.map { it.entityId }.toSet()
        // Lights an earlier session left dimmed keep their older snapshot: not read again.
        val ids = area.lights.filter { it !in carriedLights }
        val dimmed = control.dimLightsWarm(
            ids,
            kelvin = if (type == MeditationType.SLEEP) SLEEP_KELVIN else HaRooms.DEFAULT_WARM_KELVIN,
            brightnessPercent = if (type == MeditationType.SLEEP) SLEEP_PERCENT else HaRooms.DEFAULT_DIM_PERCENT,
            transitionSeconds = HaRooms.DEFAULT_TRANSITION_S,
            // The fixed restore scene may still hold the carried lights' state: never overwrite it.
            createScene = carriedLights.isEmpty() && record.sceneEntityId == null,
            onPlanned = { planned ->
                record = record.copy(
                    sceneEntityId = if (carriedLights.isEmpty()) planned.sceneEntityId else null,
                    lights = record.lights + planned.lights,
                    dimBrightnessPercent = planned.dimBrightnessPercent,
                    dimKelvin = planned.dimKelvin
                )
                // On disk before the first light changes.
                saveRecord()
            }
        )
        if (dimmed == null) log("Could not read the lights of ${area.name}; leaving them as they are.")
    }

    override suspend fun duck() {
        player?.takeIf { volumeControlled }?.let {
            rooms?.setVolume(it, PLAYER_DUCKED)
            playerLevel = PLAYER_DUCKED
        }
        if (phonePlaying) phoneMusic?.setLevel(PHONE_DUCKED)
    }

    override suspend fun unduck() {
        player?.takeIf { volumeControlled }?.let {
            rooms?.setVolume(it, PLAYER_LEVEL)
            playerLevel = PLAYER_LEVEL
        }
        if (phonePlaying) phoneMusic?.setLevel(PHONE_LEVEL)
    }

    override suspend fun finish(completed: Boolean) {
        if (finished) return
        finished = true
        if (record.lightsOffAtEnd && !completed && savedOnce) {
            // A sleep session stopped early: its lights go back as they were, not off. On
            // disk first, so a later retry (Home Assistant away now) does the same.
            record = record.copy(lightsOffAtEnd = false)
            saveRecord()
        }
        try {
            // Fade only a volume that goes back to a saved level afterwards.
            player?.takeIf { volumeControlled }?.let { rooms?.fadeVolume(it, playerLevel, 0.0, steps = 5, stepMs = 700L) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Music fade failed: ${e.message}")
        }
        try {
            // Always: a stream that started just as the session was cancelled is stopped too.
            phoneMusic?.fadeOutAndStop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Phone music stop failed: ${e.message}")
        }
        // Nothing was changed or carried (stopped before preparing): leave any stored record alone.
        if (!savedOnce) return
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
        private const val VOLUME_READS = 3
        private const val VOLUME_READ_GAP_MS = 1_500L
    }
}
