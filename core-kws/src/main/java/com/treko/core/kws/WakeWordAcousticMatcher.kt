package com.treko.core.kws

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Language-agnostic Personalized Acoustic DTW Wake-Word Matcher.
 *
 * Designed specifically so non-English pronunciations (e.g. Spanish `"treco"` with tapped `/ɾ/`
 * and pure `/e/ /o/`) wake reliably even when the bundled English Zipformer model does not emit
 * matching English subword tokens, while rejecting near-homophone Spanish words (`"trabajo"`,
 * `"taco fresco"`, `"techo creo"`, etc.).
 *
 * Signal processing pipeline:
 * - 16 kHz mono PCM, `0.97` pre-emphasis, 25 ms Hanning window (400 samples), 10 ms hop (160 samples)
 * - Pure Kotlin in-place 512-point radix-2 real/complex FFT
 * - 16-band triangular Log-Mel filterbank spanning 200 Hz – 4500 Hz
 * - Wiener spectral noise floor (`max(spec - 0.50 * noiseMel, 0.15 * spec)` from lowest-20% frames)
 * - Natural log compression `ln(clean + 1e-6)` + per-window CMVN + per-frame L2 normalization
 * - Sakoe-Chiba cosine DTW (`band = max(6, 0.35 * max(n, m))`, length ratio `<= 1.70`)
 */
class WakeWordAcousticMatcher(
    val keyword: String,
    val templates: List<MelTemplate>,
    val threshold: Float = DEFAULT_THRESHOLD,
    val sampleRate: Int = SAMPLE_RATE
) {
    class MelTemplate(
        val frameCount: Int,
        val data: FloatArray // flattened row-major: frameCount * NUM_BANDS
    ) {
        init {
            require(data.size == frameCount * NUM_BANDS) {
                "Expected ${frameCount * NUM_BANDS} floats, got ${data.size}"
            }
        }
    }

    /**
     * Scores `pcm` with multi-scale sliding windows (`0.85x`, `1.0x`, `1.2x`, stride 3 frames)
     * gated by active speech SNR. Returns the minimum cosine DTW distance across templates, or
     * `Float.POSITIVE_INFINITY` if the buffer fails the SNR gate or contains insufficient frames.
     */
    fun bestDistance(pcm: ShortArray, noiseFloorRms: Float = 0.005f): Float {
        if (templates.isEmpty() || pcm.size < WIN_SAMPLES) return Float.POSITIVE_INFINITY

        val extracted = extractRawMelAndRms(pcm) ?: return Float.POSITIVE_INFINITY
        val totalFrames = extracted.frameCount
        if (totalFrames < MIN_EVAL_FRAMES) return Float.POSITIVE_INFINITY

        val rmsGate = max(MIN_RMS_GATE, noiseFloorRms * SNR_GATE_MULTIPLIER)
        var overallMaxRms = 0f
        for (r in extracted.frameRms) {
            if (r > overallMaxRms) overallMaxRms = r
        }
        if (overallMaxRms < rmsGate) return Float.POSITIVE_INFINITY

        var bestDist = Float.POSITIVE_INFINITY
        val templateLengths = templates.map { it.frameCount }.distinct()

        for (tmplLen in templateLengths) {
            for (scale in WINDOW_SCALES) {
                val rawWinLen = (tmplLen * scale).toInt().coerceAtLeast(MIN_EVAL_FRAMES)
                if (rawWinLen >= totalFrames) {
                    // Candidate buffer is shorter than or comparable to scaled template: evaluate full buffer once
                    val ratio = max(totalFrames, tmplLen).toFloat() / min(totalFrames, tmplLen).toFloat()
                    if (ratio <= MAX_LENGTH_RATIO) {
                        val normWin = normalizeMelWindow(extracted.melSpec, 0, totalFrames)
                        for (tmpl in templates) {
                            val d = sakoeChibaDtw(normWin, totalFrames, tmpl.data, tmpl.frameCount)
                            if (d < bestDist) bestDist = d
                        }
                    }
                    continue
                }

                var start = 0
                val lastStart = totalFrames - rawWinLen
                while (start <= lastStart) {
                    var winMaxRms = 0f
                    var activeFrames = 0
                    val activeGate = rmsGate * 0.7f
                    for (f in start until start + rawWinLen) {
                        val r = extracted.frameRms[f]
                        if (r > winMaxRms) winMaxRms = r
                        if (r >= activeGate) activeFrames++
                    }

                    if (winMaxRms >= rmsGate && activeFrames >= 4) {
                        val normWin = normalizeMelWindow(extracted.melSpec, start, start + rawWinLen)
                        for (tmpl in templates) {
                            if (max(rawWinLen, tmpl.frameCount).toFloat() / min(rawWinLen, tmpl.frameCount) <= MAX_LENGTH_RATIO) {
                                val d = sakoeChibaDtw(normWin, rawWinLen, tmpl.data, tmpl.frameCount)
                                if (d < bestDist) bestDist = d
                            }
                        }
                    }
                    start += SLIDING_STRIDE_FRAMES
                }

                // Ensure tail alignment is always checked even if not aligned to stride
                if (lastStart > 0 && lastStart % SLIDING_STRIDE_FRAMES != 0) {
                    var winMaxRms = 0f
                    var activeFrames = 0
                    val activeGate = rmsGate * 0.7f
                    for (f in lastStart until totalFrames) {
                        val r = extracted.frameRms[f]
                        if (r > winMaxRms) winMaxRms = r
                        if (r >= activeGate) activeFrames++
                    }
                    if (winMaxRms >= rmsGate && activeFrames >= 4) {
                        val normWin = normalizeMelWindow(extracted.melSpec, lastStart, totalFrames)
                        for (tmpl in templates) {
                            if (max(rawWinLen, tmpl.frameCount).toFloat() / min(rawWinLen, tmpl.frameCount) <= MAX_LENGTH_RATIO) {
                                val d = sakoeChibaDtw(normWin, rawWinLen, tmpl.data, tmpl.frameCount)
                                if (d < bestDist) bestDist = d
                            }
                        }
                    }
                }
            }
        }

        return bestDist
    }

    fun matches(
        pcm: ShortArray,
        noiseFloorRms: Float = 0.005f,
        thresholdOverride: Float? = null
    ): Boolean {
        val effThreshold = (thresholdOverride ?: threshold).coerceIn(0.04f, 0.15f)
        return bestDistance(pcm, noiseFloorRms) <= effThreshold
    }

    fun toJson(): String {
        val escapedKw = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
        val tmplJson = templates.joinToString(",") { tmpl ->
            val vals = tmpl.data.joinToString(",") { v -> "%.5f".format(java.util.Locale.US, v) }
            """{"frames":${tmpl.frameCount},"data":[$vals]}"""
        }
        return """{"keyword":"$escapedKw","sampleRate":$sampleRate,"threshold":$threshold,"templates":[$tmplJson]}"""
    }

    private class RawMelResult(
        val frameCount: Int,
        val melSpec: Array<FloatArray>,
        val frameRms: FloatArray
    )

    companion object {
        const val SAMPLE_RATE = 16000
        const val FFT_SIZE = 512
        const val WIN_SAMPLES = 400 // 25ms at 16kHz
        const val HOP_SAMPLES = 160 // 10ms at 16kHz
        const val NUM_BANDS = 16
        const val MIN_FREQ_HZ = 200.0
        const val MAX_FREQ_HZ = 4500.0
        const val PRE_EMPHASIS = 0.97f

        const val DEFAULT_THRESHOLD = 0.078f
        const val MIN_ADAPTIVE_THRESHOLD = 0.072f
        const val MAX_ADAPTIVE_THRESHOLD = 0.088f
        const val MAX_LENGTH_RATIO = 1.70f
        const val MIN_RMS_GATE = 0.014f
        const val SNR_GATE_MULTIPLIER = 2.1f
        private const val MIN_EVAL_FRAMES = 8
        private const val SLIDING_STRIDE_FRAMES = 3
        private val WINDOW_SCALES = floatArrayOf(0.85f, 1.0f, 1.2f)

        private val HANNING_WINDOW = FloatArray(WIN_SAMPLES) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (WIN_SAMPLES - 1))).toFloat()
        }

        private val BIT_REV = IntArray(FFT_SIZE).also { rev ->
            val logN = 9 // log2(512)
            for (i in 0 until FFT_SIZE) {
                var r = 0
                var x = i
                for (b in 0 until logN) {
                    r = (r shl 1) or (x and 1)
                    x = x shr 1
                }
                rev[i] = r
            }
        }

        private val MEL_FILTERBANK: Array<FloatArray> = buildMelFilterbank()

        private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

        private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        private fun buildMelFilterbank(): Array<FloatArray> {
            val numBins = FFT_SIZE / 2 + 1
            val minMel = hzToMel(MIN_FREQ_HZ)
            val maxMel = hzToMel(MAX_FREQ_HZ)
            val melPoints = DoubleArray(NUM_BANDS + 2) { m ->
                minMel + (maxMel - minMel) * m / (NUM_BANDS + 1)
            }
            val binPoints = DoubleArray(NUM_BANDS + 2) { m ->
                val hz = melToHz(melPoints[m])
                (FFT_SIZE * hz) / SAMPLE_RATE
            }

            return Array(NUM_BANDS) { b ->
                val weights = FloatArray(numBins)
                val left = binPoints[b]
                val center = binPoints[b + 1]
                val right = binPoints[b + 2]
                for (k in 0 until numBins) {
                    val kd = k.toDouble()
                    val w = when {
                        kd >= left && kd <= center && center > left -> (kd - left) / (center - left)
                        kd > center && kd <= right && right > center -> (right - kd) / (right - center)
                        else -> 0.0
                    }
                    weights[k] = w.toFloat()
                }
                weights
            }
        }

        /**
         * Builds a personalized acoustic matcher from up to 3 enrolled user takes plus
         * 1 synthetic ~14 dB noisy copy of take 0. Computes pairwise self-consistency `d_self`
         * and sets `dtwThreshold = max(0.072f, min(0.088f, d_self * 1.90f))` (`0.078f` fallback).
         */
        fun fromEnrolledSamples(
            keyword: String,
            samplesList: List<ShortArray>,
            sampleRate: Int = SAMPLE_RATE,
            noiseFloorRms: Float = 0.005f
        ): WakeWordAcousticMatcher? {
            if (samplesList.isEmpty()) return null

            val realTrimmed = mutableListOf<ShortArray>()
            val realTemplates = mutableListOf<MelTemplate>()
            for (raw in samplesList) {
                if (realTemplates.size >= 3) break
                val trimmed = trimSpeech(raw, noiseFloorRms)
                val tmpl = extractNormalizedTemplate(trimmed) ?: extractNormalizedTemplate(raw)
                if (tmpl != null) {
                    realTrimmed.add(if (trimmed.size >= WIN_SAMPLES) trimmed else raw)
                    realTemplates.add(tmpl)
                }
            }
            if (realTemplates.isEmpty()) return null

            var pairDistSum = 0.0
            var pairCount = 0
            for (i in realTemplates.indices) {
                for (j in i + 1 until realTemplates.size) {
                    val d = sakoeChibaDtw(
                        realTemplates[i].data,
                        realTemplates[i].frameCount,
                        realTemplates[j].data,
                        realTemplates[j].frameCount
                    )
                    if (d.isFinite()) {
                        pairDistSum += d
                        pairCount++
                    }
                }
            }

            val adaptiveThreshold = if (pairCount > 0) {
                val dSelf = (pairDistSum / pairCount).toFloat()
                max(MIN_ADAPTIVE_THRESHOLD, min(MAX_ADAPTIVE_THRESHOLD, dSelf * 1.90f))
            } else {
                DEFAULT_THRESHOLD
            }

            val finalTemplates = mutableListOf<MelTemplate>()
            finalTemplates.addAll(realTemplates)

            // Add 1 synthetic ~14 dB SNR noisy copy of take 0 for robustness in noisy rooms
            val baseTake = realTrimmed.first()
            val sigRms = computeRms(baseTake)
            if (sigRms > 0.005f) {
                val targetNoiseRms = sigRms / 5.012f // 10^(-14/20)
                val rng = Random(1337L)
                val noisyTake = ShortArray(baseTake.size) { idx ->
                    val n = (rng.nextGaussian() * targetNoiseRms * 32768.0).toInt()
                    (baseTake[idx].toInt() + n).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                extractNormalizedTemplate(noisyTake)?.let { finalTemplates.add(it) }
            }

            return WakeWordAcousticMatcher(
                keyword = keyword.trim().ifEmpty { "Treko" },
                templates = finalTemplates,
                threshold = adaptiveThreshold,
                sampleRate = sampleRate
            )
        }

        fun fromJson(raw: String, thresholdOverride: Float? = null): WakeWordAcousticMatcher? {
            if (raw.isBlank()) return null
            return try {
                val kwMatch = Regex(""""keyword"\s*:\s*"((?:\\.|[^"])*)"""").find(raw)
                val srMatch = Regex(""""sampleRate"\s*:\s*(\d+)""").find(raw)
                val thrMatch = Regex(""""threshold"\s*:\s*([0-9eE.+\-]+)""").find(raw)

                val tmplRegex = Regex("""\{"frames"\s*:\s*(\d+)\s*,\s*"data"\s*:\s*\[([^\]]+)]\}""")
                val parsedTemplates = mutableListOf<MelTemplate>()
                for (m in tmplRegex.findAll(raw)) {
                    val frames = m.groupValues[1].toIntOrNull() ?: continue
                    val nums = m.groupValues[2].split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (frames >= MIN_EVAL_FRAMES && nums.size == frames * NUM_BANDS) {
                        parsedTemplates.add(MelTemplate(frames, nums.toFloatArray()))
                    }
                }
                if (parsedTemplates.isEmpty()) return null

                val kw = (kwMatch?.groupValues?.get(1) ?: "Treko")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val sr = srMatch?.groupValues?.get(1)?.toIntOrNull() ?: SAMPLE_RATE
                val parsedThr = thresholdOverride
                    ?: thrMatch?.groupValues?.get(1)?.toFloatOrNull()
                    ?: DEFAULT_THRESHOLD

                WakeWordAcousticMatcher(
                    keyword = kw,
                    templates = parsedTemplates,
                    threshold = parsedThr.coerceIn(0.04f, 0.15f),
                    sampleRate = sr
                )
            } catch (_: Exception) {
                null
            }
        }

        internal fun trimSpeech(samples: ShortArray, noiseFloorRms: Float = 0.005f): ShortArray {
            if (samples.size < WIN_SAMPLES) return samples
            val step = HOP_SAMPLES
            val numFrames = (samples.size - step) / step + 1
            if (numFrames <= 0) return samples

            val rmsPerFrame = FloatArray(numFrames)
            var peakRms = 0f
            for (f in 0 until numFrames) {
                val start = f * step
                val end = min(samples.size, start + step)
                var sq = 0.0
                for (i in start until end) {
                    val v = samples[i] / 32768.0
                    sq += v * v
                }
                val r = sqrt(sq / max(1, end - start)).toFloat()
                rmsPerFrame[f] = r
                if (r > peakRms) peakRms = r
            }
            if (peakRms < 0.004f) return ShortArray(0)

            val gate = maxOf(0.012f, noiseFloorRms * 2.0f, peakRms * 0.14f)
            var first = -1
            var last = -1
            for (f in 0 until numFrames) {
                if (rmsPerFrame[f] >= gate) {
                    if (first < 0) first = f
                    last = f
                }
            }
            if (first < 0) return samples

            // Keep 60ms leading pad (preserves crisp voiceless stop onsets like /t/) and 50ms trailing pad
            val startSample = max(0, (first - 6) * step)
            val endSample = min(samples.size, (last + 6) * step)
            return if (endSample - startSample < WIN_SAMPLES) samples else samples.copyOfRange(startSample, endSample)
        }

        internal fun extractNormalizedTemplate(pcm: ShortArray): MelTemplate? {
            val raw = extractRawMelAndRms(pcm) ?: return null
            if (raw.frameCount < MIN_EVAL_FRAMES) return null
            val norm = normalizeMelWindow(raw.melSpec, 0, raw.frameCount)
            return MelTemplate(raw.frameCount, norm)
        }

        private fun extractRawMelAndRms(pcm: ShortArray): RawMelResult? {
            if (pcm.size < WIN_SAMPLES) return null
            val frameCount = (pcm.size - WIN_SAMPLES) / HOP_SAMPLES + 1
            if (frameCount <= 0) return null

            val melSpec = Array(frameCount) { FloatArray(NUM_BANDS) }
            val frameRms = FloatArray(frameCount)
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            val power = FloatArray(FFT_SIZE / 2 + 1)

            for (f in 0 until frameCount) {
                val offset = f * HOP_SAMPLES
                var sumSq = 0.0
                for (i in 0 until WIN_SAMPLES) {
                    val idx = offset + i
                    val cur = pcm[idx] / 32768.0f
                    val prev = if (idx > 0) pcm[idx - 1] / 32768.0f else 0f
                    sumSq += (cur * cur).toDouble()
                    re[i] = (cur - PRE_EMPHASIS * prev) * HANNING_WINDOW[i]
                    im[i] = 0f
                }
                for (i in WIN_SAMPLES until FFT_SIZE) {
                    re[i] = 0f
                    im[i] = 0f
                }
                frameRms[f] = sqrt(sumSq / WIN_SAMPLES).toFloat()

                fft512(re, im)

                val halfBins = FFT_SIZE / 2
                for (k in 0..halfBins) {
                    power[k] = re[k] * re[k] + im[k] * im[k]
                }

                val outBands = melSpec[f]
                for (b in 0 until NUM_BANDS) {
                    val weights = MEL_FILTERBANK[b]
                    var acc = 0f
                    for (k in 0..halfBins) {
                        val w = weights[k]
                        if (w > 0f) acc += power[k] * w
                    }
                    outBands[b] = acc
                }
            }

            return RawMelResult(frameCount, melSpec, frameRms)
        }

        private fun normalizeMelWindow(
            melSpec: Array<FloatArray>,
            startFrame: Int,
            endFrame: Int
        ): FloatArray {
            val len = endFrame - startFrame
            // Estimate Wiener spectral noise floor from lowest-20% total-energy frames in this window
            val frameEnergies = FloatArray(len) { idx ->
                val row = melSpec[startFrame + idx]
                var s = 0f
                for (b in 0 until NUM_BANDS) s += row[b]
                s
            }
            val indices = Array(len) { it }
            indices.sortWith { a, b -> frameEnergies[a].compareTo(frameEnergies[b]) }

            val bottomK = max(1, (len * 0.20f).toInt())
            val noiseMel = FloatArray(NUM_BANDS)
            for (k in 0 until bottomK) {
                val row = melSpec[startFrame + indices[k]]
                for (b in 0 until NUM_BANDS) {
                    noiseMel[b] += row[b]
                }
            }
            for (b in 0 until NUM_BANDS) {
                noiseMel[b] /= bottomK.toFloat()
            }

            // Wiener subtraction + natural log compression
            val logMat = FloatArray(len * NUM_BANDS)
            val bandMeans = FloatArray(NUM_BANDS)
            for (t in 0 until len) {
                val row = melSpec[startFrame + t]
                val base = t * NUM_BANDS
                for (b in 0 until NUM_BANDS) {
                    val spec = row[b]
                    val clean = max(spec - 0.50f * noiseMel[b], 0.15f * spec)
                    val l = ln(clean + 1e-6f)
                    logMat[base + b] = l
                    bandMeans[b] += l
                }
            }
            for (b in 0 until NUM_BANDS) {
                bandMeans[b] /= len.toFloat()
            }

            // Per-window CMVN
            val bandVars = FloatArray(NUM_BANDS)
            for (t in 0 until len) {
                val base = t * NUM_BANDS
                for (b in 0 until NUM_BANDS) {
                    val d = logMat[base + b] - bandMeans[b]
                    bandVars[b] += d * d
                }
            }
            val bandStds = FloatArray(NUM_BANDS) { b ->
                max(sqrt(bandVars[b] / len.toFloat()), 0.02f)
            }

            // Normalize CMVN + L2 norm per frame
            for (t in 0 until len) {
                val base = t * NUM_BANDS
                var sq = 0f
                for (b in 0 until NUM_BANDS) {
                    val cmvn = (logMat[base + b] - bandMeans[b]) / bandStds[b]
                    logMat[base + b] = cmvn
                    sq += cmvn * cmvn
                }
                val l2 = sqrt(sq)
                if (l2 > 1e-6f) {
                    val inv = 1.0f / l2
                    for (b in 0 until NUM_BANDS) {
                        logMat[base + b] *= inv
                    }
                }
            }

            return logMat
        }

        internal fun sakoeChibaDtw(
            aData: FloatArray,
            aCount: Int,
            bData: FloatArray,
            bCount: Int
        ): Float {
            if (aCount < MIN_EVAL_FRAMES || bCount < MIN_EVAL_FRAMES) return Float.POSITIVE_INFINITY
            val maxLen = max(aCount, bCount)
            val minLen = min(aCount, bCount)
            if (maxLen.toFloat() / minLen.toFloat() > MAX_LENGTH_RATIO) return Float.POSITIVE_INFINITY

            val band = max(6, (0.35f * maxLen).toInt())
            var prev = FloatArray(bCount + 1) { Float.POSITIVE_INFINITY }
            var curr = FloatArray(bCount + 1) { Float.POSITIVE_INFINITY }
            prev[0] = 0f

            val ratio = bCount.toFloat() / aCount.toFloat()
            for (i in 1..aCount) {
                curr.fill(Float.POSITIVE_INFINITY)
                val center = (i * ratio).toInt()
                val jStart = max(1, center - band)
                val jEnd = min(bCount, center + band)
                val aOff = (i - 1) * NUM_BANDS

                for (j in jStart..jEnd) {
                    val bOff = (j - 1) * NUM_BANDS
                    var dot = 0f
                    for (k in 0 until NUM_BANDS) {
                        dot += aData[aOff + k] * bData[bOff + k]
                    }
                    val dist = (1.0f - dot).coerceIn(0f, 2f)
                    val bestPrev = minOf(prev[j], minOf(curr[j - 1], prev[j - 1]))
                    if (bestPrev < Float.POSITIVE_INFINITY) {
                        curr[j] = dist + bestPrev
                    }
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }

            val totalCost = prev[bCount]
            return if (totalCost.isFinite()) totalCost / (aCount + bCount).toFloat() else Float.POSITIVE_INFINITY
        }

        internal fun fft512(re: FloatArray, im: FloatArray) {
            for (i in 0 until FFT_SIZE) {
                val j = BIT_REV[i]
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }

            var len = 2
            while (len <= FFT_SIZE) {
                val half = len shr 1
                val ang = -2.0 * PI / len
                val wStepRe = cos(ang).toFloat()
                val wStepIm = sin(ang).toFloat()
                var i = 0
                while (i < FFT_SIZE) {
                    var wRe = 1.0f
                    var wIm = 0.0f
                    for (j in 0 until half) {
                        val uRe = re[i + j]
                        val uIm = im[i + j]
                        val vIdx = i + j + half
                        val vRe = re[vIdx] * wRe - im[vIdx] * wIm
                        val vIm = re[vIdx] * wIm + im[vIdx] * wRe

                        re[i + j] = uRe + vRe
                        im[i + j] = uIm + vIm
                        re[vIdx] = uRe - vRe
                        im[vIdx] = uIm - vIm

                        val nextWRe = wRe * wStepRe - wIm * wStepIm
                        wIm = wRe * wStepIm + wIm * wStepRe
                        wRe = nextWRe
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun computeRms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) {
                val v = s / 32768.0
                sum += v * v
            }
            return sqrt(sum / samples.size).toFloat()
        }
    }
}
