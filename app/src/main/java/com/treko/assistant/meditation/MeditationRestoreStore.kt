package com.treko.assistant.meditation

import android.annotation.SuppressLint
import android.content.Context
import com.treko.core.mcp.rooms.HaRoomControl
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.RoomRestoreRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tiny key-value storage, so the store can be tested without Android. */
interface MeditationKeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int)
}

class SharedPrefsMeditationKeyValue(context: Context) : MeditationKeyValue {
    private val prefs = context.applicationContext.getSharedPreferences("treko_meditation", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)

    @SuppressLint("ApplySharedPref")
    override fun putString(key: String, value: String?) {
        // commit: the record must be on disk before the lights change, or a crash loses it.
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
    override fun getInt(key: String): Int = prefs.getInt(key, 0)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}

/**
 * [KEPT]: something is still to be put back (retry later). [SKIPPED]: not tried now (a
 * session is running and owns the record).
 */
enum class RestoreResult { NOTHING, RESTORED, KEPT, DROPPED, SKIPPED }

/**
 * The pending-restore record of a meditation: what was changed in the room (lights,
 * player volume) and must be put back, kept on disk so a session cut short by the app or
 * service dying is restored the next time the service starts. Restores never run at the
 * same time (a retry and a new session's start would fight over the record).
 */
class MeditationRestoreStore(
    private val kv: MeditationKeyValue,
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()

    fun load(): RoomRestoreRecord? = HaRooms.decodeRecord(kv.getString(KEY_RECORD))?.takeUnless { it.isEmpty }

    /** Saves [record]; [newSession] starts its retry count again (the record is a new session's). */
    fun save(record: RoomRestoreRecord, newSession: Boolean = false) {
        if (record.isEmpty) {
            clear()
            return
        }
        kv.putString(KEY_RECORD, HaRooms.encodeRecord(record))
        if (newSession) kv.putInt(KEY_ATTEMPTS, 0)
    }

    fun clear() {
        kv.putString(KEY_RECORD, null)
        kv.putInt(KEY_ATTEMPTS, 0)
    }

    val attempts: Int get() = kv.getInt(KEY_ATTEMPTS)

    /**
     * Puts back what [record] (default: the stored one) changed, part by part: what was
     * restored leaves the record, what failed stays for a later try. A record with
     * [RoomRestoreRecord.lightsOffAtEnd] turns its lights off slowly instead (a sleep
     * session); its player and volume are restored all the same.
     *
     * [late]: the restore comes after the session (a crash, a retry once Home Assistant is
     * back): only what is still as Treko left it is put back ([HaRooms.planLateRestore]),
     * so a light the user turned off or changed, or music they started, is left alone.
     *
     * Without Home Assistant, or while it does not answer, nothing is tried and nothing is
     * counted. A try counts only when it answered and still refused a call; after
     * [MAX_ATTEMPTS] such tries, or once the record is older than [MAX_AGE_MS], it is
     * dropped: by then the user has surely set the room the way they want.
     *
     * [proceed] is checked under the lock: false → [RestoreResult.SKIPPED], nothing touched.
     */
    suspend fun restore(
        rooms: HaRoomControl?,
        record: RoomRestoreRecord? = null,
        stopPlayer: Boolean = true,
        late: Boolean = false,
        proceed: () -> Boolean = { true }
    ): RestoreResult = mutex.withLock {
        if (!proceed()) return@withLock RestoreResult.SKIPPED
        val rec = record ?: load()
        if (rec == null || rec.isEmpty) {
            clear()
            return@withLock RestoreResult.NOTHING
        }
        if (rec.startedAtMs > 0L && wallClock() - rec.startedAtMs > MAX_AGE_MS) {
            clear()
            return@withLock RestoreResult.DROPPED
        }
        if (rooms == null || !rooms.isReachable()) {
            // Not reachable is no refusal: keep it as it is, uncounted.
            save(rec)
            return@withLock RestoreResult.KEPT
        }
        var target = rec
        var stop = stopPlayer
        if (late) {
            val plan = rooms.planLateRestore(rec)
            if (plan == null) {
                save(rec)
                return@withLock RestoreResult.KEPT
            }
            target = plan.record
            stop = stopPlayer && plan.stopPlayer
        }
        var remaining = target
        val player = target.mediaPlayer
        if (player != null) {
            if (stop) rooms.stopPlayback(player)
            val volumeOk = target.volume?.let { rooms.setVolume(player, it) } ?: true
            if (volumeOk) remaining = remaining.copy(mediaPlayer = null, volume = null)
        }
        if (remaining.sceneEntityId != null || remaining.lights.isNotEmpty()) {
            // A sleep session's end (or its crash): the lights it dimmed go off, not back on.
            val lightsOk = if (remaining.lightsOffAtEnd) rooms.turnOffLights(remaining) else rooms.restoreLights(remaining)
            if (lightsOk) remaining = remaining.copy(sceneEntityId = null, lights = emptyList())
        }
        if (remaining.isEmpty) {
            clear()
            return@withLock RestoreResult.RESTORED
        }
        val tries = attempts + 1
        if (tries >= MAX_ATTEMPTS) {
            clear()
            return@withLock RestoreResult.DROPPED
        }
        save(remaining)
        kv.putInt(KEY_ATTEMPTS, tries)
        RestoreResult.KEPT
    }

    companion object {
        private const val KEY_RECORD = "pending_restore"
        private const val KEY_ATTEMPTS = "pending_restore_attempts"

        /** Tries Home Assistant answered and still refused; offline time never counts. */
        const val MAX_ATTEMPTS = 6
        const val MAX_AGE_MS = 12 * 60 * 60_000L
    }
}
