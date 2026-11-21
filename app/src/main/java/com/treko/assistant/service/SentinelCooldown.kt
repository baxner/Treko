package com.treko.assistant.service

/**
 * How long a camera rests after a sentinel run, by outcome: a short pause after "nothing
 * new" (so the next real event a minute later is looked at), the long one after an alert
 * (the same visitor is not announced again and again).
 */
internal object SentinelCooldown {
    /** While a run is in progress (the vision call can take tens of seconds). */
    const val WHILE_RUNNING_MS = 3 * 60_000L
    const val AFTER_NO_NEWS_MS = 75_000L
    const val AFTER_ALERT_MS = 10 * 60_000L

    /** Decision recorded when a camera watch already alerts this very detection. */
    const val DECISION_WATCH_ALERTED = "watch_alerted"

    /**
     * Rest after a run that ended with [decision] (see [com.treko.assistant.data.SentinelRunTrace]),
     * or null when the run did not happen and the camera should not rest at all.
     */
    fun afterOutcome(decision: String?, ran: Boolean): Long? = when {
        !ran -> null
        decision == null -> AFTER_NO_NEWS_MS
        decision.startsWith("alert") || decision == "suppressed" || decision == DECISION_WATCH_ALERTED -> AFTER_ALERT_MS
        else -> AFTER_NO_NEWS_MS
    }
}
