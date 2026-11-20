package com.treko.assistant.service

import com.treko.core.common.model.TrekoState

/**
 * Decides whether a wake word detection should start a new turn.
 *
 * "DTW" is the personalized template matcher: sensitive, so it gets extra filters.
 * "KWS" is the keyword-spotting model.
 */
object WakeDetectionPolicy {
    const val SOURCE_DTW = "DTW"
    private const val DTW_MIN_SNR_DB = 4.5f
    private const val DTW_POST_TIMEOUT_MS = 750L

    /** [sinceTimeoutMs] is null when no listening timeout has happened yet. */
    fun rejectionReason(state: TrekoState, source: String, snrDb: Float, sinceTimeoutMs: Long?): String? {
        val isDtw = source == SOURCE_DTW
        if (isDtw && snrDb < DTW_MIN_SNR_DB) return "low SNR"
        if (isDtw && sinceTimeoutMs != null && sinceTimeoutMs < DTW_POST_TIMEOUT_MS) return "right after a listening timeout"
        // While THINKING the room is often still talking (the user's last sentence, other
        // people, the TV). DTW alone kept restarting the turn there, so only KWS may interrupt.
        if (isDtw && state == TrekoState.THINKING) return "DTW-only detection while thinking"
        return null
    }
}
