package com.treko.core.audio

import com.treko.core.common.model.AudioLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enlace JNI con el motor nativo de audio AAudio/Oboe en C++.
 */
object NativeAudioEngine {

    init {
        try {
            System.loadLibrary("treko_audio")
        } catch (e: UnsatisfiedLinkError) {
            // Permitir pruebas unitarias en JVM
        }
    }

    private val _audioLevel = MutableStateFlow(AudioLevel())
    val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()

    external fun nativeInitAudio(sampleRate: Int, channels: Int): Boolean
    external fun nativeStartRecording(): Boolean
    external fun nativeReadPcm(buffer: ShortArray, numSamples: Int): Int
    external fun nativeCalculateRms(buffer: ShortArray, length: Int): Float
    external fun nativeStopRecording()

    fun updateLevel(rms: Float) {
        val bands = FloatArray(5) { i ->
            // Simular bandas reactivas para ecualizador según RMS
            val factor = when(i) {
                0 -> 1.2f
                1 -> 1.5f
                2 -> 1.8f
                3 -> 1.3f
                else -> 0.9f
            }
            (rms * factor).coerceIn(0f, 1f)
        }
        _audioLevel.value = AudioLevel(
            rms = rms,
            isSpeaking = rms > 0.03f,
            bands = bands
        )
    }
}
