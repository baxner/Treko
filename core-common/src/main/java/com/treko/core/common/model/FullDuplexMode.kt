package com.treko.core.common.model

/**
 * Conversation duplex mode during assistant speech playback (`TrekoState.SPEAKING`).
 */
enum class FullDuplexMode {
    /** Classic half-duplex turn taking: mic stays idle during TTS playback. */
    HALF_DUPLEX,

    /**
     * Keeps listening during TTS without interrupting playback; queues valid user addendums
     * and sends them automatically as the follow-up turn once TTS finishes draining.
     */
    COMPLEMENTARY,

    /** Immediate barge-in mode: interrupts TTS as soon as near-field speech is confirmed. */
    BARGE_IN_INTERRUPT;

    companion object {
        fun fromStored(value: String?): FullDuplexMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: COMPLEMENTARY
    }
}
