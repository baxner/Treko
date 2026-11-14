package com.treko.core.audio

import com.treko.core.common.model.FullDuplexIndicator
import kotlin.math.max

/**
 * Buffers complementary user remarks spoken while Treko is playing TTS without cutting playback.
 *
 * Pure JVM state machine (zero Android dependencies) separating:
 * - Short backchannels / residual transients (< [minComplementarySpeechMs]) -> discarded silently.
 * - Completed complementary utterances -> queued and flushed automatically on TTS completion.
 * - Utterances still in progress when TTS stops -> handed off seamlessly into live `LISTENING`.
 */
class FullDuplexComplementaryQueue(
    var minComplementarySpeechMs: Long = DEFAULT_MIN_COMPLEMENTARY_SPEECH_MS,
    nearFieldEnabled: Boolean = true,
    minSnrDb: Float = 8.0f,
    private val maxBufferedFrames: Int = MAX_BUFFERED_FRAMES
) {
    enum class FlushDecision {
        NONE,
        SEND_COMPLETE_UTTERANCE,
        HANDOFF_LIVE_LISTENING
    }

    enum class BiometricVerdict {
        DISABLED,
        PENDING,
        VERIFIED,
        REJECTED
    }

    data class QueueSnapshot(
        val indicator: FullDuplexIndicator,
        val activeSpeechNow: Boolean,
        val hasConfirmedUtterance: Boolean,
        val bufferedFrameCount: Int,
        val confirmedSpeechMs: Long,
        val needsBiometricCheck: Boolean
    )

    data class FlushPlan(
        val decision: FlushDecision,
        val pcmFrames: List<ShortArray>,
        val totalVoicedMs: Long
    )

    private val lock = Any()
    private val gate = NearFieldListeningGate(
        nearFieldEnabled = nearFieldEnabled,
        minSnrDb = minSnrDb,
        preRollFramesCapacity = 5,
        hangoverFramesCount = 6,
        endOfSpeechSilenceMs = 900L
    )

    private val bufferedFrames = ArrayList<ShortArray>(64)
    private var voicedFrameCount: Int = 0
    private var hasConfirmedUtterance: Boolean = false
    private var isSpeechActiveNow: Boolean = false
    private var biometricRequired: Boolean = false
    private var biometricVerdict: BiometricVerdict = BiometricVerdict.DISABLED
    private var generation: Long = 0L

    fun configurePolicy(
        minSpeechMs: Long,
        nearFieldEnabled: Boolean,
        minSnrDb: Float,
        biometricEnabled: Boolean
    ) {
        synchronized(lock) {
            minComplementarySpeechMs = minSpeechMs.coerceIn(240L, 1200L)
            gate.nearFieldEnabled = nearFieldEnabled
            gate.minSnrDb = minSnrDb.coerceIn(3.5f, 20f)
            biometricRequired = biometricEnabled
            if (!hasConfirmedUtterance && bufferedFrames.isEmpty()) {
                biometricVerdict = if (biometricEnabled) BiometricVerdict.PENDING else BiometricVerdict.DISABLED
            }
        }
    }

    fun reset(biometricEnabled: Boolean = biometricRequired, nowMs: Long = 0L) {
        synchronized(lock) {
            generation++
            gate.reset(nowMs)
            bufferedFrames.clear()
            voicedFrameCount = 0
            hasConfirmedUtterance = false
            isSpeechActiveNow = false
            biometricRequired = biometricEnabled
            biometricVerdict = if (biometricEnabled) BiometricVerdict.PENDING else BiometricVerdict.DISABLED
        }
    }

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun setBiometricVerdict(expectedGeneration: Long, verified: Boolean) {
        synchronized(lock) {
            if (expectedGeneration != generation) return
            if (verified) {
                biometricVerdict = BiometricVerdict.VERIFIED
            } else {
                biometricVerdict = BiometricVerdict.REJECTED
                // Drop unauthorized third-party utterance immediately
                bufferedFrames.clear()
                voicedFrameCount = 0
                hasConfirmedUtterance = false
                isSpeechActiveNow = false
            }
        }
    }

    fun snapshotBiometricPcm(expectedGeneration: Long): ShortArray? {
        synchronized(lock) {
            if (expectedGeneration != generation || bufferedFrames.isEmpty()) return null
            val totalSamples = bufferedFrames.sumOf { it.size }
            if (totalSamples < MIN_BIOMETRIC_SAMPLES) return null
            val out = ShortArray(minOf(totalSamples, MAX_BIOMETRIC_SAMPLES))
            var offset = 0
            for (frame in bufferedFrames) {
                val copyLen = minOf(frame.size, out.size - offset)
                if (copyLen <= 0) break
                System.arraycopy(frame, 0, out, offset, copyLen)
                offset += copyLen
            }
            return out
        }
    }

    fun onSpeakingFrame(
        cleanPcm16k: ShortArray,
        count: Int,
        metrics: AdaptiveVad.FrameMetrics,
        playbackRms: Float,
        nowMs: Long
    ): QueueSnapshot {
        synchronized(lock) {
            val safeCount = minOf(count, cleanPcm16k.size).coerceAtLeast(0)
            if (safeCount == 0) {
                return snapshotLocked()
            }

            if (biometricVerdict == BiometricVerdict.REJECTED) {
                val decision = gate.evaluateFrame(cleanPcm16k, safeCount, metrics, nowMs)
                if (decision.shouldCloseTurnOnSilence) {
                    // Ready for next speaker burst once foreign voice finishes
                    generation++
                    gate.reset(nowMs)
                    bufferedFrames.clear()
                    voicedFrameCount = 0
                    hasConfirmedUtterance = false
                    isSpeechActiveNow = false
                    biometricVerdict = if (biometricRequired) BiometricVerdict.PENDING else BiometricVerdict.DISABLED
                }
                return snapshotLocked()
            }

            // Double-talk guard: during loud speaker bursts, demand either clear residual SNR
            // or sufficient post-AEC RMS relative to playback level so residual echo never queues.
            val doubleTalkMinRms = if (playbackRms > 0.04f) max(0.015f, playbackRms * 0.14f) else 0f
            val effectiveMetrics = if (playbackRms > 0.04f && metrics.rms < doubleTalkMinRms && metrics.snrDb < (gate.minSnrDb + 3.0f)) {
                metrics.copy(isSpeech = false)
            } else {
                metrics
            }

            val decision = gate.evaluateFrame(cleanPcm16k, safeCount, effectiveMetrics, nowMs)

            val isInstantVoicedSpeech = decision.isNearFieldSpeech &&
                (effectiveMetrics.isSpeech || effectiveMetrics.rms >= 0.032f)
            if (isInstantVoicedSpeech) {
                isSpeechActiveNow = true
                voicedFrameCount++
            } else if (!decision.isNearFieldSpeech) {
                isSpeechActiveNow = false
            }

            if (decision.shouldEmitAudio && decision.framesToEmit.isNotEmpty()) {
                for (frame in decision.framesToEmit) {
                    if (bufferedFrames.size < maxBufferedFrames) {
                        bufferedFrames.add(frame)
                    }
                }
            }

            val voicedMs = voicedFrameCount * FRAME_DURATION_MS
            if (voicedMs >= minComplementarySpeechMs && biometricVerdict != BiometricVerdict.REJECTED) {
                hasConfirmedUtterance = true
            }

            // User paused while Treko is still talking: close active burst and decide whether to keep or discard
            if (gate.speechStarted && decision.shouldCloseTurnOnSilence) {
                isSpeechActiveNow = false
                if (!hasConfirmedUtterance) {
                    // Backchannel / short noise (< minComplementarySpeechMs) -> discard cleanly
                    gate.reset(nowMs)
                    bufferedFrames.clear()
                    voicedFrameCount = 0
                    biometricVerdict = if (biometricRequired) BiometricVerdict.PENDING else BiometricVerdict.DISABLED
                } else {
                    // Preserve confirmed queued utterance, stop appending trailing silence
                    gate.reset(nowMs)
                }
            }

            return snapshotLocked()
        }
    }

    /**
     * Called once TTS playback has drained. Returns the buffered complementary speech and
     * resets the queue cleanly for the next turn.
     */
    fun onPlaybackFinished(): FlushPlan {
        synchronized(lock) {
            if (biometricVerdict == BiometricVerdict.REJECTED || bufferedFrames.isEmpty()) {
                resetLocked()
                return FlushPlan(FlushDecision.NONE, emptyList(), 0L)
            }

            val voicedMs = voicedFrameCount * FRAME_DURATION_MS
            val decision = when {
                // User started speaking shortly before TTS ended and is STILL talking -> seamless handoff
                isSpeechActiveNow && voicedFrameCount >= MIN_LIVE_HANDOFF_FRAMES ->
                    FlushDecision.HANDOFF_LIVE_LISTENING
                hasConfirmedUtterance ->
                    FlushDecision.SEND_COMPLETE_UTTERANCE
                else ->
                    FlushDecision.NONE
            }

            val frames = if (decision == FlushDecision.NONE) {
                emptyList()
            } else {
                bufferedFrames.toList()
            }

            resetLocked()
            return FlushPlan(
                decision = decision,
                pcmFrames = frames,
                totalVoicedMs = voicedMs
            )
        }
    }

    private fun resetLocked() {
        generation++
        gate.reset(0L)
        bufferedFrames.clear()
        voicedFrameCount = 0
        hasConfirmedUtterance = false
        isSpeechActiveNow = false
        biometricVerdict = if (biometricRequired) BiometricVerdict.PENDING else BiometricVerdict.DISABLED
    }

    private fun snapshotLocked(): QueueSnapshot {
        val voicedMs = voicedFrameCount * FRAME_DURATION_MS
        val indicator = when {
            isSpeechActiveNow && voicedFrameCount >= 2 -> FullDuplexIndicator.CAPTURING_COMPLEMENTARY
            hasConfirmedUtterance -> FullDuplexIndicator.QUEUED_COMPLEMENTARY
            else -> FullDuplexIndicator.INACTIVE
        }
        val totalSamples = bufferedFrames.sumOf { it.size }
        val needsBiometric = biometricRequired &&
            biometricVerdict == BiometricVerdict.PENDING &&
            totalSamples >= MIN_BIOMETRIC_SAMPLES
        return QueueSnapshot(
            indicator = indicator,
            activeSpeechNow = isSpeechActiveNow,
            hasConfirmedUtterance = hasConfirmedUtterance,
            bufferedFrameCount = bufferedFrames.size,
            confirmedSpeechMs = voicedMs,
            needsBiometricCheck = needsBiometric
        )
    }

    companion object {
        const val FRAME_DURATION_MS = 60L
        const val DEFAULT_MIN_COMPLEMENTARY_SPEECH_MS = 420L
        const val MAX_BUFFERED_FRAMES = 250 // 15 seconds @ 60ms/frame
        const val MIN_LIVE_HANDOFF_FRAMES = 3 // ~180ms ongoing speech when TTS drains
        const val MIN_BIOMETRIC_SAMPLES = 9600 // 0.6s @ 16kHz
        const val MAX_BIOMETRIC_SAMPLES = 22400 // 1.4s @ 16kHz
    }
}
