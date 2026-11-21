package com.treko.assistant.meditation

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The phone's own text-to-speech, slower and a little lower than usual for a calm voice.
 * Speaks in the language the script strings were resolved in.
 */
class PhoneTtsMeditationVoice(
    context: Context,
    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
) : MeditationVoice {
    private val appContext = context.applicationContext

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initFailed = false
    @Volatile private var current: CompletableDeferred<Boolean>? = null
    private val seq = AtomicInteger()

    override val label: String = "phone"

    override fun isAvailable(): Boolean = !initFailed

    private suspend fun engine(): TextToSpeech? {
        tts?.takeIf { ready }?.let { return it }
        if (initFailed) return null
        return withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    var created: TextToSpeech? = null
                    try {
                        created = TextToSpeech(appContext) { status ->
                            val ok = status == TextToSpeech.SUCCESS
                            ready = ok
                            initFailed = !ok
                            if (ok) created?.let { configure(it) }
                            if (cont.isActive) cont.resume(if (ok) created else null)
                        }
                        tts = created
                    } catch (e: Exception) {
                        initFailed = true
                        TrekoLogBuffer.w(TAG, "Phone TTS could not start: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
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
            override fun onDone(utteranceId: String?) {
                current?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                current?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                current?.complete(false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                current?.complete(false)
            }
        })
    }

    override suspend fun speak(text: String): Boolean {
        val engine = engine() ?: return false
        val done = CompletableDeferred<Boolean>()
        current?.complete(false)
        current = done
        val id = "meditation_${seq.incrementAndGet()}"
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            current = null
            return false
        }
        val timeoutMs = (MeditationTimelineBuilder.estimateSpeechMs(text) / SPEECH_RATE * 2).toLong() + 8_000L
        val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        if (current === done) current = null
        return ok
    }

    override fun stop() {
        current?.complete(false)
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        stop()
        val engine = tts
        tts = null
        ready = false
        try {
            engine?.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MeditationVoice"
        const val SPEECH_RATE = 0.85f
        const val PITCH = 0.92f
    }
}
