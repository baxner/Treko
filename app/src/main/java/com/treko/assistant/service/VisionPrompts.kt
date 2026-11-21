package com.treko.assistant.service

import android.content.Context
import com.treko.assistant.R
import com.treko.core.mcp.watch.WatchCatalog
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Builds the vision prompts (camera sentinel and watch alerts) from string resources. Both ask
 * for the same JSON verdict (see [com.treko.assistant.data.parseVisionVerdict]); the context
 * line holds only what Treko knows about this look: the camera, what the detector saw and
 * where, the local time and whether the user asked to watch the place. Nothing about the
 * people of the household is ever sent.
 */
internal class VisionPrompts(context: Context) {
    private val ctx = context.applicationContext

    /**
     * The sentinel's prompt. [label] is the detector's object (null for the periodic check
     * with no detector); [zone] only when known.
     */
    fun sentinel(
        cameraName: String,
        label: String?,
        zone: String? = null,
        watchRequested: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): String = ctx.getString(
        R.string.vision_verdict_prompt,
        contextLine(cameraName, label, zone, watchRequested, nowMs),
        ""
    )

    /** The watch alert's prompt: same verdict, and it always describes what it sees. */
    fun watch(
        cameraName: String,
        label: String?,
        objectGroup: String,
        zone: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): String = ctx.getString(
        R.string.vision_verdict_prompt,
        contextLine(cameraName, label ?: objectGroup, zone, watchRequested = true, nowMs = nowMs),
        ctx.getString(R.string.watch_vision_question, labelText(WatchCatalog.groupOfLabel(label) ?: objectGroup))
    )

    private fun contextLine(cameraName: String, label: String?, zone: String?, watchRequested: Boolean, nowMs: Long): String {
        val camera = cameraName.ifBlank { "?" }
        val parts = ArrayList<String>()
        parts += if (label.isNullOrBlank()) {
            ctx.getString(R.string.vision_ctx_periodic, camera)
        } else {
            ctx.getString(R.string.vision_ctx_detector, labelText(label), camera)
        }
        zone?.takeIf { it.isNotBlank() && !it.equals(cameraName, ignoreCase = true) }?.let {
            parts += ctx.getString(R.string.vision_ctx_zone, it)
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nowMs))
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        parts += ctx.getString(if (isNight(hour)) R.string.vision_ctx_time_night else R.string.vision_ctx_time_day, time)
        parts += ctx.getString(if (watchRequested) R.string.vision_ctx_watch_yes else R.string.vision_ctx_watch_no)
        return parts.joinToString(" ")
    }

    private fun labelText(label: String): String = when (WatchCatalog.groupOfLabel(label) ?: label.lowercase()) {
        "person" -> ctx.getString(R.string.vision_label_person)
        "car" -> ctx.getString(R.string.vision_label_vehicle)
        "animal" -> ctx.getString(R.string.vision_label_animal)
        "package" -> ctx.getString(R.string.vision_label_package)
        "motion" -> ctx.getString(R.string.vision_label_motion)
        "any" -> ctx.getString(R.string.vision_label_any)
        else -> label
    }

    companion object {
        /** Rough day/night from the local hour (no sun data needed): night is 19:00–06:59. */
        fun isNight(hourOfDay: Int): Boolean = hourOfDay >= 19 || hourOfDay < 7
    }
}
