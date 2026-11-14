package com.treko.core.kws

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Compact spectral-prosodic acoustic fingerprint for enrolled wake-word verification.
 * Runs 100% on-device with zero external model dependencies by combining:
 * - 12 log-band energy ratios across 125 Hz - 6000 Hz (Goertzel filterbank)
 * - 4 temporal syllable-envelope quartiles
 * - 4 prosodic features (mean ZCR, ZCR variance, voiced ratio, normalized duration)
 */
data class WakeWordVoicePrint(
    val keyword: String,
    val featureVector: FloatArray,
    val sampleCount: Int,
    val meanSpeechRms: Float,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun similarityTo(candidate: WakeWordVoicePrint): Float {
        return cosineSimilarity(featureVector, candidate.featureVector)
    }

    fun similarityToPcm(samples: ShortArray, sampleRate: Int = 16000): Float {
        val extracted = extractFeatureVector(samples, sampleRate) ?: return 0f
        return cosineSimilarity(featureVector, extracted)
    }

    fun toJson(): String {
        val escapedKw = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
        val feats = featureVector.joinToString(",") { it.toString() }
        return """{"keyword":"$escapedKw","features":[$feats],"sampleCount":$sampleCount,"meanSpeechRms":$meanSpeechRms,"createdAtMs":$createdAtMs}"""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WakeWordVoicePrint
        return keyword == other.keyword &&
            sampleCount == other.sampleCount &&
            featureVector.contentEquals(other.featureVector)
    }

    override fun hashCode(): Int {
        var result = keyword.hashCode()
        result = 31 * result + featureVector.contentHashCode()
        result = 31 * result + sampleCount
        return result
    }

    companion object {
        const val FEATURE_DIM = 20
        private val BAND_FREQUENCIES_HZ = floatArrayOf(
            150f, 250f, 380f, 540f, 750f, 1000f,
            1350f, 1800f, 2400f, 3200f, 4300f, 5600f
        )

        fun fromJson(raw: String): WakeWordVoicePrint? {
            if (raw.isBlank()) return null
            return try {
                val kwMatch = Regex(""""keyword"\s*:\s*"((?:\\.|[^"])*)"""").find(raw)
                val featMatch = Regex(""""features"\s*:\s*\[([^\]]+)]""").find(raw) ?: return null
                val countMatch = Regex(""""sampleCount"\s*:\s*(\d+)""").find(raw)
                val rmsMatch = Regex(""""meanSpeechRms"\s*:\s*([0-9eE.+\-]+)""").find(raw)
                val tsMatch = Regex(""""createdAtMs"\s*:\s*(\d+)""").find(raw)

                val parts = featMatch.groupValues[1].split(",").map { it.trim().toFloat() }
                if (parts.size != FEATURE_DIM) return null
                val kw = (kwMatch?.groupValues?.get(1) ?: "Treko")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                WakeWordVoicePrint(
                    keyword = kw,
                    featureVector = parts.toFloatArray(),
                    sampleCount = countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                    meanSpeechRms = rmsMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.05f,
                    createdAtMs = tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                )
            } catch (_: Exception) {
                null
            }
        }

        fun fromSamples(
            keyword: String,
            samplesList: List<ShortArray>,
            meanSpeechRms: Float,
            sampleRate: Int = 16000
        ): WakeWordVoicePrint? {
            val vectors = samplesList.mapNotNull { extractFeatureVector(it, sampleRate) }
            if (vectors.isEmpty()) return null

            val mean = FloatArray(FEATURE_DIM)
            for (v in vectors) {
                for (i in 0 until FEATURE_DIM) {
                    mean[i] += v[i]
                }
            }
            for (i in 0 until FEATURE_DIM) {
                mean[i] /= vectors.size.toFloat()
            }
            normalizeInPlace(mean)
            return WakeWordVoicePrint(
                keyword = keyword,
                featureVector = mean,
                sampleCount = vectors.size,
                meanSpeechRms = meanSpeechRms
            )
        }

        fun extractFeatureVector(samples: ShortArray, sampleRate: Int = 16000): FloatArray? {
            if (samples.size < 640) return null
            val trimmed = trimSilence(samples)
            if (trimmed.size < 480) return null

            val vec = FloatArray(FEATURE_DIM)
            val frameSize = 320 // 20ms at 16kHz
            val hopSize = 160   // 10ms hop
            val bandEnergies = DoubleArray(BAND_FREQUENCIES_HZ.size)
            val frameRmsList = mutableListOf<Float>()
            val frameZcrList = mutableListOf<Float>()

            var frameCount = 0
            var start = 0
            while (start + frameSize <= trimmed.size) {
                var sumSq = 0.0
                var zc = 0
                var prev = trimmed[start]
                for (i in start until start + frameSize) {
                    val s = trimmed[i] / 32768.0
                    sumSq += s * s
                    if (i > start) {
                        val cur = trimmed[i]
                        if ((cur >= 0 && prev < 0) || (cur < 0 && prev >= 0)) zc++
                        prev = cur
                    }
                }
                val rms = sqrt(sumSq / frameSize).toFloat()
                frameRmsList.add(rms)
                frameZcrList.add(zc.toFloat() / (frameSize - 1))

                if (rms > 0.008f) {
                    for (b in BAND_FREQUENCIES_HZ.indices) {
                        bandEnergies[b] += goertzelPower(trimmed, start, frameSize, BAND_FREQUENCIES_HZ[b], sampleRate)
                    }
                    frameCount++
                }
                start += hopSize
            }

            if (frameCount == 0) return null

            // 12 log-spectral band energies (normalized across bands)
            var totalLog = 0.0
            val logBands = DoubleArray(BAND_FREQUENCIES_HZ.size)
            for (b in BAND_FREQUENCIES_HZ.indices) {
                logBands[b] = ln(1.0 + (bandEnergies[b] / frameCount) * 1000.0)
                totalLog += logBands[b]
            }
            val safeTotalLog = max(totalLog, 1e-6)
            for (b in BAND_FREQUENCIES_HZ.indices) {
                vec[b] = (logBands[b] / safeTotalLog).toFloat()
            }

            // 4 temporal envelope quartiles
            val qSize = max(1, frameRmsList.size / 4)
            var totalEnv = 1e-6f
            val quartiles = FloatArray(4)
            for (q in 0 until 4) {
                val from = q * qSize
                val to = if (q == 3) frameRmsList.size else minOf(frameRmsList.size, (q + 1) * qSize)
                var qSum = 0f
                for (idx in from until to) qSum += frameRmsList[idx]
                quartiles[q] = qSum / max(1, to - from)
                totalEnv += quartiles[q]
            }
            for (q in 0 until 4) {
                vec[12 + q] = quartiles[q] / totalEnv
            }

            // 4 prosodic descriptors
            val meanZcr = frameZcrList.average().toFloat()
            var zcrVar = 0f
            for (z in frameZcrList) {
                val d = z - meanZcr
                zcrVar += d * d
            }
            val zcrStd = sqrt(zcrVar / max(1, frameZcrList.size))
            val voicedRatio = frameCount.toFloat() / max(1, frameRmsList.size)
            val normDuration = (trimmed.size.toFloat() / sampleRate / 1.5f).coerceIn(0f, 1.5f)

            vec[16] = meanZcr * 2.0f
            vec[17] = zcrStd * 4.0f
            vec[18] = voicedRatio
            vec[19] = normDuration

            normalizeInPlace(vec)
            return vec
        }

        private fun trimSilence(samples: ShortArray): ShortArray {
            var peak = 0
            for (s in samples) {
                val a = kotlin.math.abs(s.toInt())
                if (a > peak) peak = a
            }
            if (peak < 200) return ShortArray(0)
            val gate = max(150, (peak * 0.08f).toInt())

            var first = 0
            while (first < samples.size && kotlin.math.abs(samples[first].toInt()) < gate) first++
            var last = samples.size - 1
            while (last > first && kotlin.math.abs(samples[last].toInt()) < gate) last--

            val startIdx = (first - 160).coerceAtLeast(0)
            val endIdx = (last + 160).coerceAtMost(samples.size - 1)
            return if (endIdx <= startIdx) ShortArray(0) else samples.copyOfRange(startIdx, endIdx + 1)
        }

        private fun goertzelPower(
            samples: ShortArray,
            offset: Int,
            length: Int,
            targetFreqHz: Float,
            sampleRate: Int
        ): Double {
            val k = (0.5 + (length * targetFreqHz) / sampleRate).toInt()
            val omega = (2.0 * PI * k) / length
            val coeff = 2.0 * cos(omega)
            var q0 = 0.0
            var q1 = 0.0
            var q2 = 0.0
            for (i in 0 until length) {
                val x = samples[offset + i] / 32768.0
                q0 = coeff * q1 - q2 + x
                q2 = q1
                q1 = q0
            }
            return (q1 * q1 + q2 * q2 - q1 * q2 * coeff) / length
        }

        private fun normalizeInPlace(vec: FloatArray) {
            var normSq = 0.0
            for (v in vec) normSq += v * v
            val norm = sqrt(normSq).toFloat()
            if (norm > 1e-6f) {
                for (i in vec.indices) vec[i] /= norm
            }
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na * nb)
            return if (denom > 1e-9) (dot / denom).toFloat().coerceIn(0f, 1f) else 0f
        }
    }
}
