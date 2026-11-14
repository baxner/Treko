package com.treko.core.audio

import kotlin.math.max

/**
 * Compuerta acústica de cercanía (Near-Field SNR Gate) para el estado LISTENING.
 *
 * Evita que voces de fondo moderadas de televisión o películas mantengan abierto el turno
 * o se filtren al servidor antes de que el usuario hable:
 * - Exige margen SNR sobre el piso de ruido ambiental rastreado por AdaptiveVad.
 * - Mantiene un buffer circular pre-roll (~360ms) antes del inicio de voz para no cortar la primera sílaba.
 * - Aplica hangover (~420ms) entre palabras y corta limpio al superar endOfSpeechSilenceMs.
 */
class NearFieldListeningGate(
    @Volatile var nearFieldEnabled: Boolean = true,
    @Volatile var minSnrDb: Float = 7.5f,
    private val preRollFramesCapacity: Int = 6,
    private val hangoverFramesCount: Int = 7,
    @Volatile var endOfSpeechSilenceMs: Long = 1150L
) {
    data class GateDecision(
        val isNearFieldSpeech: Boolean,
        val shouldEmitAudio: Boolean,
        val framesToEmit: List<ShortArray>,
        val shouldCloseTurnOnSilence: Boolean,
        val speechStartedJustNow: Boolean,
        val silenceDurationMs: Long
    )

    private val preRollQueue = ArrayDeque<ShortArray>(preRollFramesCapacity + 1)

    @Volatile
    var speechStarted: Boolean = false
        private set

    private var hangoverRemaining: Int = 0
    private var lastNearFieldSpeechMs: Long = 0L

    fun reset(nowMs: Long = System.currentTimeMillis()) {
        preRollQueue.clear()
        speechStarted = false
        hangoverRemaining = 0
        lastNearFieldSpeechMs = nowMs
    }

    fun evaluateFrame(
        pcm: ShortArray,
        count: Int,
        metrics: AdaptiveVad.FrameMetrics,
        nowMs: Long
    ): GateDecision {
        val safeCount = minOf(count, pcm.size).coerceAtLeast(0)
        val frameCopy = pcm.copyOf(safeCount)

        if (!nearFieldEnabled) {
            val legacySpeech = metrics.isSpeech || metrics.rms > 0.015f
            val startedNow = !speechStarted && legacySpeech
            if (legacySpeech) {
                speechStarted = true
                lastNearFieldSpeechMs = nowMs
            }
            val silenceDuration = if (speechStarted) (nowMs - lastNearFieldSpeechMs).coerceAtLeast(0L) else 0L
            val close = speechStarted && silenceDuration > 1250L
            return GateDecision(
                isNearFieldSpeech = legacySpeech,
                shouldEmitAudio = true,
                framesToEmit = listOf(frameCopy),
                shouldCloseTurnOnSilence = close,
                speechStartedJustNow = startedNow,
                silenceDurationMs = silenceDuration
            )
        }

        val plausibleSpeechZcr = metrics.zcr in 0.012f..0.58f
        val rmsOverrideThreshold = max(0.032f, metrics.noiseFloorRms * 3.0f)
        val instantNearFieldSpeech =
            (metrics.isSpeech && metrics.snrDb >= minSnrDb) ||
                (plausibleSpeechZcr && metrics.rms >= rmsOverrideThreshold && metrics.snrDb >= (minSnrDb - 1.5f))

        if (instantNearFieldSpeech) {
            hangoverRemaining = hangoverFramesCount
        } else if (hangoverRemaining > 0) {
            hangoverRemaining--
        }

        val activeNearField = instantNearFieldSpeech || hangoverRemaining > 0

        if (!speechStarted) {
            if (!activeNearField) {
                if (preRollFramesCapacity > 0) {
                    if (preRollQueue.size >= preRollFramesCapacity) {
                        preRollQueue.removeFirst()
                    }
                    preRollQueue.addLast(frameCopy)
                }
                return GateDecision(
                    isNearFieldSpeech = false,
                    shouldEmitAudio = false,
                    framesToEmit = emptyList(),
                    shouldCloseTurnOnSilence = false,
                    speechStartedJustNow = false,
                    silenceDurationMs = 0L
                )
            }

            // First near-field speech onset: flush pre-roll + current frame so opening syllable isn't clipped
            speechStarted = true
            lastNearFieldSpeechMs = nowMs
            val burst = ArrayList<ShortArray>(preRollQueue.size + 1)
            burst.addAll(preRollQueue)
            preRollQueue.clear()
            burst.add(frameCopy)

            return GateDecision(
                isNearFieldSpeech = true,
                shouldEmitAudio = true,
                framesToEmit = burst,
                shouldCloseTurnOnSilence = false,
                speechStartedJustNow = true,
                silenceDurationMs = 0L
            )
        }

        if (instantNearFieldSpeech) {
            lastNearFieldSpeechMs = nowMs
        }

        val silenceDuration = (nowMs - lastNearFieldSpeechMs).coerceAtLeast(0L)
        val shouldClose = silenceDuration > endOfSpeechSilenceMs

        return GateDecision(
            isNearFieldSpeech = activeNearField,
            shouldEmitAudio = activeNearField,
            framesToEmit = if (activeNearField) listOf(frameCopy) else emptyList(),
            shouldCloseTurnOnSilence = shouldClose,
            speechStartedJustNow = false,
            silenceDurationMs = silenceDuration
        )
    }
}
