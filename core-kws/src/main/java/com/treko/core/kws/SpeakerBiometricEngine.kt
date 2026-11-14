package com.treko.core.kws

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.util.TrekoLogBuffer
import kotlin.system.measureTimeMillis

/**
 * Motor de biometría vocal en dispositivo usando Sherpa-ONNX SpeakerEmbeddingExtractor (CAM++).
 * Extrae embeddings de 192 dimensiones independientes del idioma/texto para validar la voz real del usuario.
 */
class SpeakerBiometricEngine(private val context: Context) {

    data class VerificationResult(
        val similarity: Float,
        val matched: Boolean,
        val inferenceMs: Long,
        val isAvailable: Boolean
    )

    data class BiometricDiagnostics(
        val isInitialized: Boolean,
        val isEnrolled: Boolean,
        val isGateEnabled: Boolean,
        val modelId: String,
        val embeddingDim: Int,
        val threshold: Float,
        val lastSimilarity: Float?,
        val lastMatched: Boolean?,
        val verifiedTurnsCount: Long,
        val rejectedTurnsCount: Long
    )

    companion object {
        private const val TAG = "TrekoBiometric"
        const val MODEL_ASSET_PATH = "models/speaker/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val SAMPLE_RATE = 16000
    }

    private val lock = Any()
    private var extractor: SpeakerEmbeddingExtractor? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var embeddingDim: Int = 0
        private set

    @Volatile
    var enrolledProfile: SpeakerBiometricProfile? = null
        private set

    @Volatile
    var isGateEnabled: Boolean = false

    @Volatile
    var threshold: Float = TrekoPreferences.DEFAULT_BIOMETRIC_THRESHOLD

    @Volatile
    var lastSimilarity: Float? = null
        private set

    @Volatile
    var lastMatched: Boolean? = null
        private set

    @Volatile
    private var verifiedTurnsCount: Long = 0L

    @Volatile
    private var rejectedTurnsCount: Long = 0L

    fun initialize(): Boolean {
        synchronized(lock) {
            if (isInitialized && extractor != null) return true
            return try {
                val config = SpeakerEmbeddingExtractorConfig(
                    model = MODEL_ASSET_PATH,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val instance = SpeakerEmbeddingExtractor(context.assets, config)
                val dim = instance.dim()
                if (dim <= 0) {
                    instance.release()
                    TrekoLogBuffer.e(TAG, "SpeakerEmbeddingExtractor returned invalid dim=$dim.")
                    return false
                }
                extractor = instance
                embeddingDim = dim
                isInitialized = true
                TrekoLogBuffer.i(TAG, "SpeakerEmbeddingExtractor CAM++ initialized (dim=$dim).")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeakerBiometricEngine", e)
                TrekoLogBuffer.e(TAG, "Failed to initialize SpeakerEmbeddingExtractor", e)
                isInitialized = false
                false
            }
        }
    }

    fun loadFromPreferences(prefs: TrekoPreferences) {
        val parsed = SpeakerBiometricProfile.fromJson(prefs.biometricVoicePrintJson)
        enrolledProfile = parsed
        threshold = prefs.biometricSpeakerThreshold.coerceIn(0.35f, 0.85f)
        isGateEnabled = prefs.biometricSpeakerGateEnabled && parsed != null
    }

    fun isVerificationActive(): Boolean {
        return isGateEnabled && enrolledProfile != null && isInitialized
    }

    fun extractEmbedding(samples: ShortArray, count: Int = samples.size): FloatArray? {
        if (count < 1600 || samples.isEmpty()) return null
        val safeCount = minOf(count, samples.size)

        synchronized(lock) {
            val active = extractor ?: return null
            val stream = active.createStream()
            return try {
                val floatSamples = FloatArray(safeCount) { i -> samples[i] / 32768.0f }
                stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                stream.inputFinished()
                if (!active.isReady(stream)) {
                    null
                } else {
                    val raw = active.compute(stream)
                    if (raw.isEmpty()) null else SpeakerBiometricProfile.l2Normalize(raw)
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractEmbedding error: ${e.message}")
                null
            } finally {
                try {
                    stream.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun verifyPcm(
        samples: ShortArray,
        count: Int = samples.size,
        profileOverride: SpeakerBiometricProfile? = enrolledProfile,
        thresholdOverride: Float = threshold,
        recordTurnStats: Boolean = true
    ): VerificationResult {
        val profile = profileOverride
            ?: return VerificationResult(similarity = 0f, matched = true, inferenceMs = 0L, isAvailable = false)
        if (!isInitialized && !initialize()) {
            return VerificationResult(similarity = 0f, matched = true, inferenceMs = 0L, isAvailable = false)
        }

        var embedding: FloatArray? = null
        val elapsedMs = measureTimeMillis {
            embedding = extractEmbedding(samples, count)
        }
        val vec = embedding
            ?: return VerificationResult(similarity = 0f, matched = false, inferenceMs = elapsedMs, isAvailable = true)

        val sim = profile.score(vec)
        val matched = sim >= thresholdOverride
        lastSimilarity = sim
        lastMatched = matched
        if (recordTurnStats) {
            if (matched) verifiedTurnsCount++ else rejectedTurnsCount++
        }

        return VerificationResult(
            similarity = sim,
            matched = matched,
            inferenceMs = elapsedMs,
            isAvailable = true
        )
    }

    fun getDiagnostics(): BiometricDiagnostics {
        return BiometricDiagnostics(
            isInitialized = isInitialized,
            isEnrolled = enrolledProfile != null,
            isGateEnabled = isGateEnabled,
            modelId = enrolledProfile?.modelName ?: SpeakerBiometricProfile.DEFAULT_MODEL_ID,
            embeddingDim = embeddingDim,
            threshold = threshold,
            lastSimilarity = lastSimilarity,
            lastMatched = lastMatched,
            verifiedTurnsCount = verifiedTurnsCount,
            rejectedTurnsCount = rejectedTurnsCount
        )
    }

    fun release() {
        synchronized(lock) {
            isInitialized = false
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            extractor = null
        }
    }
}
