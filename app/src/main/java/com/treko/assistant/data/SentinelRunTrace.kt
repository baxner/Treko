package com.treko.assistant.data

import com.treko.core.common.util.LogText
import kotlinx.coroutines.Deferred
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What happened in one camera-sentinel run, written to the log as a single line.
 *
 * It travels in the coroutine context, so each step of the run (the coordinator, the
 * service, [TrekoProactiveManager] and the camera scan) can add what it did without new
 * parameters. Only sentinel runs carry one; regular proactive checks find none.
 */
class SentinelRunTrace(
    /** Detector entity that fired (e.g. a Frigate occupancy sensor). */
    val sensorId: String,
    /** Camera linked to that detector, when known. */
    val cameraEntityId: String?,
    val area: String,
    /** Detector label (Frigate object: person, car…), when known. */
    val label: String?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** The detector's own picture of the detection (Frigate image entity), when there is one. */
    val snapshotEntityId: String? = null
) : AbstractCoroutineContextElement(Key) {

    /**
     * The detector's picture, fetched as soon as the run starts (in parallel with the vision
     * call) for the alert's notification and the alerts log. Null when there is none.
     */
    @Volatile
    var detectionImage: Deferred<ByteArray?>? = null

    companion object Key : CoroutineContext.Key<SentinelRunTrace> {
        const val VERDICT_PREVIEW_CHARS = 160
    }

    /** One camera the vision model looked at. */
    data class Look(
        val cameraName: String,
        val imageEntityId: String,
        val durationMs: Long,
        val verdict: String,
        val anomaly: Boolean?
    )

    val startedAtMs: Long = clockMs()
    private val lock = Any()
    private val stages = LinkedHashMap<String, Long>()
    private val looks = ArrayList<Look>()
    private val notes = ArrayList<String>()
    private var decisionValue: String? = null
    private var reasonValue: String? = null

    fun stage(name: String, durationMs: Long) {
        synchronized(lock) { stages[name] = (stages[name] ?: 0L) + durationMs.coerceAtLeast(0L) }
    }

    /** Runs [block] and adds its duration to stage [name]. */
    inline fun <T> timed(name: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            stage(name, System.currentTimeMillis() - start)
        }
    }

    fun look(cameraName: String, imageEntityId: String, durationMs: Long, verdict: String?, anomaly: Boolean?) {
        synchronized(lock) {
            looks += Look(cameraName, imageEntityId, durationMs, verdict.orEmpty(), anomaly)
        }
    }

    /** Free-form fact worth keeping (e.g. which cameras Frigate saw busy). */
    fun note(text: String) {
        synchronized(lock) { notes += text }
    }

    /**
     * Records the outcome. The first decision sticks unless [override] (the service's final
     * "spoken / pending" result replaces an earlier "alert" from the manager).
     */
    fun decide(decision: String, reason: String? = null, override: Boolean = false) {
        synchronized(lock) {
            if (decisionValue != null && !override) return
            decisionValue = decision
            reasonValue = reason
        }
    }

    val hasDecision: Boolean get() = synchronized(lock) { decisionValue != null }

    /** Outcome so far ("alert", "normal", "suppressed", "skipped"…), null if none yet. */
    val decision: String? get() = synchronized(lock) { decisionValue }

    val reason: String? get() = synchronized(lock) { reasonValue }

    /** The cameras looked at so far, in order. */
    fun looks(): List<Look> = synchronized(lock) { looks.toList() }

    /** The single log line for this run. */
    fun format(nowMs: Long = clockMs()): String = synchronized(lock) {
        buildString {
            append("Sentinel run: trigger=").append(sensorId)
            label?.let { append(" label=").append(it) }
            append(" camera=").append(cameraEntityId ?: "?")
            append(" area=\"").append(area).append('"')
            if (looks.isEmpty()) {
                append(" | looked=none")
            } else {
                looks.forEachIndexed { i, look ->
                    append(if (i == 0) " | looked: " else "; ")
                    append('"').append(look.cameraName).append("\" image=").append(look.imageEntityId)
                    append(' ').append(look.durationMs).append("ms")
                    look.anomaly?.let { append(" anomaly=").append(it) }
                    val verdict = LogText.preview(look.verdict, VERDICT_PREVIEW_CHARS)
                    append(" verdict=\"").append(verdict.ifEmpty { "<empty>" }).append('"')
                }
            }
            if (notes.isNotEmpty()) append(" | ").append(notes.joinToString("; "))
            append(" | stages:")
            stages.forEach { (name, ms) -> append(' ').append(name).append('=').append(ms).append("ms") }
            append(" total=").append((nowMs - startedAtMs).coerceAtLeast(0L)).append("ms")
            append(" | decision=").append(decisionValue ?: "unknown")
            reasonValue?.let { append(" reason=\"").append(it).append('"') }
        }
    }
}
