package com.treko.assistant.meditation

import android.annotation.SuppressLint
import android.content.Context
import com.treko.core.mcp.rooms.HaRoomControl
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.RoomRestoreRecord

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

enum class RestoreResult { NOTHING, RESTORED, KEPT, DROPPED }

/**
 * The pending-restore record of a meditation: what was changed in the room (lights,
 * player volume) and must be put back, kept on disk so a session cut short by the app or
 * service dying is restored the next time the service starts.
 */
class MeditationRestoreStore(
    private val kv: MeditationKeyValue,
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {
    fun load(): RoomRestoreRecord? = HaRooms.decodeRecord(kv.getString(KEY_RECORD))?.takeUnless { it.isEmpty }

    fun save(record: RoomRestoreRecord) {
        if (record.isEmpty) {
            clear()
            return
        }
        kv.putString(KEY_RECORD, HaRooms.encodeRecord(record))
    }

    fun clear() {
        kv.putString(KEY_RECORD, null)
        kv.putInt(KEY_ATTEMPTS, 0)
    }

    val attempts: Int get() = kv.getInt(KEY_ATTEMPTS)

    /**
     * Puts back what [record] (default: the stored one) changed, part by part: what was
     * restored leaves the record, what failed stays for a later try. A record older than
     * [MAX_AGE_MS] or tried [MAX_ATTEMPTS] times is dropped: by then the user has surely
     * set the room the way they want and restoring would undo it.
     */
    suspend fun restore(rooms: HaRoomControl?, record: RoomRestoreRecord? = load(), stopPlayer: Boolean = true): RestoreResult {
        if (record == null || record.isEmpty) {
            clear()
            return RestoreResult.NOTHING
        }
        if (record.startedAtMs > 0L && wallClock() - record.startedAtMs > MAX_AGE_MS) {
            clear()
            return RestoreResult.DROPPED
        }
        var remaining = record
        if (rooms != null) {
            val player = record.mediaPlayer
            if (player != null) {
                if (stopPlayer) rooms.stopPlayback(player)
                val volumeOk = record.volume?.let { rooms.setVolume(player, it) } ?: true
                if (volumeOk) remaining = remaining.copy(mediaPlayer = null, volume = null)
            }
            if (remaining.sceneEntityId != null || remaining.lights.isNotEmpty()) {
                if (rooms.restoreLights(remaining)) remaining = remaining.copy(sceneEntityId = null, lights = emptyList())
            }
        }
        if (remaining.isEmpty) {
            clear()
            return RestoreResult.RESTORED
        }
        val tries = attempts + 1
        if (tries >= MAX_ATTEMPTS) {
            clear()
            return RestoreResult.DROPPED
        }
        save(remaining)
        kv.putInt(KEY_ATTEMPTS, tries)
        return RestoreResult.KEPT
    }

    companion object {
        private const val KEY_RECORD = "pending_restore"
        private const val KEY_ATTEMPTS = "pending_restore_attempts"
        const val MAX_ATTEMPTS = 4
        const val MAX_AGE_MS = 12 * 60 * 60_000L
    }
}
