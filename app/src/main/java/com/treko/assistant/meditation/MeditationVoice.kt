package com.treko.assistant.meditation

/** How a [MeditationVoice.speak] ended. */
enum class SpeakResult {
    /** The cue was said to the end. */
    SAID,

    /** The voice could not say it (not connected, timed out, engine error). */
    FAILED,

    /** Cut on purpose (pause, stop, the wake word): never a reason to repeat it with another voice. */
    CANCELLED
}

/**
 * Something that can say a meditation cue out loud. [speak] suspends until the cue was
 * said, failed, or was cut by [stop] or by cancelling the calling coroutine (which must
 * also stop the speech, before or after it began).
 */
interface MeditationVoice {
    /** Short label for logs. */
    val label: String

    /** False when this voice cannot speak right now (Xiaozhi disconnected, no TTS engine). */
    fun isAvailable(): Boolean

    suspend fun speak(text: String): SpeakResult

    /** Cuts the cue being said, if any; the pending [speak] returns [SpeakResult.CANCELLED]. */
    fun stop()

    /** Gets ready to speak soon (e.g. starts a TTS engine), so the first cue is not lost. */
    fun warmUp() {}

    /** Frees what the voice holds (end of session). */
    fun release() {}
}

/**
 * Treko's own voice: the cue goes to the Xiaozhi model through the proactive path, which
 * reads it word for word. The [channel] is the assistant service.
 */
class XiaozhiMeditationVoice(private val channel: Channel) : MeditationVoice {
    interface Channel {
        fun isConnected(): Boolean

        /**
         * Has the model say [text] verbatim, without opening the mic. Cancelling the caller
         * must stop it, also before the trigger was sent.
         */
        suspend fun deliverCue(text: String): SpeakResult

        /** Cancels the cue in flight, if any. */
        fun cancelCue()
    }

    override val label: String = "treko"
    override fun isAvailable(): Boolean = channel.isConnected()
    override suspend fun speak(text: String): SpeakResult = channel.deliverCue(text)
    override fun stop() = channel.cancelCue()
}
