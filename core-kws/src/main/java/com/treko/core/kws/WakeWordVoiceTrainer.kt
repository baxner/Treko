package com.treko.core.kws

import com.treko.core.common.data.TrekoPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Guided Wake-Word Voice Training, Multi-SNR Augmentation & Empirical Calibration Engine.
 *
 * Workflow:
 * 1. Noise floor measurement (~1.5s ambient silence) -> `finishNoiseFloorMeasurement()`
 * 2. Multi-sample enrollment (3–5 spoken wake-word takes) -> `finishSampleCapture()`
 *    Each take is validated for clipping, SNR, background noise, and voiced duration.
 * 3. Multi-SNR + gain augmentation + empirical KWS replay sweep (or analytical SNR calibration)
 *    to compute optimal `(boost, threshold)` and build a personalized `WakeWordVoicePrint`.
 * 4. Persistence to `TrekoPreferences` and live verification support.
 */
class WakeWordVoiceTrainer(
    private val targetSamples: Int = DEFAULT_TARGET_SAMPLES
) {
    enum class TrainingStage {
        IDLE,
        MEASURING_NOISE_FLOOR,
        RECORDING_SAMPLE,
        CALIBRATING,
        VERIFYING_LIVE,
        COMPLETED,
        FAILED
    }

    enum class SampleQuality {
        OK,
        TOO_QUIET,
        TOO_NOISY,
        CLIPPED,
        TOO_SHORT,
        TOO_LONG
    }

    data class SampleAnalysis(
        val quality: SampleQuality,
        val speechRms: Float,
        val peakAmplitude: Int,
        val snrDb: Float,
        val voicedDurationMs: Int,
        val clippedRatio: Float
    )

    data class CalibrationResult(
        val keyword: String,
        val recommendedBoost: Float,
        val recommendedThreshold: Float,
        val noiseFloorRms: Float,
        val meanSpeechRms: Float,
        val meanSnrDb: Float,
        val augmentedRecall: Float,
        val falseTriggerCount: Int,
        val voicePrint: WakeWordVoicePrint?,
        val acousticMatcher: WakeWordAcousticMatcher? = null
    )

    data class TrainerUiState(
        val stage: TrainingStage = TrainingStage.IDLE,
        val keyword: String = "Treko",
        val currentSampleIndex: Int = 0,
        val targetSampleCount: Int = DEFAULT_TARGET_SAMPLES,
        val progressFraction: Float = 0f,
        val noiseFloorRms: Float = 0.005f,
        val liveRms: Float = 0f,
        val lastSampleAnalysis: SampleAnalysis? = null,
        val calibrationResult: CalibrationResult? = null,
        val errorMessage: String? = null
    )

    companion object {
        const val SAMPLE_RATE = 16000
        const val DEFAULT_TARGET_SAMPLES = 3
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 4 // 4s safety cap
        private const val MIN_VOICED_DURATION_MS = 160
        private const val MAX_VOICED_DURATION_MS = 2400
        private const val MIN_SNR_DB = 5.5f
        private const val MAX_CLIPPED_RATIO = 0.025f
        private const val MAX_NOISE_FLOOR_RMS = 0.06f
    }

    private val lock = Any()
    private val _state = MutableStateFlow(TrainerUiState(targetSampleCount = targetSamples))
    val state: StateFlow<TrainerUiState> = _state.asStateFlow()

    private val currentCaptureBuffer = mutableListOf<Short>()
    private var capturedNoiseBuffer = ShortArray(0)
    private val acceptedSamples = mutableListOf<ShortArray>()
    private val acceptedAnalyses = mutableListOf<SampleAnalysis>()

    fun startSession(keyword: String) {
        synchronized(lock) {
            currentCaptureBuffer.clear()
            capturedNoiseBuffer = ShortArray(0)
            acceptedSamples.clear()
            acceptedAnalyses.clear()
            _state.value = TrainerUiState(
                stage = TrainingStage.MEASURING_NOISE_FLOOR,
                keyword = keyword.trim().ifEmpty { "Treko" },
                targetSampleCount = targetSamples
            )
        }
    }

    fun beginNoiseFloorCapture() {
        synchronized(lock) {
            currentCaptureBuffer.clear()
            _state.value = _state.value.copy(
                stage = TrainingStage.MEASURING_NOISE_FLOOR,
                progressFraction = 0f,
                errorMessage = null
            )
        }
    }

    fun beginSampleCapture() {
        synchronized(lock) {
            currentCaptureBuffer.clear()
            _state.value = _state.value.copy(
                stage = TrainingStage.RECORDING_SAMPLE,
                progressFraction = 0f,
                errorMessage = null
            )
        }
    }

    /**
     * Feeds live 16 kHz mono PCM frames during MEASURING_NOISE_FLOOR or RECORDING_SAMPLE.
     */
    fun onPcmFrame(samples: ShortArray, read: Int = samples.size) {
        if (read <= 0 || samples.isEmpty()) return
        val safeRead = minOf(read, samples.size)

        synchronized(lock) {
            val stage = _state.value.stage
            if (stage != TrainingStage.MEASURING_NOISE_FLOOR && stage != TrainingStage.RECORDING_SAMPLE) {
                return
            }

            var sumSq = 0.0
            for (i in 0 until safeRead) {
                val s = samples[i]
                if (currentCaptureBuffer.size < MAX_BUFFER_SAMPLES) {
                    currentCaptureBuffer.add(s)
                }
                val f = s / 32768.0
                sumSq += f * f
            }
            val rms = sqrt(sumSq / safeRead).toFloat()
            val expectedDurationSamples = if (stage == TrainingStage.MEASURING_NOISE_FLOOR) {
                (SAMPLE_RATE * 1.5f).toInt()
            } else {
                (SAMPLE_RATE * 2.0f).toInt()
            }
            val progress = (currentCaptureBuffer.size.toFloat() / expectedDurationSamples).coerceIn(0f, 1f)
            _state.value = _state.value.copy(liveRms = rms, progressFraction = progress)
        }
    }

    fun finishNoiseFloorMeasurement(): Float {
        synchronized(lock) {
            val pcm = currentCaptureBuffer.toShortArray()
            currentCaptureBuffer.clear()
            capturedNoiseBuffer = pcm
            val floor = computeRms(pcm).coerceIn(0.001f, 0.20f)
            val isTooNoisy = floor > MAX_NOISE_FLOOR_RMS
            _state.value = _state.value.copy(
                noiseFloorRms = floor,
                stage = if (isTooNoisy) TrainingStage.FAILED else TrainingStage.RECORDING_SAMPLE,
                progressFraction = 0f,
                errorMessage = if (isTooNoisy) {
                    "Ambient noise is too loud for clean calibration."
                } else null
            )
            return floor
        }
    }

    fun finishSampleCapture(): SampleAnalysis {
        synchronized(lock) {
            val pcm = currentCaptureBuffer.toShortArray()
            currentCaptureBuffer.clear()
            val floor = _state.value.noiseFloorRms
            val analysis = analyzeSample(pcm, floor)

            if (analysis.quality == SampleQuality.OK) {
                acceptedSamples.add(pcm)
                acceptedAnalyses.add(analysis)
            }

            val nextIndex = acceptedSamples.size
            val nextStage = if (nextIndex >= targetSamples) {
                TrainingStage.CALIBRATING
            } else {
                TrainingStage.RECORDING_SAMPLE
            }

            _state.value = _state.value.copy(
                currentSampleIndex = nextIndex,
                stage = nextStage,
                lastSampleAnalysis = analysis,
                progressFraction = 0f,
                errorMessage = if (analysis.quality != SampleQuality.OK) analysis.quality.name else null
            )
            return analysis
        }
    }

    fun runCalibration(
        detector: TrekoWakeWordDetector? = null,
        prefs: TrekoPreferences? = null
    ): CalibrationResult {
        val snapshotKeyword: String
        val snapshotFloor: Float
        val snapshotNoisePcm: ShortArray
        val snapshotSamples: List<ShortArray>

        synchronized(lock) {
            snapshotKeyword = _state.value.keyword
            snapshotFloor = _state.value.noiseFloorRms
            snapshotNoisePcm = capturedNoiseBuffer
            snapshotSamples = acceptedSamples.toList()
            _state.value = _state.value.copy(stage = TrainingStage.CALIBRATING)
        }

        val evaluator: ((List<ShortArray>, String, Float, Float) -> Int)? =
            if (detector != null && detector.isInitialized) {
                { list, kw, b, t -> detector.evaluateSamples(list, kw, b, t) }
            } else {
                null
            }

        val result = calibrate(
            keyword = snapshotKeyword,
            noiseFloorRms = snapshotFloor,
            recordedSamples = snapshotSamples,
            noiseSample = snapshotNoisePcm,
            evaluator = evaluator
        )

        if (prefs != null) {
            saveToPreferences(prefs, result)
        }
        if (detector != null && detector.isInitialized) {
            detector.setNoiseFloorRms(result.noiseFloorRms)
            detector.setVoicePrint(result.voicePrint, strictness = 0f)
            detector.setAcousticMatcher(result.acousticMatcher)
            detector.updateKeywords(result.keyword, result.recommendedBoost, result.recommendedThreshold)
        }

        synchronized(lock) {
            _state.value = _state.value.copy(
                stage = TrainingStage.VERIFYING_LIVE,
                calibrationResult = result
            )
        }
        return result
    }

    fun markCompleted() {
        synchronized(lock) {
            _state.value = _state.value.copy(stage = TrainingStage.COMPLETED)
        }
    }

    fun reset() {
        synchronized(lock) {
            currentCaptureBuffer.clear()
            capturedNoiseBuffer = ShortArray(0)
            acceptedSamples.clear()
            acceptedAnalyses.clear()
            _state.value = TrainerUiState(targetSampleCount = targetSamples)
        }
    }

    /**
     * Analyzes a single spoken wake-word PCM recording against the ambient noise floor.
     */
    fun analyzeSample(samples: ShortArray, noiseFloorRms: Float): SampleAnalysis {
        if (samples.isEmpty()) {
            return SampleAnalysis(SampleQuality.TOO_SHORT, 0f, 0, 0f, 0, 0f)
        }
        if (noiseFloorRms > MAX_NOISE_FLOOR_RMS) {
            return SampleAnalysis(SampleQuality.TOO_NOISY, computeRms(samples), peakAmp(samples), 0f, 0, 0f)
        }

        var peak = 0
        var clippedCount = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            if (a >= 32000) clippedCount++
        }
        val clippedRatio = clippedCount.toFloat() / samples.size

        // Measure voiced segments (20ms frames above adaptive gate)
        val frameSize = 320 // 20ms at 16kHz
        val speechGate = max(0.010f, noiseFloorRms * 2.0f)
        var voicedFrames = 0
        var voicedEnergySum = 0.0
        var frameStart = 0
        while (frameStart + frameSize <= samples.size) {
            var frameSq = 0.0
            for (i in frameStart until frameStart + frameSize) {
                val f = samples[i] / 32768.0
                frameSq += f * f
            }
            val frameRms = sqrt(frameSq / frameSize).toFloat()
            if (frameRms > speechGate) {
                voicedFrames++
                voicedEnergySum += frameRms * frameRms
            }
            frameStart += frameSize
        }

        val totalDurationMs = (samples.size * 1000) / SAMPLE_RATE
        val voicedDurationMs = voicedFrames * 20
        val speechRms = if (voicedFrames > 0) sqrt(voicedEnergySum / voicedFrames).toFloat() else computeRms(samples)
        val snrDb = (20f * log10(max(speechRms, 1e-6f) / max(noiseFloorRms, 1e-5f))).coerceIn(-20f, 60f)

        val quality = when {
            clippedRatio > MAX_CLIPPED_RATIO -> SampleQuality.CLIPPED
            totalDurationMs < MIN_VOICED_DURATION_MS -> SampleQuality.TOO_SHORT
            snrDb < MIN_SNR_DB -> SampleQuality.TOO_QUIET
            voicedDurationMs < MIN_VOICED_DURATION_MS -> SampleQuality.TOO_SHORT
            voicedDurationMs > MAX_VOICED_DURATION_MS -> SampleQuality.TOO_LONG
            else -> SampleQuality.OK
        }

        return SampleAnalysis(
            quality = quality,
            speechRms = speechRms,
            peakAmplitude = peak,
            snrDb = snrDb,
            voicedDurationMs = voicedDurationMs,
            clippedRatio = clippedRatio
        )
    }

    /**
     * Generates multi-SNR and multi-gain augmented copies of enrolled recordings so
     * calibrated parameters generalize across distance, whisper/loud voice, and ambient noise.
     */
    fun buildAugmentedDataset(
        recordedSamples: List<ShortArray>,
        noiseFloorRms: Float
    ): List<ShortArray> {
        val augmented = mutableListOf<ShortArray>()
        val rng = Random(42L)
        val noiseAmp = (noiseFloorRms * 1.4f).coerceIn(0.004f, 0.04f) * 32768f

        for (raw in recordedSamples) {
            if (raw.isEmpty()) continue
            augmented.add(raw)
            // Soft / far-field gain variant (0.55x)
            augmented.add(scalePcm(raw, 0.55f))
            // Loud / close gain variant (1.35x)
            augmented.add(scalePcm(raw, 1.35f))
            // Noise-augmented (~12-15dB SNR) variant
            val noisy = ShortArray(raw.size) { i ->
                val n = (rng.nextGaussian() * noiseAmp).toInt()
                (raw[i].toInt() + n).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            augmented.add(noisy)
        }
        return augmented
    }

    /**
     * Calibrates optimal `(boost, threshold)`, builds a `WakeWordVoicePrint`, and trains a
     * language-agnostic `WakeWordAcousticMatcher` (Log-Mel + Sakoe-Chiba DTW).
     *
     * When `evaluator` is provided (e.g. `TrekoWakeWordDetector.evaluateSamples`), runs a fast
     * 8-point Zipformer schedule on the raw enrolled takes first (< 4s instead of >100s grid search),
     * preserves the safe Spanish baseline `(3.5, 0.04)` when Zipformer gets 0 hits (never downgrading
     * sensitivity to `(2.6, 0.12)`), and computes combined `(Zipformer || AcousticDTW)` recall over
     * the multi-SNR augmented dataset.
     */
    fun calibrate(
        keyword: String,
        noiseFloorRms: Float,
        recordedSamples: List<ShortArray>,
        noiseSample: ShortArray = ShortArray(0),
        evaluator: ((List<ShortArray>, String, Float, Float) -> Int)? = null
    ): CalibrationResult {
        val analyses = recordedSamples.map { analyzeSample(it, noiseFloorRms) }
        val meanSpeechRms = if (analyses.isNotEmpty()) {
            analyses.map { it.speechRms }.average().toFloat()
        } else {
            0.05f
        }
        val meanSnrDb = if (analyses.isNotEmpty()) {
            analyses.map { it.snrDb }.average().toFloat()
        } else {
            15f
        }

        val voicePrint = WakeWordVoicePrint.fromSamples(keyword, recordedSamples, meanSpeechRms, SAMPLE_RATE)
        val acousticMatcher = WakeWordAcousticMatcher.fromEnrolledSamples(
            keyword = keyword,
            samplesList = recordedSamples,
            sampleRate = SAMPLE_RATE,
            noiseFloorRms = noiseFloorRms
        )
        val augmented = buildAugmentedDataset(recordedSamples, noiseFloorRms)
        val noiseList = if (noiseSample.size >= 3200) listOf(noiseSample) else emptyList()

        if (evaluator == null || recordedSamples.isEmpty() || augmented.isEmpty()) {
            val analyticalBoost = when {
                meanSpeechRms < 0.025f -> 3.8f
                meanSpeechRms < 0.055f -> 3.5f
                else -> 3.0f
            }.coerceIn(1.5f, 4.8f)

            val analyticalThreshold = when {
                noiseFloorRms > 0.025f || meanSnrDb < 10f -> 0.07f
                noiseFloorRms > 0.012f || meanSnrDb < 16f -> 0.05f
                else -> 0.04f
            }.coerceIn(0.02f, 0.16f)

            val dtwRecall = if (acousticMatcher != null && augmented.isNotEmpty()) {
                augmented.count { acousticMatcher.matches(it, noiseFloorRms) }.toFloat() / augmented.size
            } else {
                1.0f
            }
            val dtwFalse = if (acousticMatcher != null && noiseList.isNotEmpty() && acousticMatcher.matches(noiseSample, noiseFloorRms)) {
                1
            } else {
                0
            }

            return CalibrationResult(
                keyword = keyword,
                recommendedBoost = analyticalBoost,
                recommendedThreshold = analyticalThreshold,
                noiseFloorRms = noiseFloorRms,
                meanSpeechRms = meanSpeechRms,
                meanSnrDb = meanSnrDb,
                augmentedRecall = dtwRecall,
                falseTriggerCount = dtwFalse,
                voicePrint = voicePrint,
                acousticMatcher = acousticMatcher
            )
        }

        // Fast 8-point Zipformer schedule over raw enrolled recordings first
        val zipformerSchedule = listOf(
            3.5f to 0.04f,
            3.8f to 0.03f,
            3.6f to 0.05f,
            3.4f to 0.06f,
            3.2f to 0.05f,
            3.2f to 0.07f,
            2.8f to 0.08f,
            3.0f to 0.09f
        )

        var bestBoost = 3.5f
        var bestThreshold = 0.04f
        var bestScore = Double.NEGATIVE_INFINITY
        var bestFalseTriggers = 0
        var maxRawHits = 0

        for ((b, t) in zipformerSchedule) {
            val rawHits = evaluator(recordedSamples, keyword, b, t)
            if (rawHits > maxRawHits) {
                maxRawHits = rawHits
            }
            if (rawHits <= 0) {
                // Do not score zero-hit configurations; prevents poisoning to insensitive thresholds
                continue
            }

            val recall = rawHits.toFloat() / recordedSamples.size
            val kwsFalse = if (noiseList.isNotEmpty()) evaluator(noiseList, keyword, b, t) else 0
            val score = (recall * 100.0) - (kwsFalse * 80.0) + (t * 20.0) - (b * 1.5)
            if (score > bestScore) {
                bestScore = score
                bestBoost = b
                bestThreshold = t
                bestFalseTriggers = kwsFalse
            }
        }

        if (maxRawHits == 0) {
            // Keep safe Spanish baseline (3.5, 0.04) so uncalibrated/English-accented fallback stays responsive
            bestBoost = 3.5f
            bestThreshold = 0.04f
            bestFalseTriggers = 0
        }

        // Combined (Zipformer || AcousticDTW) recall across the multi-SNR augmented dataset
        val combinedHits: Int = if (acousticMatcher != null) {
            val dtwMisses = augmented.filterNot { acousticMatcher.matches(it, noiseFloorRms) }
            val dtwHitCount = augmented.size - dtwMisses.size
            val kwsHitOnMisses = if (dtwMisses.isNotEmpty() && maxRawHits > 0) {
                evaluator(dtwMisses, keyword, bestBoost, bestThreshold)
            } else {
                0
            }
            dtwHitCount + kwsHitOnMisses
        } else {
            if (maxRawHits > 0) evaluator(augmented, keyword, bestBoost, bestThreshold) else 0
        }

        if (acousticMatcher != null && noiseList.isNotEmpty() && acousticMatcher.matches(noiseSample, noiseFloorRms)) {
            bestFalseTriggers++
        }

        val combinedRecall = (combinedHits.toFloat() / augmented.size).coerceIn(0f, 1f)

        return CalibrationResult(
            keyword = keyword,
            recommendedBoost = bestBoost,
            recommendedThreshold = bestThreshold,
            noiseFloorRms = noiseFloorRms,
            meanSpeechRms = meanSpeechRms,
            meanSnrDb = meanSnrDb,
            augmentedRecall = combinedRecall,
            falseTriggerCount = bestFalseTriggers,
            voicePrint = voicePrint,
            acousticMatcher = acousticMatcher
        )
    }

    fun saveToPreferences(prefs: TrekoPreferences, result: CalibrationResult) {
        prefs.customWakeWord = result.keyword
        prefs.wakeWordBoost = result.recommendedBoost
        prefs.wakeWordSensitivity = result.recommendedThreshold
        prefs.calibratedNoiseFloorRms = result.noiseFloorRms
        prefs.calibratedSpeechRms = result.meanSpeechRms
        prefs.calibratedSnrDb = result.meanSnrDb
        prefs.wakeWordVoicePrintJson = result.voicePrint?.toJson() ?: ""
        prefs.wakeWordAcousticTemplatesJson = result.acousticMatcher?.toJson() ?: ""
        prefs.wakeWordDtwThreshold = result.acousticMatcher?.threshold ?: WakeWordAcousticMatcher.DEFAULT_THRESHOLD
        prefs.isWakeWordCalibrated = true
    }

    private fun scalePcm(samples: ShortArray, factor: Float): ShortArray {
        return ShortArray(samples.size) { i ->
            (samples[i] * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val f = s / 32768.0
            sum += f * f
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun peakAmp(samples: ShortArray): Int {
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        return peak
    }
}
