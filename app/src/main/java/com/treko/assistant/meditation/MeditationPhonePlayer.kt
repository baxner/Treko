package com.treko.assistant.meditation

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.RadioBrowserClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Calm internet radio on the phone under the voice, at a low player level (the phone's
 * media volume is not touched, so there is nothing to put back).
 */
class MeditationPhonePlayer(private val radio: RadioBrowserClient) : PhoneMeditationMusic {
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var level = HomeMeditationRoom.PHONE_LEVEL

    override suspend fun start(type: MeditationType): Boolean = withContext(Dispatchers.IO) {
        val station = QUERIES[type].orEmpty().firstNotNullOfOrNull { q ->
            try {
                radio.findBestStation(q)
            } catch (_: Exception) {
                null
            }
        } ?: return@withContext false
        val started = withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setOnPreparedListener {
                    try {
                        it.setVolume(level, level)
                        it.isLooping = true
                        it.start()
                        player = it
                        if (cont.isActive) cont.resume(true)
                    } catch (_: Exception) {
                        it.release()
                        if (cont.isActive) cont.resume(false)
                    }
                }
                mp.setOnErrorListener { failed, what, extra ->
                    TrekoLogBuffer.w(TAG, "Meditation stream error what=$what extra=$extra")
                    if (player === failed) player = null
                    failed.release()
                    if (cont.isActive) cont.resume(false)
                    true
                }
                cont.invokeOnCancellation { if (player !== mp) mp.release() }
                try {
                    mp.setDataSource(station.streamUrl)
                    mp.prepareAsync()
                } catch (e: Exception) {
                    TrekoLogBuffer.w(TAG, "Meditation stream setup failed: ${e.message}")
                    mp.release()
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
        if (started) TrekoLogBuffer.i(TAG, "Meditation music on the phone: ${station.name}.")
        started
    }

    override fun setLevel(level: Float) {
        this.level = level.coerceIn(0f, 1f)
        try {
            player?.setVolume(this.level, this.level)
        } catch (_: Exception) {
        }
    }

    override suspend fun fadeOutAndStop() {
        val mp = player ?: return
        val from = level
        for (i in 1..FADE_STEPS) {
            setLevel(from * (FADE_STEPS - i) / FADE_STEPS)
            delay(FADE_STEP_MS)
        }
        player = null
        try {
            if (mp.isPlaying) mp.stop()
        } catch (_: Exception) {
        }
        try {
            mp.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MeditationMusic"
        private const val FADE_STEPS = 8
        private const val FADE_STEP_MS = 400L

        /** Radio Browser tags (the same in every language); tried in order. */
        private val QUERIES = mapOf(
            MeditationType.SLEEP to listOf("sleep", "nature sounds", "meditation"),
            MeditationType.BODY_SCAN to listOf("meditation", "ambient"),
            MeditationType.MINDFULNESS to listOf("meditation", "ambient"),
            MeditationType.BREATH_478 to listOf("meditation", "ambient"),
            MeditationType.BOX_BREATHING to listOf("meditation", "ambient")
        )
    }
}
