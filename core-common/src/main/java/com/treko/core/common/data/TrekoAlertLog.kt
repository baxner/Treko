package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertFeedback
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the alerts log is kept (a JSON file in the app; a list in memory in tests). */
interface AlertPersistence {
    fun save(events: List<AlertEvent>)
    fun load(): List<AlertEvent>
}

/**
 * The alerts log ("Avisos"): every camera watch alert, sentinel check, delivered proactive
 * briefing and finished timer, newest first, for the alerts screen.
 *
 * Process-wide like [TrekoTimerManager]: the service, the activity and the timer receiver
 * all call [init] (idempotent) and then use the object. Thread-safe; reads come from
 * [events] / [unreadCount]. Writes go to disk on a background thread, coalesced.
 *
 * Retention: 14 days or [MAX_EVENTS] entries; "no news" checks only 24 h and never with a
 * picture. Pictures of evicted entries are deleted.
 */
object TrekoAlertLog {
    private const val TAG = "AlertLog"

    const val RETENTION_MS = 14L * 24 * 60 * 60_000L
    const val NO_NEWS_RETENTION_MS = 24L * 60 * 60_000L
    const val MAX_EVENTS = 500

    private val lock = Any()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private val backgroundWriter: (() -> Unit) -> Unit = { task -> ioScope.launch { task() } }

    private var persistence: AlertPersistence? = null
    private var loaded = true
    private var writeQueued = false
    private var lastId = 0L
    private var writer: (() -> Unit) -> Unit = backgroundWriter
    private var imageDeleter: (List<String>) -> Unit = {}

    /** Reloj inyectable para tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private val _events = MutableStateFlow<List<AlertEvent>>(emptyList())
    /** All entries, newest first. */
    val events: StateFlow<List<AlertEvent>> = _events.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    /** Unread alerts ("no news" checks never count). */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** Pictures of the alerts; null until [init] ran. */
    @Volatile
    var snapshots: AlertSnapshotStore? = null
        private set

    /**
     * Connects the log to `filesDir/avisos` (log file and pictures). Idempotent; the saved log
     * loads in the background (an early read or write waits for it).
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            if (persistence != null) return
            val dir = File(appCtx.filesDir, AlertSnapshotStore.DIR_NAME)
            val store = AlertSnapshotStore(File(dir, AlertSnapshotStore.SNAPSHOTS_DIR_NAME)) { evicted ->
                forgetImages(evicted)
            }
            snapshots = store
            attachLocked(AlertLogFile(File(dir, LOG_FILE_NAME)), store::deleteFiles, backgroundWriter, loadNow = false)
        }
        ioScope.launch {
            val referenced = synchronized(lock) {
                ensureLoadedLocked()
                _events.value.flatMap { it.imagePaths }.toSet()
            }
            snapshots?.sweepOrphans(referenced)
        }
    }

    /**
     * Connects a custom store (tests). [writer] runs disk work (default: a background thread);
     * [imageDeleter] removes the picture files of evicted entries.
     */
    fun attach(
        persistence: AlertPersistence,
        imageDeleter: (List<String>) -> Unit = {},
        writer: (() -> Unit) -> Unit = backgroundWriter
    ) {
        synchronized(lock) {
            if (this.persistence != null) return
            attachLocked(persistence, imageDeleter, writer, loadNow = true)
        }
    }

    private fun attachLocked(
        persistence: AlertPersistence,
        imageDeleter: (List<String>) -> Unit,
        writer: (() -> Unit) -> Unit,
        loadNow: Boolean
    ) {
        this.persistence = persistence
        this.imageDeleter = imageDeleter
        this.writer = writer
        loaded = false
        if (loadNow) ensureLoadedLocked()
    }

    /** Solo para tests: olvida persistencia y entradas. */
    internal fun resetForTests() {
        synchronized(lock) {
            persistence = null
            snapshots = null
            loaded = true
            writeQueued = false
            lastId = 0L
            writer = backgroundWriter
            imageDeleter = {}
            clock = System::currentTimeMillis
            publishLocked(emptyList())
        }
    }

    /** Adds [event] (its id is assigned here) and returns the new id. */
    fun record(event: AlertEvent): Long = synchronized(lock) {
        ensureLoadedLocked()
        val id = maxOf(lastId + 1, clock())
        lastId = id
        val (clean, dropped) = normalize(event.copy(id = id))
        deleteImagesLocked(dropped)
        val (kept, removed) = retain(listOf(clean) + _events.value, clock())
        deleteImagesLocked(removed.flatMap { it.imagePaths })
        publishLocked(kept)
        persistLocked()
        id
    }

    fun get(id: Long): AlertEvent? = synchronized(lock) {
        ensureLoadedLocked()
        _events.value.firstOrNull { it.id == id }
    }

    /**
     * Changes entry [id] (e.g. adds the description or the picture once they arrive).
     * The id and level rules still apply; pictures no longer referenced are deleted.
     * Returns the updated entry, or null if it is gone.
     */
    fun update(id: Long, transform: (AlertEvent) -> AlertEvent): AlertEvent? = synchronized(lock) {
        ensureLoadedLocked()
        val current = _events.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = current[index]
        val (next, dropped) = normalize(transform(old).copy(id = old.id))
        deleteImagesLocked(dropped + (old.imagePaths - next.imagePaths.toSet()))
        if (next == old) return old
        publishLocked(current.toMutableList().also { it[index] = next })
        persistLocked()
        next
    }

    fun markRead(id: Long): Boolean = update(id) { it.copy(read = true) }?.read == true

    fun markAllRead(): Int = synchronized(lock) {
        ensureLoadedLocked()
        val current = _events.value
        val count = current.count { !it.read }
        if (count == 0) return 0
        publishLocked(current.map { if (it.read) it else it.copy(read = true) })
        persistLocked()
        count
    }

    fun setFeedback(id: Long, feedback: AlertFeedback): Boolean =
        update(id) { it.copy(feedback = feedback) }?.feedback == feedback

    fun delete(id: Long): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val current = _events.value
        val victim = current.firstOrNull { it.id == id } ?: return false
        deleteImagesLocked(victim.imagePaths)
        publishLocked(current - victim)
        persistLocked()
        true
    }

    /** The picture files at [paths] were removed (storage cap): clear them from their entries. */
    fun forgetImages(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val gone = paths.toSet()
        synchronized(lock) {
            ensureLoadedLocked()
            val current = _events.value
            if (current.none { e -> e.imagePaths.any { it in gone } }) return
            publishLocked(
                current.map { e ->
                    if (e.imagePaths.none { it in gone }) {
                        e
                    } else {
                        e.copy(
                            snapshotPath = e.snapshotPath?.takeUnless { it in gone },
                            thumbPath = e.thumbPath?.takeUnless { it in gone }
                        )
                    }
                }
            )
            persistLocked()
        }
    }

    /** Applies the retention rules now; returns how many entries were removed. */
    fun prune(nowMs: Long = clock()): Int = synchronized(lock) {
        ensureLoadedLocked()
        val (kept, removed) = retain(_events.value, nowMs)
        if (removed.isEmpty()) return 0
        deleteImagesLocked(removed.flatMap { it.imagePaths })
        publishLocked(kept)
        persistLocked()
        removed.size
    }

    /**
     * Saves [imageBytes] as the picture of entry [id] (full size and thumbnail, off the main
     * thread). False when there is no store, the entry is gone, is a "no news" check, or the
     * image cannot be decoded.
     */
    suspend fun attachSnapshot(id: Long, imageBytes: ByteArray): Boolean {
        val store = snapshots ?: return false
        val event = get(id) ?: return false
        if (event.level == AlertLevel.NO_NEWS) return false
        val saved = store.save(id, imageBytes) ?: return false
        val updated = update(id) { it.copy(snapshotPath = saved.fullPath, thumbPath = saved.thumbPath) }
        if (updated == null) {
            store.deleteFiles(listOfNotNull(saved.fullPath, saved.thumbPath))
            return false
        }
        return true
    }

    /** Writes any pending change now, on the calling thread (e.g. before a receiver returns). */
    fun flush() {
        val (store, snapshot) = synchronized(lock) {
            ensureLoadedLocked()
            writeQueued = false
            persistence to _events.value
        }
        store ?: return
        saveSafely(store, snapshot)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val store = persistence ?: return
        val stored = try {
            store.load()
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Cannot read the alerts log: ${e.message}")
            emptyList()
        }
        // Entries recorded before the load finished are kept too.
        val merged = (_events.value + stored).distinctBy { it.id }.map { normalize(it).first }
        lastId = maxOf(lastId, merged.maxOfOrNull { it.id } ?: 0L)
        val (kept, removed) = retain(merged, clock())
        deleteImagesLocked(removed.flatMap { it.imagePaths })
        publishLocked(kept)
        if (removed.isNotEmpty() || _events.value.size != stored.size) persistLocked()
    }

    private fun publishLocked(list: List<AlertEvent>) {
        val sorted = list.sortedWith(NEWEST_FIRST)
        _events.value = sorted
        _unreadCount.value = sorted.count { it.isUnread }
    }

    private fun persistLocked() {
        val store = persistence ?: return
        if (writeQueued) return
        writeQueued = true
        writer {
            val snapshot = synchronized(lock) {
                // Null when flush() already wrote it.
                if (writeQueued) _events.value.also { writeQueued = false } else null
            }
            if (snapshot != null) saveSafely(store, snapshot)
        }
    }

    private fun saveSafely(store: AlertPersistence, snapshot: List<AlertEvent>) {
        try {
            store.save(snapshot)
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Cannot save the alerts log: ${e.message}")
        }
    }

    private fun deleteImagesLocked(paths: List<String>) {
        val distinct = paths.distinct()
        if (distinct.isEmpty()) return
        val deleter = imageDeleter
        writer { deleter(distinct) }
    }

    private const val LOG_FILE_NAME = "log.json"

    private val NEWEST_FIRST = compareByDescending<AlertEvent> { it.timestampMs }.thenByDescending { it.id }

    /** Enforces the level rules; returns the clean entry and the picture paths it may not keep. */
    internal fun normalize(event: AlertEvent): Pair<AlertEvent, List<String>> =
        if (event.level == AlertLevel.NO_NEWS && event.imagePaths.isNotEmpty()) {
            // "No news" never keeps a picture of the scene (often people at home).
            event.copy(snapshotPath = null, thumbPath = null) to event.imagePaths
        } else {
            event to emptyList()
        }

    /**
     * Retention rules: drop entries older than [RETENTION_MS] and "no news" older than
     * [NO_NEWS_RETENTION_MS]; above [MAX_EVENTS], drop the oldest "no news" first, then the
     * oldest alerts. Returns (kept, removed).
     */
    internal fun retain(events: List<AlertEvent>, nowMs: Long): Pair<List<AlertEvent>, List<AlertEvent>> {
        val removed = ArrayList<AlertEvent>()
        val fresh = events.filter { e ->
            val age = nowMs - e.timestampMs
            val keep = age <= RETENTION_MS && (e.level != AlertLevel.NO_NEWS || age <= NO_NEWS_RETENTION_MS)
            if (!keep) removed += e
            keep
        }
        val excess = fresh.size - MAX_EVENTS
        if (excess <= 0) return fresh to removed
        val oldestFirst = compareBy<AlertEvent> { it.timestampMs }.thenBy { it.id }
        val victims = (
            fresh.filter { it.level == AlertLevel.NO_NEWS }.sortedWith(oldestFirst) +
                fresh.filter { it.level != AlertLevel.NO_NEWS }.sortedWith(oldestFirst)
            ).take(excess).map { it.id }.toSet()
        val kept = fresh.filter { e ->
            val keep = e.id !in victims
            if (!keep) removed += e
            keep
        }
        return kept to removed
    }
}
