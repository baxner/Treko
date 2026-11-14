package com.treko.core.kws

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gestor de enrolamiento biométrico vocal independiente del texto (3 frases naturales).
 */
class BiometricVoiceTrainer(
    private val targetTakes: Int = DEFAULT_TARGET_TAKES
) {
    enum class EnrollmentStage {
        IDLE,
        RECORDING_TAKE,
        PROCESSING,
        READY,
        VERIFYING_LIVE
    }

    enum class TakeQuality {
        OK,
        TOO_QUIET,
        TOO_NOISY,
        CLIPPED,
        TOO_SHORT
    }

    data class TakeAnalysis(
        val quality: TakeQuality,
        val activeRms: Float,
        val snrDb: Float,
        val activeDurationMs: Int
    )

    data class UiState(
        val stage: EnrollmentStage = EnrollmentStage.IDLE,
        val recordedTakes: Int = 0,
        val targetTakes: Int = DEFAULT_TARGET_TAKES,
        val lastTakeQuality: TakeQuality? = null,
        val lastTakeSnrDb: Float = 0f,
        val lastTakeDurationMs: Int = 0,
        val statusError: String? = null,
        val profile: SpeakerBiometricProfile? = null,
        val recommendedThreshold: Float = 0.56f
    )

    private val lock = Any()
    private val ringBuffer = ShortArray(CAPTURE_WINDOW_SAMPLES)
    private var ringWriteIdx = 0
    private var ringFilled = 0

    private val acceptedTakesPcm = mutableListOf<ShortArray>()
    private val acceptedEmbeddings = mutableListOf<FloatArray>()

    private val _state = MutableStateFlow(UiState(targetTakes = targetTakes))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startSession() {
        synchronized(lock) {
            acceptedTakesPcm.clear()
            acceptedEmbeddings.clear()
            ringWriteIdx = 0
            ringFilled = 0
            _state.value = UiState(stage = EnrollmentStage.RECORDING_TAKE, targetTakes = targetTakes)
        }
    }

    fun onPcmFrame(samples: ShortArray, count: Int) {
        if (count <= 0 || samples.isEmpty()) return
        val safeCount = minOf(count, samples.size)
        synchronized(lock) {
            for (i in 0 until safeCount) {
                ringBuffer[ringWriteIdx] = samples[i]
                ringWriteIdx = (ringWriteIdx + 1) % ringBuffer.size
                if (ringFilled < ringBuffer.size) ringFilled++
            }
        }
    }

    fun snapshotRecentPcm(maxSamples: Int = TAKE_WINDOW_SAMPLES): ShortArray {
        synchronized(lock) {
            val count = minOf(maxSamples, ringFilled)
            if (count <= 0) return ShortArray(0)
            val result = ShortArray(count)
            val start = (ringWriteIdx - count + ringBuffer.size) % ringBuffer.size
            for (i in 0 until count) {
                result[i] = ringBuffer[(start + i) % ringBuffer.size]
            }
            return result
        }
    }

    fun analyzeTake(
        rawSamples: ShortArray,
        noiseFloorRms: Float = 0.005f,
        sampleRate: Int = SAMPLE_RATE
    ): TakeAnalysis {
        if (rawSamples.isEmpty()) {
            return TakeAnalysis(TakeQuality.TOO_SHORT, 0f, 0f, 0)
        }
        val effectiveFloor = max(noiseFloorRms, 0.001f)
        if (effectiveFloor > 0.07f) {
            return TakeAnalysis(TakeQuality.TOO_NOISY, effectiveFloor, 0f, 0)
        }

        val frameSize = 320 // 20ms @ 16kHz
        val gateThresholdRms = max(0.008f, effectiveFloor * 1.6f)
        var activeFrames = 0
        var hangover = 0
        var activeEnergySum = 0.0
        var activeCount = 0
        var clippedCount = 0

        var offset = 0
        while (offset + frameSize <= rawSamples.size) {
            var eSum = 0.0
            var zeroCrossings = 0
            var prevSign = 0
            var frameClip = 0
            for (i in 0 until frameSize) {
                val s = rawSamples[offset + i]
                val v = s / 32768.0
                eSum += v * v
                val sign = if (s >= 0) 1 else -1
                if (prevSign != 0 && sign != prevSign) zeroCrossings++
                prevSign = sign
                if (abs(s.toInt()) >= 32400) frameClip++
            }
            val rms = sqrt(eSum / frameSize).toFloat()
            val zcr = zeroCrossings.toFloat() / (frameSize - 1)
            val framePlausible = rms > gateThresholdRms && zcr in 0.012f..0.58f
            val isActive = if (framePlausible) {
                hangover = 3
                true
            } else if (hangover > 0) {
                hangover--
                true
            } else {
                false
            }
            if (isActive) {
                activeFrames++
                activeEnergySum += eSum
                activeCount += frameSize
                clippedCount += frameClip
            }
            offset += frameSize
        }

        val activeDurationMs = activeFrames * 20
        val activeRms = if (activeCount > 0) sqrt(activeEnergySum / activeCount).toFloat() else 0f
        val snrDb = (20f * log10(max(activeRms, 1e-6f) / effectiveFloor)).coerceIn(-10f, 60f)

        val quality = when {
            activeDurationMs < MIN_ACTIVE_SPEECH_MS -> TakeQuality.TOO_SHORT
            activeCount > 0 && clippedCount > max(12, (activeCount * 0.03f).toInt()) -> TakeQuality.CLIPPED
            snrDb < 6.5f -> TakeQuality.TOO_QUIET
            else -> TakeQuality.OK
        }

        return TakeAnalysis(
            quality = quality,
            activeRms = activeRms,
            snrDb = snrDb,
            activeDurationMs = activeDurationMs
        )
    }

    /**
     * Extrae muestras activas continuas (recortando silencios largos inicial/final) para maximizar la pureza del embedding.
     */
    fun trimSilentEdges(samples: ShortArray, noiseFloorRms: Float = 0.005f): ShortArray {
        val gate = max(250, (noiseFloorRms * 2.2f * 32768f).toInt())
        var first = 0
        while (first < samples.size && abs(samples[first].toInt()) < gate) first++
        var last = samples.size - 1
        while (last > first && abs(samples[last].toInt()) < gate) last--
        val start = (first - 1600).coerceAtLeast(0)
        val end = (last + 1600).coerceAtMost(samples.size - 1)
        return if (end <= start) samples else samples.copyOfRange(start, end + 1)
    }

    fun submitTake(
        rawSamples: ShortArray,
        noiseFloorRms: Float,
        denoiser: NeuralSpeechDenoiser?,
        engine: SpeakerBiometricEngine
    ): TakeAnalysis {
        val cleanPcm = if (denoiser != null && denoiser.isInitialized && denoiser.isEnabled) {
            val enhanced = denoiser.processPcm(rawSamples, rawSamples.size)
            val tail = denoiser.flush()
            denoiser.reset()
            enhanced + tail
        } else {
            rawSamples
        }

        val analysis = analyzeTake(cleanPcm, noiseFloorRms)
        if (analysis.quality != TakeQuality.OK) {
            _state.value = _state.value.copy(
                lastTakeQuality = analysis.quality,
                lastTakeSnrDb = analysis.snrDb,
                lastTakeDurationMs = analysis.activeDurationMs,
                statusError = null
            )
            return analysis
        }

        if (!engine.isInitialized && !engine.initialize()) {
            _state.value = _state.value.copy(statusError = "Speaker model unavailable")
            return analysis
        }

        val trimmed = trimSilentEdges(cleanPcm, noiseFloorRms)
        val emb = engine.extractEmbedding(trimmed, trimmed.size)
        if (emb == null) {
            _state.value = _state.value.copy(
                lastTakeQuality = TakeQuality.TOO_SHORT,
                lastTakeSnrDb = analysis.snrDb,
                lastTakeDurationMs = analysis.activeDurationMs
            )
            return analysis.copy(quality = TakeQuality.TOO_SHORT)
        }

        synchronized(lock) {
            acceptedTakesPcm.add(trimmed)
            acceptedEmbeddings.add(emb)
        }

        val takesNow = acceptedEmbeddings.size
        if (takesNow < targetTakes) {
            _state.value = _state.value.copy(
                stage = EnrollmentStage.RECORDING_TAKE,
                recordedTakes = takesNow,
                lastTakeQuality = TakeQuality.OK,
                lastTakeSnrDb = analysis.snrDb,
                lastTakeDurationMs = analysis.activeDurationMs,
                statusError = null
            )
        } else {
            val profile = SpeakerBiometricProfile.fromEmbeddings(acceptedEmbeddings)
            _state.value = _state.value.copy(
                stage = EnrollmentStage.READY,
                recordedTakes = takesNow,
                lastTakeQuality = TakeQuality.OK,
                lastTakeSnrDb = analysis.snrDb,
                lastTakeDurationMs = analysis.activeDurationMs,
                statusError = null,
                profile = profile,
                recommendedThreshold = profile?.recommendedThreshold ?: 0.56f
            )
        }
        return analysis
    }

    fun enterLiveVerification() {
        _state.value = _state.value.copy(stage = EnrollmentStage.VERIFYING_LIVE)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val DEFAULT_TARGET_TAKES = 3
        const val TAKE_DURATION_MS = 3200
        const val TAKE_WINDOW_SAMPLES = (SAMPLE_RATE * TAKE_DURATION_MS) / 1000 // 51,200 samples
        const val CAPTURE_WINDOW_SAMPLES = SAMPLE_RATE * 5 // 5s ring buffer
        const val MIN_ACTIVE_SPEECH_MS = 950
    }
}
