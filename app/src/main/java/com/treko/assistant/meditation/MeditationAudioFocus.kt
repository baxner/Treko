package com.treko.assistant.meditation

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.treko.core.common.util.TrekoLogBuffer

/**
 * The session's own audio focus, held while it runs, so a video, another player, a VoIP or
 * phone call pauses it. Treko's own speech asks for "may duck" focus, which this ignores.
 *
 * [onLost]: true for a short loss (a call, a notification), false when another app took
 * the audio for good. [onRegained]: focus is back after a short loss.
 */
class MeditationAudioFocus(
    private val audioManager: AudioManager?,
    private val onLost: (transient: Boolean) -> Unit,
    private val onRegained: () -> Unit
) {
    @Volatile var held = false
        private set

    private val request: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        held = false
                        onLost(false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLost(true)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        held = true
                        onRegained()
                    }
                    // Treko speaking (or a notification sound): the music just ducks.
                    else -> Unit
                }
            }
            .build()
    }

    /** Asks for focus; true when held. */
    fun acquire(): Boolean {
        if (held) return true
        val am = audioManager ?: return false
        held = try {
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Meditation audio focus request failed: ${e.message}")
            false
        }
        return held
    }

    fun release() {
        val am = audioManager ?: return
        held = false
        try {
            am.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "Meditation"
    }
}
