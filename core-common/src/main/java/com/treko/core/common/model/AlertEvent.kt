package com.treko.core.common.model

/** What produced an entry of the alerts log. */
enum class AlertSource {
    /** A "tell me when you see …" camera watch fired. */
    WATCH,
    /** The camera sentinel (vision check of a camera, triggered by a detector or the periodic scan). */
    SENTINEL,
    /** A proactive briefing (home, habits, tasks…) that was delivered. */
    PROACTIVE,
    /** A timer or alarm finished. */
    TIMER,
    /** Reserved: 3D printer finished or failed (only with the OctoPrint skill). */
    PRINTER
}

/** How much an entry matters. */
enum class AlertLevel {
    /** Something the user was (or should have been) told about. Counts as unread. */
    ALERT,
    /** A check that found nothing ("sin novedad"). Kept for 24 h, never with images, never unread. */
    NO_NEWS
}

/** What the user said about an alert from the alerts screen. */
enum class AlertFeedback { NONE, USEFUL, FALSE_ALARM }

/**
 * One entry of the alerts log ("Avisos"). Everything except [id], [timestampMs], [source]
 * and [title] is optional, so every source fills in only what it knows; the picture and the
 * vision description usually arrive a few seconds later through an update.
 */
data class AlertEvent(
    /** Assigned by the log when the event is recorded (unique, increasing). */
    val id: Long = 0L,
    val timestampMs: Long,
    val source: AlertSource,
    val level: AlertLevel = AlertLevel.ALERT,
    /** Short headline, e.g. "Hay alguien en la entrada". */
    val title: String,
    /** Place or camera name the user knows (area, camera friendly name). */
    val place: String? = null,
    /** Home Assistant camera entity the alert is about, when there is one. */
    val cameraEntityId: String? = null,
    /** Detector label (person, car, animal…) or a source specific kind (timer…). */
    val objectLabel: String? = null,
    /** What Treko said or would have said about it. */
    val spokenSummary: String? = null,
    /** What the vision model saw. */
    val visionDescription: String? = null,
    /** Absolute path of the full-size picture (JPEG), when one was kept. */
    val snapshotPath: String? = null,
    /** Absolute path of the thumbnail (JPEG), when one was kept. */
    val thumbPath: String? = null,
    /** Treko actually said it out loud. */
    val spoken: Boolean = false,
    /** A notification was posted for it. */
    val notified: Boolean = false,
    val read: Boolean = false,
    val feedback: AlertFeedback = AlertFeedback.NONE,
    /** Time from the trigger to the verdict or alert, when measured. */
    val latencyMs: Long? = null,
    /** Notification posted for it, so the app can clear it once the alert is read. */
    val notificationId: Int? = null,
    /** Small source specific facts (insight category, trigger sensor, reason…). */
    val extra: Map<String, String> = emptyMap()
) {
    val imagePaths: List<String> get() = listOfNotNull(snapshotPath, thumbPath)

    /** Counts towards the unread badge. */
    val isUnread: Boolean get() = level == AlertLevel.ALERT && !read
}
