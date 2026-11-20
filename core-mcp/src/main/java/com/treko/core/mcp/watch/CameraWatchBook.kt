package com.treko.core.mcp.watch

import java.net.URLDecoder
import java.net.URLEncoder

/** One "tell me when you see …" request. */
data class CameraWatch(
    val id: Long,
    val sensorIds: List<String>,
    val areaNames: List<String>,
    /** Key of [WatchCatalog.OBJECT_GROUPS] ("person", "car", …). */
    val objectGroup: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    /** Alert once, then stop watching. */
    val once: Boolean
)

/** A sensor fired for an active watch and the alert is due. */
data class WatchHit(val watch: CameraWatch, val sensorId: String)

interface WatchPersistence {
    fun save(encoded: String)
    fun load(): String?
}

/**
 * Active watches, their expiry and the per-sensor cooldown. Pure logic: the caller feeds
 * sensor activations and gets back which alerts to deliver.
 */
class CameraWatchBook(
    private val persistence: WatchPersistence,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private val lock = Any()
    private val watches = ArrayList<CameraWatch>()
    private val lastAlertBySensor = HashMap<String, Long>()
    private var nextId = 1L

    init {
        persistence.load()?.let { decode(it) }?.let { (next, list) ->
            nextId = next
            watches += list
        }
    }

    fun add(
        sensorIds: List<String>,
        areaNames: List<String>,
        objectGroup: String,
        durationMs: Long,
        once: Boolean,
        nowMs: Long
    ): CameraWatch = synchronized(lock) {
        val watch = CameraWatch(
            id = nextId++,
            sensorIds = sensorIds.distinct(),
            areaNames = areaNames.distinct(),
            objectGroup = objectGroup,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
            once = once
        )
        watches += watch
        persistLocked()
        watch
    }

    /**
     * Stops the watches covering the places named (same matching as when they were set),
     * or all of them when nothing or "toda la casa" is named.
     */
    fun stop(places: List<String>): List<CameraWatch> = synchronized(lock) {
        val queries = places.map { it.trim() }.filter { it.isNotEmpty() }
        val removed = if (queries.isEmpty() || queries.any { WatchCatalog.isEverywhere(it) }) {
            watches.toList()
        } else {
            val names = watches.flatMap { it.areaNames }.distinct()
            val meant = queries.flatMap { WatchCatalog.matchPlaces(it, names) }.toSet()
            watches.filter { w -> w.areaNames.any { it in meant } }
        }
        watches.removeAll(removed.toSet())
        persistLocked()
        removed
    }

    fun active(nowMs: Long): List<CameraWatch> = synchronized(lock) {
        pruneLocked(nowMs)
        watches.toList()
    }

    fun watchedSensorIds(nowMs: Long): Set<String> = active(nowMs).flatMap { it.sensorIds }.toSet()

    /**
     * A watched sensor turned on. Returns the alert to deliver, or null while the same
     * sensor is in its cooldown (the same visitor is still there) or nothing watches it.
     */
    fun onSensorActivated(sensorId: String, nowMs: Long): WatchHit? = synchronized(lock) {
        pruneLocked(nowMs)
        val covering = watches.filter { sensorId in it.sensorIds }
        if (covering.isEmpty()) return null
        val last = lastAlertBySensor[sensorId]
        if (last != null && nowMs - last < cooldownMs) return null
        lastAlertBySensor[sensorId] = nowMs
        // One alert serves every watch on this sensor; each one-shot watch is used up.
        val oneShots = covering.filter { it.once }
        if (oneShots.isNotEmpty()) {
            watches.removeAll(oneShots.toSet())
            persistLocked()
        }
        WatchHit(covering.first(), sensorId)
    }

    private fun pruneLocked(nowMs: Long) {
        if (watches.removeAll { it.expiresAtMs <= nowMs }) persistLocked()
    }

    private fun persistLocked() {
        persistence.save(encode(nextId, watches))
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2 * 60_000L
        const val MIN_DURATION_MS = 60_000L
        const val MAX_DURATION_MS = 12 * 60 * 60_000L

        private const val VERSION = "v1"

        internal fun encode(nextId: Long, list: List<CameraWatch>): String = buildString {
            append(VERSION).append('\n')
            append("next=").append(nextId).append('\n')
            for (w in list) {
                append(
                    listOf(
                        w.id.toString(),
                        w.sensorIds.joinToString(",") { enc(it) },
                        w.areaNames.joinToString(",") { enc(it) },
                        enc(w.objectGroup),
                        w.createdAtMs.toString(),
                        w.expiresAtMs.toString(),
                        if (w.once) "1" else "0"
                    ).joinToString("|")
                ).append('\n')
            }
        }

        internal fun decode(text: String): Pair<Long, List<CameraWatch>>? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != VERSION) return null
            val next = lines.getOrNull(1)?.removePrefix("next=")?.toLongOrNull() ?: return null
            val list = lines.drop(2).mapNotNull { line ->
                val p = line.split('|')
                if (p.size != 7) return@mapNotNull null
                CameraWatch(
                    id = p[0].toLongOrNull() ?: return@mapNotNull null,
                    sensorIds = p[1].split(',').filter { it.isNotEmpty() }.map { dec(it) },
                    areaNames = p[2].split(',').filter { it.isNotEmpty() }.map { dec(it) },
                    objectGroup = dec(p[3]),
                    createdAtMs = p[4].toLongOrNull() ?: return@mapNotNull null,
                    expiresAtMs = p[5].toLongOrNull() ?: return@mapNotNull null,
                    once = p[6] == "1"
                )
            }
            return next to list
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
