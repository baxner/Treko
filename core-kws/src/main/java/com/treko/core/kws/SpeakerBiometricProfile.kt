package com.treko.core.kws

import kotlin.math.sqrt

/**
 * Huella biométrica vocal independiente del texto generada con embeddings neuronales (3D-Speaker CAM++).
 * Almacena el centroide L2-normalizado y las tomas de enrolamiento para verificar al usuario
 * durante cualquier frase hablada (p. ej. para ignorar voces de películas o terceros).
 */
data class SpeakerBiometricProfile(
    val dim: Int,
    val centroid: FloatArray,
    val sampleEmbeddings: List<FloatArray>,
    val minSelfSimilarity: Float,
    val recommendedThreshold: Float,
    val modelName: String = DEFAULT_MODEL_ID,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun score(candidateEmbedding: FloatArray): Float {
        if (candidateEmbedding.size != dim || centroid.size != dim) return 0f
        val normCandidate = l2Normalize(candidateEmbedding)
        val centroidSim = cosineSimilarityNormalized(normCandidate, centroid)
        var bestTakeSim = centroidSim
        for (sample in sampleEmbeddings) {
            if (sample.size == dim) {
                val s = cosineSimilarityNormalized(normCandidate, sample)
                if (s > bestTakeSim) bestTakeSim = s
            }
        }
        return (0.60f * centroidSim + 0.40f * bestTakeSim).coerceIn(-1f, 1f)
    }

    fun isMatch(candidateEmbedding: FloatArray, threshold: Float = recommendedThreshold): Boolean {
        return score(candidateEmbedding) >= threshold
    }

    fun toJson(): String {
        val escapedModel = modelName.replace("\\", "\\\\").replace("\"", "\\\"")
        val centroidStr = centroid.joinToString(",") { it.toString() }
        val samplesStr = sampleEmbeddings.joinToString(",") { vec ->
            "[" + vec.joinToString(",") { it.toString() } + "]"
        }
        return """{"version":1,"model":"$escapedModel","dim":$dim,"minSelfSim":$minSelfSimilarity,"recommendedThreshold":$recommendedThreshold,"createdAtMs":$createdAtMs,"centroid":[$centroidStr],"samples":[$samplesStr]}"""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SpeakerBiometricProfile
        if (dim != other.dim || modelName != other.modelName) return false
        if (!centroid.contentEquals(other.centroid)) return false
        if (sampleEmbeddings.size != other.sampleEmbeddings.size) return false
        for (i in sampleEmbeddings.indices) {
            if (!sampleEmbeddings[i].contentEquals(other.sampleEmbeddings[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = dim
        result = 31 * result + centroid.contentHashCode()
        result = 31 * result + modelName.hashCode()
        result = 31 * result + sampleEmbeddings.size
        return result
    }

    companion object {
        const val DEFAULT_MODEL_ID = "3dspeaker-campplus-zh-en"
        const val MIN_RECOMMENDED_THRESHOLD = 0.44f
        const val MAX_RECOMMENDED_THRESHOLD = 0.72f

        fun l2Normalize(vec: FloatArray): FloatArray {
            if (vec.isEmpty()) return vec
            var normSq = 0.0
            for (v in vec) normSq += v * v
            val norm = sqrt(normSq).toFloat()
            if (norm <= 1e-7f) return vec.copyOf()
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            return cosineSimilarityNormalized(l2Normalize(a), l2Normalize(b))
        }

        private fun cosineSimilarityNormalized(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
            }
            return dot.toFloat().coerceIn(-1f, 1f)
        }

        fun fromEmbeddings(
            rawEmbeddings: List<FloatArray>,
            modelName: String = DEFAULT_MODEL_ID
        ): SpeakerBiometricProfile? {
            if (rawEmbeddings.isEmpty()) return null
            val dim = rawEmbeddings.first().size
            if (dim <= 0) return null

            val normalizedTakes = rawEmbeddings
                .filter { it.size == dim && it.any { v -> v != 0f } }
                .map { l2Normalize(it) }
            if (normalizedTakes.isEmpty()) return null

            val centroidSum = FloatArray(dim)
            for (emb in normalizedTakes) {
                for (i in 0 until dim) centroidSum[i] += emb[i]
            }
            val centroid = l2Normalize(centroidSum)

            // Leave-one-out self-consistency to estimate natural voice variance
            var minSelfSim = 1.0f
            if (normalizedTakes.size >= 2) {
                for (idx in normalizedTakes.indices) {
                    val restSum = FloatArray(dim)
                    for (j in normalizedTakes.indices) {
                        if (j != idx) {
                            for (k in 0 until dim) restSum[k] += normalizedTakes[j][k]
                        }
                    }
                    val restCentroid = l2Normalize(restSum)
                    val sim = cosineSimilarityNormalized(normalizedTakes[idx], restCentroid)
                    if (sim < minSelfSim) minSelfSim = sim
                }
            } else {
                minSelfSim = 0.75f
            }

            // Leave headroom for room/TV noise and microphone angle variations
            val recommended = (minSelfSim - 0.14f).coerceIn(MIN_RECOMMENDED_THRESHOLD, MAX_RECOMMENDED_THRESHOLD)

            return SpeakerBiometricProfile(
                dim = dim,
                centroid = centroid,
                sampleEmbeddings = normalizedTakes,
                minSelfSimilarity = minSelfSim,
                recommendedThreshold = recommended,
                modelName = modelName
            )
        }

        fun fromJson(raw: String): SpeakerBiometricProfile? {
            if (raw.isBlank()) return null
            return try {
                val modelMatch = Regex(""""model"\s*:\s*"((?:\\.|[^"])*)"""").find(raw)
                val dimMatch = Regex(""""dim"\s*:\s*(\d+)""").find(raw) ?: return null
                val minSimMatch = Regex(""""minSelfSim"\s*:\s*([0-9eE.+\-]+)""").find(raw)
                val threshMatch = Regex(""""recommendedThreshold"\s*:\s*([0-9eE.+\-]+)""").find(raw)
                val tsMatch = Regex(""""createdAtMs"\s*:\s*(\d+)""").find(raw)
                val centroidMatch = Regex(""""centroid"\s*:\s*\[([^\]]+)]""").find(raw) ?: return null
                val samplesMatch = Regex(""""samples"\s*:\s*\[(.*)]\s*\}""").find(raw)

                val dim = dimMatch.groupValues[1].toIntOrNull() ?: return null
                if (dim <= 0) return null

                val centroidParts = centroidMatch.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (centroidParts.size != dim) return null

                val parsedSamples = ArrayList<FloatArray>()
                if (samplesMatch != null) {
                    val inner = samplesMatch.groupValues[1]
                    val takeMatches = Regex("""\[([^\]]+)]""").findAll(inner)
                    for (m in takeMatches) {
                        val floats = m.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
                        if (floats.size == dim) {
                            parsedSamples.add(l2Normalize(floats.toFloatArray()))
                        }
                    }
                }
                if (parsedSamples.isEmpty()) {
                    parsedSamples.add(l2Normalize(centroidParts.toFloatArray()))
                }

                val model = (modelMatch?.groupValues?.get(1) ?: DEFAULT_MODEL_ID)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")

                SpeakerBiometricProfile(
                    dim = dim,
                    centroid = l2Normalize(centroidParts.toFloatArray()),
                    sampleEmbeddings = parsedSamples,
                    minSelfSimilarity = minSimMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.70f,
                    recommendedThreshold = threshMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.56f,
                    modelName = model,
                    createdAtMs = tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
