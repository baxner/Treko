package com.treko.assistant.meditation

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's own text-to-speech, slower and a little lower than usual for a calm voice.
 * Speaks in the language the script strings were resolved in.
 *
 * One engine per session: [warmUp] starts it early, every [speak] reuses the same start
 * (also one that is still binding), and [release] shuts it down, also when its start was
 * still in progress.
 */
class PhoneTtsMeditationVoice(
    context: Context,
    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
) : MeditationVoice {
    private val appContext = context.applicationContext
    private val lock = Any()

    /** The ready engine (set only once its init succeeded and it was configured). */
    @Volatile private var tts: TextToSpeech? = null
    private var init: CompletableDeferred<TextToSpeech?>? = null
    @Volatile private var released = false
    @Volatile private var initFailed = false

    /** The utterance being said and what its [speak] waits on. */
    private class Pending(val id: String, val done: CompletableDeferred<Boolean>)

    @Volatile private var current: Pending? = null
    @Volatile private var stoppedId: String? = null
    private val seq = AtomicInteger()

    override val label: String = "phone"

    override fun isAvailable(): Boolean = !initFailed && !released

    override fun warmUp() {
        startInit()
    }

    /** The engine start, shared by every caller; created once. */
    private fun startInit(): CompletableDeferred<TextToSpeech?> = synchronized(lock) {
        init?.let { return it }
        val deferred = CompletableDeferred<TextToSpeech?>()
        init = deferred
        if (released) {
            deferred.complete(null)
            return deferred
        }
        Handler(Looper.getMainLooper()).post {
            if (released) {
                deferred.complete(null)
                return@post
            }
            var created: TextToSpeech? = null
            try {
                created = TextToSpeech(appContext) { status ->
                    val engine = created
                    val ok = status == TextToSpeech.SUCCESS && engine != null
                    if (ok) {
                        try {
                            configure(engine!!)
                        } catch (e: Exception) {
                            TrekoLogBuffer.w(TAG, "Phone TTS setup failed: ${e.message}")
                        }
                    } else {
                        initFailed = true
                    }
                    val keep = ok && synchronized(lock) {
                        if (released) false else {
                            tts = engine
                            true
                        }
                    }
                    if (!keep) shutdownQuietly(engine)
                    deferred.complete(if (keep) engine else null)
                }
                // An engine that failed at once (no TTS installed) answered inside the constructor.
                if (deferred.isCompleted && tts !== created) shutdownQuietly(created)
            } catch (e: Exception) {
                initFailed = true
                TrekoLogBuffer.w(TAG, "Phone TTS could not start: ${e.message}")
                shutdownQuietly(created)
                deferred.complete(null)
            }
        }
        deferred
    }

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        if (initFailed || released) return null
        // A slow first start is not abandoned: the next cue waits on the same one.
        return withTimeoutOrNull(INIT_WAIT_MS) { startInit().await() }
    }

    private fun configure(engine: TextToSpeech) {
        val lang = listOf(locale, Locale(locale.language)).firstOrNull {
            engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
        }
        if (lang != null) engine.language = lang
        engine.setSpeechRate(SPEECH_RATE)
        engine.setPitch(PITCH)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    /** Only the callback of the utterance being waited on ends it (a late one of an earlier cue does not). */
    private fun finish(utteranceId: String?, ok: Boolean) {
        val pending = current ?: return
        if (pending.id == utteranceId) pending.done.complete(ok)
    }

    override suspend fun speak(text: String): SpeakResult {
        val engine = engine() ?: return SpeakResult.FAILED
        val id = "meditation_${seq.incrementAndGet()}"
        val pending = Pending(id, CompletableDeferred())
        current?.done?.complete(false)
        current = pending
        try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return SpeakResult.FAILED
            val timeoutMs = (MeditationTimelineBuilder.estimateSpeechMs(text) / SPEECH_RATE * 2).toLong() + 8_000L
            val ok = withTimeoutOrNull(timeoutMs) { pending.done.await() } ?: false
            return when {
                ok -> SpeakResult.SAID
                stoppedId == id -> SpeakResult.CANCELLED
                else -> SpeakResult.FAILED
            }
        } catch (e: CancellationException) {
            // Paused or stopped from the session: the queued cue must not play on.
            stopEngine(engine)
            throw e
        } finally {
            if (current === pending) current = null
        }
    }

    override fun stop() {
        val pending = current
        stoppedId = pending?.id
        pending?.done?.complete(false)
        tts?.let { stopEngine(it) }
    }

    override fun release() {
        stop()
        val engine = synchronized(lock) {
            released = true
            val e = tts
            tts = null
            e
        }
        shutdownQuietly(engine)
    }

    private fun stopEngine(engine: TextToSpeech) {
        try {
            engine.stop()
        } catch (_: Exception) {
        }
    }

    private fun shutdownQuietly(engine: TextToSpeech?) {
        try {
            engine?.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MeditationVoice"
        private const val INIT_WAIT_MS = 6_000L
        const val SPEECH_RATE = 0.85f
        const val PITCH = 0.92f
    }
}
