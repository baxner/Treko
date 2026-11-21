package com.treko.assistant.meditation

/**
 * Something that can say a meditation cue out loud. [speak] suspends until the cue was
 * said (true) or failed, timed out or was interrupted by [stop] (false).
 */
interface MeditationVoice {
    /** Short label for logs. */
    val label: String

    /** False when this voice cannot speak right now (Xiaozhi disconnected, no TTS engine). */
    fun isAvailable(): Boolean

    suspend fun speak(text: String): Boolean

    /** Cuts the cue being said, if any; the pending [speak] returns false. */
    fun stop()

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

        /** Has the model say [text] verbatim; true once the speech ended normally. Must not open the mic. */
        suspend fun deliverCue(text: String): Boolean

        /** Cancels the cue in flight, if any. */
        fun cancelCue()
    }

    override val label: String = "treko"
    override fun isAvailable(): Boolean = channel.isConnected()
    override suspend fun speak(text: String): Boolean = channel.deliverCue(text)
    override fun stop() = channel.cancelCue()
}
