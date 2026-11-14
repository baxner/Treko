package com.treko.core.kws

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.treko.core.audio.AdaptiveVad
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.util.TrekoLogBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.tanh

/**
 * Gestor de detección local de palabras de activación (Wake Word) usando Sherpa-ONNX en Android.
 * Incluye:
 * - Pre-amplificación adaptativa con soft-knee limiting (sin clipping en voces cercanas).
 * - Pre-filtrado VAD adaptativo por energía + ZCR con ring-buffer de pre-roll (300ms) para ahorrar CPU y evitar falsos positivos en ruido.
 * - Verificación opcional por huella vocal (WakeWordVoicePrint).
 * - Evaluación offline por replay sobre el motor Zipformer real para calibración de entrenamiento.
 */
class TrekoWakeWordDetector(private val context: Context) {

    data class WakeWordDetectionEvent(
        val keyword: String,
        val snrDb: Float,
        val voicePrintSimilarity: Float,
        val timestampMs: Long,
        val source: String = "KWS"
    )

    data class KwsDiagnostics(
        val isInitialized: Boolean,
        val keyword: String,
        val boost: Float,
        val threshold: Float,
        val vadGatingEnabled: Boolean,
        val noiseFloorRms: Float,
        val hasVoicePrint: Boolean,
        val speakerStrictness: Float,
        val totalFramesProcessed: Long,
        val vadSkippedFrames: Long,
        val suppressedBySpeakerGate: Long,
        val hasAcousticMatcher: Boolean = false,
        val dtwThreshold: Float = WakeWordAcousticMatcher.DEFAULT_THRESHOLD,
        val dtwDetectionsCount: Long = 0L
    )

    private val TAG = "TrekoKWS"

    private val _detectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val detectionEvents: SharedFlow<String> = _detectionEvents.asSharedFlow()

    private val _detectionDetails = MutableSharedFlow<WakeWordDetectionEvent>(extraBufferCapacity = 64)
    val detectionDetails: SharedFlow<WakeWordDetectionEvent> = _detectionDetails.asSharedFlow()

    @Volatile
    var isInitialized = false
        private set

    @Volatile
    var vadGatingEnabled: Boolean = true

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var tokenizer: KwsTokenizer? = null

    private var currentKeyword: String = "Treko"
    private var currentBoost: Float = 3.0f
    private var currentThreshold: Float = 0.05f
    private var lastDetectionTime: Long = 0L

    private val lock = Any()
    private val kwsVad = AdaptiveVad(initialNoiseFloorRms = 0.005f, hangoverFrames = 10)

    // Pre-allocated scratch buffers to avoid per-frame GC allocations on the audio thread
    private var scratchFloatBuffer = FloatArray(960)
    private var preRollFloatBuffer = FloatArray(PRE_ROLL_SAMPLES)
    private var preRollIndex = 0
    private var preRollFilled = 0
    private var wasVadActive = false

    // Ring buffer of recent raw PCM shorts (~1.4s) for Acoustic DTW co-detection & VoicePrint verification
    private val recentPcmRing = ShortArray(VOICEPRINT_WINDOW_SAMPLES)
    private var recentPcmIndex = 0
    private var recentPcmFilled = 0

    // Peak active speech tracking for accurate WakeWordDetectionEvent.snrDb and periodic DTW co-detection
    private var peakSpeechSnrDb: Float = 0f
    private var activeSpeechFrames: Int = 0

    @Volatile
    private var enrolledVoicePrint: WakeWordVoicePrint? = null

    @Volatile
    private var acousticMatcher: WakeWordAcousticMatcher? = null

    @Volatile
    private var speakerStrictness: Float = 0f

    @Volatile
    private var totalFramesProcessed: Long = 0L

    @Volatile
    private var vadSkippedFrames: Long = 0L

    @Volatile
    private var suppressedBySpeakerGate: Long = 0L

    @Volatile
    private var dtwDetectionsCount: Long = 0L

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PRE_ROLL_SAMPLES = 4800 // 300ms pre-roll at 16kHz
        private const val VOICEPRINT_WINDOW_SAMPLES = 22400 // 1.4s at 16kHz
        private const val DEBOUNCE_MS = 1500L
    }

    /**
     * Inicializa el KeywordSpotter nativo directamente desde los assets de la aplicación.
     */
    fun initialize(
        initialKeyword: String = "Treko",
        boost: Float = 3.0f,
        threshold: Float = 0.05f
    ): Boolean {
        synchronized(lock) {
            var newSpotter: KeywordSpotter? = null
            try {
                if (isInitialized) {
                    updateKeywords(initialKeyword, boost, threshold)
                    return true
                }

                val safeBoost = boost.coerceIn(1.0f, 6.0f)
                val safeThreshold = threshold.coerceIn(0.005f, 0.50f)

                tokenizer = KwsTokenizer.loadFromAssets(context, "models/en/tokens.txt")

                val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "models/en/encoder.onnx",
                        decoder = "models/en/decoder.onnx",
                        joiner = "models/en/joiner.onnx"
                    ),
                    tokens = "models/en/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    modelType = "zipformer2"
                )

                val config = KeywordSpotterConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig,
                    maxActivePaths = 10,
                    keywordsFile = "models/en/keywords.txt",
                    keywordsScore = safeBoost,
                    keywordsThreshold = safeThreshold,
                    numTrailingBlanks = 1
                )

                newSpotter = KeywordSpotter(context.assets, config)
                val kwString = buildKeywordsString(initialKeyword, safeBoost, safeThreshold)
                val newStream = newSpotter.createStream(kwString)

                spotter = newSpotter
                stream = newStream
                currentKeyword = initialKeyword
                currentBoost = safeBoost
                currentThreshold = safeThreshold
                isInitialized = true

                TrekoLogBuffer.i(
                    TAG,
                    "KeywordSpotter inicializado con '$initialKeyword' (boost=$safeBoost, threshold=$safeThreshold)"
                )
                return true
            } catch (e: Exception) {
                TrekoLogBuffer.e(TAG, "Error inicializando Sherpa-ONNX KeywordSpotter", e)
                try {
                    newSpotter?.release()
                } catch (_: Exception) { }
                isInitialized = false
                return false
            }
        }
    }

    /**
     * Sincroniza configuración calibrada y huella vocal desde TrekoPreferences.
     */
    fun loadFromPreferences(prefs: TrekoPreferences) {
        synchronized(lock) {
            vadGatingEnabled = prefs.vadAdaptiveEnabled
            if (prefs.isWakeWordCalibrated) {
                kwsVad.reset(prefs.calibratedNoiseFloorRms)
            }
            val vp = WakeWordVoicePrint.fromJson(prefs.wakeWordVoicePrintJson)
            enrolledVoicePrint = vp
            speakerStrictness = if (prefs.speakerVerificationEnabled && vp != null) 0.55f else 0f
            acousticMatcher = WakeWordAcousticMatcher.fromJson(
                raw = prefs.wakeWordAcousticTemplatesJson,
                thresholdOverride = prefs.wakeWordDtwThreshold
            )
        }
    }

    fun setVoicePrint(voicePrint: WakeWordVoicePrint?, strictness: Float = 0f) {
        synchronized(lock) {
            enrolledVoicePrint = voicePrint
            speakerStrictness = if (voicePrint != null) strictness.coerceIn(0f, 0.85f) else 0f
        }
    }

    fun setAcousticMatcher(matcher: WakeWordAcousticMatcher?) {
        synchronized(lock) {
            acousticMatcher = matcher
        }
    }

    fun setNoiseFloorRms(noiseFloorRms: Float) {
        kwsVad.reset(noiseFloorRms)
    }

    /**
     * Genera la cadena de configuración KWS en formato Sherpa-ONNX.
     */
    fun buildKeywordsString(keyword: String, boost: Float, threshold: Float): String {
        val clean = keyword.trim().ifEmpty { "Treko" }
        val safeBoost = boost.coerceIn(1.0f, 6.0f)
        val safeThreshold = threshold.coerceIn(0.005f, 0.50f)
        val lines = linkedSetOf<String>()

        if (clean.equals("Treko", ignoreCase = true)) {
            val variants = listOf(
                "▁T RE K O",
                "▁T RE CO",
                "▁T RE CK O",
                "▁T RE ▁CO",
                "▁T RE ▁K O",
                "▁T R E K O",
                "▁T R E CO",
                "▁TO RE CO",
                "▁TO RE K O",
                "▁TRA CO",
                "▁TRA K O",
                "▁TRA CK O",
                "▁TRA C K O",
                "▁T RA CO",
                "▁T RA K O",
                "▁TH RE CO",
                "▁TH RE K O",
                "▁THE RE CO",
                "▁THE ▁RA CO",
                "▁THE ▁RA K O",
                "▁DR E CO",
                "▁DR E K O",
                "▁G RE CO",
                "▁G RE K O",
                "▁T RE G O",
                "▁T RE ▁GO",
                "▁TRA Y CO",
                "▁TRA Y K O",
                "▁HE Y ▁T RE K O",
                "▁HE Y ▁T RE CO",
                "▁HE Y ▁TRA CO",
                "▁O Y E ▁T RE K O",
                "▁O Y E ▁T RE CO",
                "▁O Y E ▁TRA CO"
            )
            for (v in variants) {
                lines.add(String.format(java.util.Locale.US, "%s :%.1f #%.2f @TREKO", v, safeBoost, safeThreshold))
            }
        } else {
            val tok = tokenizer ?: KwsTokenizer.loadFromAssets(context)
            val mainLine = tok.formatKeywordLine(clean, safeBoost, safeThreshold)
            if (mainLine.isNotEmpty()) {
                lines.add(mainLine)
            }

            // Expand phonetic variants with slightly stricter threshold to boost recall safely
            val phoneticForms = tok.generatePhoneticVariants(clean, maxVariants = 4)
            for (variant in phoneticForms.drop(1)) {
                val varLine = tok.formatKeywordLine(variant, safeBoost, (safeThreshold * 1.1f).coerceAtMost(0.50f), clean)
                if (varLine.isNotEmpty()) {
                    lines.add(varLine)
                }
            }

            val isHey = clean.startsWith("hey", ignoreCase = true)
            val isOye = clean.startsWith("oye", ignoreCase = true)
            if (!isHey) {
                val heyLine = tok.formatKeywordLine("Hey $clean", safeBoost, safeThreshold, clean)
                if (heyLine.isNotEmpty()) {
                    lines.add(heyLine)
                }
            }
            if (!isOye && !isHey) {
                val oyeLine = tok.formatKeywordLine("Oye $clean", safeBoost, safeThreshold, clean)
                if (oyeLine.isNotEmpty()) {
                    lines.add(oyeLine)
                }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Actualiza la palabra clave, potencia (/boost) y sensibilidad (threshold) sin recargar ONNX.
     */
    fun updateKeywords(newKeyword: String, boost: Float, threshold: Float) {
        synchronized(lock) {
            val safeBoost = boost.coerceIn(1.0f, 6.0f)
            val safeThreshold = threshold.coerceIn(0.005f, 0.50f)
            currentKeyword = newKeyword
            currentBoost = safeBoost
            currentThreshold = safeThreshold

            val activeSpotter = spotter
            if (activeSpotter == null) {
                Log.d(TAG, "KeywordSpotter no inicializado aún, procediendo a inicializar...")
                initialize(newKeyword, safeBoost, safeThreshold)
                return
            }

            val kwString = buildKeywordsString(newKeyword, safeBoost, safeThreshold)
            if (kwString.isBlank()) {
                TrekoLogBuffer.w(TAG, "No se pudieron generar tokens válidos para '$newKeyword'; manteniendo stream anterior.")
                return
            }

            TrekoLogBuffer.i(TAG, "Actualizando KWS en caliente ($newKeyword, boost=$safeBoost, threshold=$safeThreshold)")

            try {
                // Create new stream before releasing old stream so stream never becomes null on failure
                val newStream = activeSpotter.createStream(kwString)
                stream?.release()
                stream = newStream
            } catch (e: Exception) {
                TrekoLogBuffer.e(TAG, "Error recreando OnlineStream con '$newKeyword'", e)
            }
        }
    }

    /**
     * Procesa un buffer de audio PCM de entrada de 16 kHz mono con VAD pre-gating, ganancia adaptativa
     * y co-detección acústica DTW (para pronunciaciones en español como `"treco"`).
     */
    fun acceptWaveform(samples: ShortArray, read: Int = samples.size) {
        if (read <= 0 || samples.isEmpty()) return
        val safeRead = minOf(read, samples.size)

        var dtwCandidatePcm: ShortArray? = null
        var dtwNoiseFloor = 0.005f
        var dtwSnrDb = 0f
        var dtwMatcher: WakeWordAcousticMatcher? = null

        synchronized(lock) {
            if (!isInitialized) return
            val activeSpotter = spotter ?: return
            val activeStream = stream ?: return

            totalFramesProcessed++
            recordRecentPcmLocked(samples, safeRead)

            val metrics = kwsVad.process(samples, safeRead)
            val gain = metrics.recommendedGain

            if (scratchFloatBuffer.size != safeRead) {
                scratchFloatBuffer = FloatArray(safeRead)
            }
            fillSoftKneeNormalized(samples, safeRead, gain, scratchFloatBuffer)

            val endedSpeechBurst = wasVadActive && !metrics.isSpeech
            val burstSnrSnapshot = if (endedSpeechBurst) peakSpeechSnrDb else maxOf(peakSpeechSnrDb, metrics.snrDb)

            if (metrics.isSpeech) {
                peakSpeechSnrDb = if (activeSpeechFrames == 0) metrics.snrDb else maxOf(peakSpeechSnrDb, metrics.snrDb)
                activeSpeechFrames++
            }

            val periodicSpeechCheck = metrics.isSpeech && activeSpeechFrames >= 5 && (activeSpeechFrames - 5) % 4 == 0
            val shouldRunDtw = acousticMatcher != null &&
                ((endedSpeechBurst && activeSpeechFrames >= 4) || periodicSpeechCheck) &&
                (System.currentTimeMillis() - lastDetectionTime >= DEBOUNCE_MS)

            if (shouldRunDtw) {
                dtwMatcher = acousticMatcher
                dtwCandidatePcm = snapshotRecentPcmLocked()
                dtwNoiseFloor = kwsVad.noiseFloorRms
                dtwSnrDb = burstSnrSnapshot
            }

            if (endedSpeechBurst) {
                activeSpeechFrames = 0
                peakSpeechSnrDb = 0f
            }

            if (vadGatingEnabled && !metrics.isSpeech) {
                pushPreRollLocked(scratchFloatBuffer, safeRead)
                wasVadActive = false
                vadSkippedFrames++
            } else {
                try {
                    if (vadGatingEnabled && !wasVadActive) {
                        flushPreRollToStreamLocked(activeStream)
                    }
                    wasVadActive = metrics.isSpeech

                    activeStream.acceptWaveform(scratchFloatBuffer, SAMPLE_RATE)

                    while (activeSpotter.isReady(activeStream)) {
                        activeSpotter.decode(activeStream)
                    }

                    val result = activeSpotter.getResult(activeStream)
                    if (result.keyword.isNotBlank()) {
                        activeSpotter.reset(activeStream)
                        val eventSnr = maxOf(burstSnrSnapshot, metrics.snrDb)
                        if (emitVerifiedDetectionLocked(result.keyword, eventSnr, source = "KWS")) {
                            activeSpeechFrames = 0
                            peakSpeechSnrDb = 0f
                            dtwCandidatePcm = null // already woke via Zipformer KWS
                        }
                    }
                } catch (e: Exception) {
                    TrekoLogBuffer.e(TAG, "Error procesando buffer KWS", e)
                }
            }
        }

        val matcher = dtwMatcher
        val candidateWindow = dtwCandidatePcm
        if (matcher != null && candidateWindow != null) {
            val dtwDist = matcher.bestDistance(candidateWindow, dtwNoiseFloor)
            if (dtwDist <= matcher.threshold) {
                synchronized(lock) {
                    if (!isInitialized) return
                    val activeSpotter = spotter ?: return
                    val activeStream = stream ?: return
                    if (emitVerifiedDetectionLocked(currentKeyword, dtwSnrDb, source = "DTW")) {
                        dtwDetectionsCount++
                        activeSpeechFrames = 0
                        peakSpeechSnrDb = 0f
                        try {
                            activeSpotter.reset(activeStream)
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun emitVerifiedDetectionLocked(
        detectedKeyword: String,
        snrDb: Float,
        source: String
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < DEBOUNCE_MS) {
            return false
        }

        val vp = enrolledVoicePrint
        val similarity = if (vp != null) {
            val window = snapshotRecentPcmLocked()
            vp.similarityToPcm(window, SAMPLE_RATE)
        } else {
            1.0f
        }

        if (speakerStrictness > 0f && vp != null && similarity < speakerStrictness) {
            suppressedBySpeakerGate++
            TrekoLogBuffer.i(
                TAG,
                "Trigger '$detectedKeyword' ($source) filtrado por huella vocal (sim=%.2f < %.2f)".format(
                    java.util.Locale.US, similarity, speakerStrictness
                )
            )
            return false
        }

        lastDetectionTime = now
        TrekoLogBuffer.i(
            TAG,
            "Wake word detectada [$source]: $detectedKeyword (SNR=${snrDb.toInt()}dB, sim=%.2f)".format(
                java.util.Locale.US, similarity
            )
        )
        _detectionEvents.tryEmit(detectedKeyword)
        _detectionDetails.tryEmit(
            WakeWordDetectionEvent(
                keyword = detectedKeyword,
                snrDb = snrDb,
                voicePrintSimilarity = similarity,
                timestampMs = now,
                source = source
            )
        )
        return true
    }

    /**
     * Evalúa offline una lista de buffers PCM grabados (ej. tomas de entrenamiento o ruido ambiente)
     * contra una combinación candidata (keyword, boost, threshold) usando el motor Zipformer cargado.
     * Normaliza la ganancia con el mismo limitador soft-knee hacia 0.12 RMS que el flujo en vivo y
     * libera el lock entre cada toma para evitar inanición en `acceptWaveform`.
     */
    fun evaluateSamples(
        samplesList: List<ShortArray>,
        keyword: String,
        boost: Float,
        threshold: Float
    ): Int {
        if (samplesList.isEmpty()) return 0

        val activeSpotter: KeywordSpotter
        val kwString: String
        val noiseFloorSnapshot: Float
        var evalStream: OnlineStream? = null

        synchronized(lock) {
            activeSpotter = spotter ?: return 0
            kwString = buildKeywordsString(keyword, boost, threshold)
            if (kwString.isBlank()) return 0
            noiseFloorSnapshot = kwsVad.noiseFloorRms
            evalStream = try {
                activeSpotter.createStream(kwString)
            } catch (e: Exception) {
                TrekoLogBuffer.w(TAG, "Error creando stream en evaluateSamples: ${e.message}")
                return 0
            }
        }

        val trailingSilence = FloatArray(4800) // 300ms tail flush
        var hits = 0
        try {
            val streamRef = evalStream ?: return 0
            for (pcm in samplesList) {
                if (pcm.isEmpty()) continue
                // Normalize outside the monitor lock
                val floatBuf = normalizeSpeechToFloat(pcm, noiseFloorSnapshot)

                synchronized(lock) {
                    if (spotter !== activeSpotter || !isInitialized) return hits
                    activeSpotter.reset(streamRef)
                    streamRef.acceptWaveform(floatBuf, SAMPLE_RATE)
                    streamRef.acceptWaveform(trailingSilence, SAMPLE_RATE)

                    while (activeSpotter.isReady(streamRef)) {
                        activeSpotter.decode(streamRef)
                    }
                    val res = activeSpotter.getResult(streamRef)
                    if (res.keyword.isNotBlank()) {
                        hits++
                    }
                }
            }
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Error en evaluateSamples: ${e.message}")
        } finally {
            synchronized(lock) {
                try {
                    evalStream?.release()
                } catch (_: Exception) { }
            }
        }
        return hits
    }

    fun getDiagnostics(): KwsDiagnostics {
        synchronized(lock) {
            val matcher = acousticMatcher
            return KwsDiagnostics(
                isInitialized = isInitialized,
                keyword = currentKeyword,
                boost = currentBoost,
                threshold = currentThreshold,
                vadGatingEnabled = vadGatingEnabled,
                noiseFloorRms = kwsVad.noiseFloorRms,
                hasVoicePrint = enrolledVoicePrint != null,
                speakerStrictness = speakerStrictness,
                totalFramesProcessed = totalFramesProcessed,
                vadSkippedFrames = vadSkippedFrames,
                suppressedBySpeakerGate = suppressedBySpeakerGate,
                hasAcousticMatcher = matcher != null,
                dtwThreshold = matcher?.threshold ?: WakeWordAcousticMatcher.DEFAULT_THRESHOLD,
                dtwDetectionsCount = dtwDetectionsCount
            )
        }
    }

    suspend fun notifyDetected(keyword: String = "Treko") {
        Log.i(TAG, "¡Palabra de activación detectada manualmente: $keyword!")
        _detectionEvents.emit(keyword)
    }

    /**
     * Libera los recursos nativos de Sherpa-ONNX.
     */
    fun release() {
        synchronized(lock) {
            try {
                stream?.release()
                stream = null
                spotter?.release()
                spotter = null
                isInitialized = false
                preRollFilled = 0
                preRollIndex = 0
                recentPcmFilled = 0
                recentPcmIndex = 0
                activeSpeechFrames = 0
                peakSpeechSnrDb = 0f
                wasVadActive = false
                TrekoLogBuffer.i(TAG, "KeywordSpotter liberado.")
            } catch (e: Exception) {
                Log.e(TAG, "Error liberando KeywordSpotter: ${e.message}", e)
            }
        }
    }

    private fun pushPreRollLocked(samples: FloatArray, count: Int) {
        val toPush = minOf(count, PRE_ROLL_SAMPLES)
        val startOffset = count - toPush
        for (i in 0 until toPush) {
            preRollFloatBuffer[preRollIndex] = samples[startOffset + i]
            preRollIndex = (preRollIndex + 1) % PRE_ROLL_SAMPLES
            if (preRollFilled < PRE_ROLL_SAMPLES) preRollFilled++
        }
    }

    private fun flushPreRollToStreamLocked(targetStream: OnlineStream) {
        if (preRollFilled <= 0) return
        val ordered = FloatArray(preRollFilled)
        val startIdx = if (preRollFilled < PRE_ROLL_SAMPLES) 0 else preRollIndex
        for (i in 0 until preRollFilled) {
            ordered[i] = preRollFloatBuffer[(startIdx + i) % PRE_ROLL_SAMPLES]
        }
        targetStream.acceptWaveform(ordered, SAMPLE_RATE)
        preRollFilled = 0
        preRollIndex = 0
    }

    private fun recordRecentPcmLocked(samples: ShortArray, count: Int) {
        val toPush = minOf(count, VOICEPRINT_WINDOW_SAMPLES)
        val startOffset = count - toPush
        for (i in 0 until toPush) {
            recentPcmRing[recentPcmIndex] = samples[startOffset + i]
            recentPcmIndex = (recentPcmIndex + 1) % VOICEPRINT_WINDOW_SAMPLES
            if (recentPcmFilled < VOICEPRINT_WINDOW_SAMPLES) recentPcmFilled++
        }
    }

    private fun snapshotRecentPcmLocked(): ShortArray {
        val out = ShortArray(recentPcmFilled)
        val startIdx = if (recentPcmFilled < VOICEPRINT_WINDOW_SAMPLES) 0 else recentPcmIndex
        for (i in 0 until recentPcmFilled) {
            out[i] = recentPcmRing[(startIdx + i) % VOICEPRINT_WINDOW_SAMPLES]
        }
        return out
    }

    private fun fillSoftKneeNormalized(
        samples: ShortArray,
        count: Int,
        gain: Float,
        dst: FloatArray
    ) {
        for (i in 0 until count) {
            val scaled = (samples[i] / 32768.0f) * gain
            // Soft-knee limiter above 0.85 to preserve formants without hard clipping
            dst[i] = if (kotlin.math.abs(scaled) > 0.85f) {
                tanh(scaled.toDouble()).toFloat()
            } else {
                scaled
            }
        }
    }

    private fun normalizeSpeechToFloat(pcm: ShortArray, noiseFloorRms: Float): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val gate = maxOf(0.012f, noiseFloorRms * 2.0f)
        val frameSize = 320
        var activeSqSum = 0.0
        var activeFrames = 0
        var totalSqSum = 0.0
        for (s in pcm) {
            val f = s / 32768.0
            totalSqSum += f * f
        }
        var pos = 0
        while (pos + frameSize <= pcm.size) {
            var frameSq = 0.0
            for (i in pos until pos + frameSize) {
                val f = pcm[i] / 32768.0
                frameSq += f * f
            }
            val frameRms = kotlin.math.sqrt(frameSq / frameSize).toFloat()
            if (frameRms > gate) {
                activeSqSum += frameSq / frameSize
                activeFrames++
            }
            pos += frameSize
        }
        val speechRms = if (activeFrames > 0) {
            kotlin.math.sqrt(activeSqSum / activeFrames).toFloat()
        } else {
            kotlin.math.sqrt(totalSqSum / pcm.size).toFloat()
        }
        val gain = if (speechRms <= noiseFloorRms * 1.4f) {
            1.0f
        } else {
            (0.12f / maxOf(speechRms, 0.015f)).coerceIn(0.75f, 2.5f)
        }
        val out = FloatArray(pcm.size)
        fillSoftKneeNormalized(pcm, pcm.size, gain, out)
        return out
    }
}
