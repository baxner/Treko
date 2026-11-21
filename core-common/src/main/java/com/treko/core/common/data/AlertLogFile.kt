package com.treko.core.common.data

import androidx.core.util.AtomicFile
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertFeedback
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.model.AlertSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * The alerts log on disk: one JSON document written with [AtomicFile], so a crash or a
 * killed process mid-write never leaves a half-written log. Not SharedPreferences: the log
 * grows to hundreds of entries and would be rewritten as XML and kept in memory.
 */
internal class AlertLogFile(private val file: File) : AlertPersistence {
    private val atomic = AtomicFile(file)

    override fun save(events: List<AlertEvent>) {
        file.parentFile?.mkdirs()
        val bytes = AlertEventJson.encode(events).toByteArray(Charsets.UTF_8)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    override fun load(): List<AlertEvent> {
        val text = try {
            atomic.readFully().toString(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return AlertEventJson.decode(text)
    }
}

/** org.json codec of the alerts log. Unknown or broken entries are skipped, never fatal. */
internal object AlertEventJson {
    private const val VERSION = 1

    fun encode(events: List<AlertEvent>): String {
        val array = JSONArray()
        events.forEach { array.put(toJson(it)) }
        return JSONObject().put("v", VERSION).put("events", array).toString()
    }

    fun decode(text: String): List<AlertEvent> {
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        val array = root.optJSONArray("events") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { runCatching { fromJson(it) }.getOrNull() }
        }
    }

    private fun toJson(e: AlertEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("ts", e.timestampMs)
        put("source", e.source.name)
        put("level", e.level.name)
        put("title", e.title)
        putOpt("place", e.place)
        putOpt("camera", e.cameraEntityId)
        putOpt("label", e.objectLabel)
        putOpt("spoken_text", e.spokenSummary)
        putOpt("vision", e.visionDescription)
        putOpt("snapshot", e.snapshotPath)
        putOpt("thumb", e.thumbPath)
        put("spoken", e.spoken)
        put("notified", e.notified)
        put("read", e.read)
        put("feedback", e.feedback.name)
        e.latencyMs?.let { put("latency_ms", it) }
        e.notificationId?.let { put("notification_id", it) }
        if (e.extra.isNotEmpty()) {
            put("extra", JSONObject().also { obj -> e.extra.forEach { (k, v) -> obj.put(k, v) } })
        }
    }

    private fun fromJson(o: JSONObject): AlertEvent {
        val extra = o.optJSONObject("extra")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.orEmpty()
        return AlertEvent(
            id = o.getLong("id"),
            timestampMs = o.getLong("ts"),
            source = AlertSource.valueOf(o.getString("source")),
            level = runCatching { AlertLevel.valueOf(o.optString("level")) }.getOrDefault(AlertLevel.ALERT),
            title = o.optString("title"),
            place = o.stringOrNull("place"),
            cameraEntityId = o.stringOrNull("camera"),
            objectLabel = o.stringOrNull("label"),
            spokenSummary = o.stringOrNull("spoken_text"),
            visionDescription = o.stringOrNull("vision"),
            snapshotPath = o.stringOrNull("snapshot"),
            thumbPath = o.stringOrNull("thumb"),
            spoken = o.optBoolean("spoken"),
            notified = o.optBoolean("notified"),
            read = o.optBoolean("read"),
            feedback = runCatching { AlertFeedback.valueOf(o.optString("feedback")) }.getOrDefault(AlertFeedback.NONE),
            latencyMs = if (o.has("latency_ms")) o.optLong("latency_ms") else null,
            notificationId = if (o.has("notification_id")) o.optInt("notification_id") else null,
            extra = extra
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
