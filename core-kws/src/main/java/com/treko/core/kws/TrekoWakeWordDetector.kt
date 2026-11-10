package com.treko.core.kws

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Gestor de detección local de palabras de activación (Wake Word) usando Sherpa-ONNX en Android.
 * Permite inicialización directa desde assets y personalización en caliente de la palabra clave,
 * potencia (/boost) y sensibilidad (threshold) sin recargar los pesos de la red neuronal.
 */
class TrekoWakeWordDetector(private val context: Context) {

    private val TAG = "TrekoKWS"

    private val _detectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val detectionEvents: SharedFlow<String> = _detectionEvents.asSharedFlow()

    @Volatile
    var isInitialized = false
        private set

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var tokenizer: KwsTokenizer? = null

    private var currentKeyword: String = "Treko"
    private var currentBoost: Float = 3.0f
    private var currentThreshold: Float = 0.05f
    private var lastDetectionTime: Long = 0L

    private val lock = Any()

    /**
     * Inicializa el KeywordSpotter nativo directamente desde los assets de la aplicación.
     */
    fun initialize(
        initialKeyword: String = "Treko",
        boost: Float = 3.0f,
        threshold: Float = 0.05f
    ): Boolean {
        synchronized(lock) {
            try {
                if (isInitialized) {
                    updateKeywords(initialKeyword, boost, threshold)
                    return true
                }

                tokenizer = KwsTokenizer.loadFromAssets(context, "models/en/tokens.txt")

                val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
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
                    maxActivePaths = 4,
                    keywordsFile = "models/en/keywords.txt",
                    keywordsScore = boost,
                    keywordsThreshold = threshold,
                    numTrailingBlanks = 1
                )

                val newSpotter = KeywordSpotter(context.assets, config)
                spotter = newSpotter

                val kwString = buildKeywordsString(initialKeyword, boost, threshold)
                stream = newSpotter.createStream(kwString)

                currentKeyword = initialKeyword
                currentBoost = boost
                currentThreshold = threshold
                isInitialized = true

                Log.i(TAG, "Sherpa-ONNX KeywordSpotter inicializado desde assets con palabra '$initialKeyword' (boost=$boost, threshold=$threshold)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando Sherpa-ONNX KeywordSpotter: ${e.message}", e)
                isInitialized = false
                return false
            }
        }
    }

    /**
     * Genera la cadena de configuración KWS en formato Sherpa-ONNX.
     * Si la palabra es 'Treko', expande las variantes fonéticas comunes con el boost y threshold configurados.
     * Para cualquier otra palabra personalizada, utiliza KwsTokenizer para segmentar en subpalabras BPE.
     */
    fun buildKeywordsString(keyword: String, boost: Float, threshold: Float): String {
        val clean = keyword.trim().ifEmpty { "Treko" }
        val lines = mutableListOf<String>()

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
                "▁HE Y ▁T RE K O",
                "▁HE Y ▁T RE CO",
                "▁O Y E ▁T RE K O",
                "▁O Y E ▁T RE CO"
            )
            for (v in variants) {
                lines.add(String.format(java.util.Locale.US, "%s :%.1f #%.2f @TREKO", v, boost, threshold))
            }
        } else {
            val tok = tokenizer ?: KwsTokenizer.loadFromAssets(context)
            val mainLine = tok.formatKeywordLine(clean, boost, threshold)
            if (mainLine.isNotEmpty()) {
                lines.add(mainLine)
            }
            val isHey = clean.startsWith("hey", ignoreCase = true)
            val isOye = clean.startsWith("oye", ignoreCase = true)
            if (!isHey) {
                val heyLine = tok.formatKeywordLine("Hey $clean", boost, threshold, clean)
                if (heyLine.isNotEmpty()) {
                    lines.add(heyLine)
                }
            }
            if (!isOye && !isHey) {
                val oyeLine = tok.formatKeywordLine("Oye $clean", boost, threshold, clean)
                if (oyeLine.isNotEmpty()) {
                    lines.add(oyeLine)
                }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Actualiza la palabra clave, potencia de detección (/boost) y sensibilidad (threshold)
     * en memoria de forma instantánea sin recargar los pesos ONNX.
     */
    fun updateKeywords(newKeyword: String, boost: Float, threshold: Float) {
        synchronized(lock) {
            currentKeyword = newKeyword
            currentBoost = boost
            currentThreshold = threshold

            val activeSpotter = spotter
            if (activeSpotter == null) {
                Log.d(TAG, "KeywordSpotter no inicializado aún, procediendo a inicializar...")
                initialize(newKeyword, boost, threshold)
                return
            }

            val kwString = buildKeywordsString(newKeyword, boost, threshold)
            Log.i(TAG, "Actualizando KWS en caliente ($newKeyword, boost=$boost, threshold=$threshold)")

            try {
                stream?.release()
                stream = activeSpotter.createStream(kwString)
            } catch (e: Exception) {
                Log.e(TAG, "Error recreando OnlineStream con nueva palabra clave: ${e.message}", e)
            }
        }
    }

    /**
     * Procesa un buffer de audio PCM de entrada de 16 kHz mono.
     */
    fun acceptWaveform(samples: ShortArray, read: Int = samples.size) {
        if (read <= 0) return

        val floatSamples = FloatArray(read) { i -> samples[i] / 32768.0f }

        synchronized(lock) {
            if (!isInitialized) return
            val activeSpotter = spotter ?: return
            val activeStream = stream ?: return

            try {
                activeStream.acceptWaveform(floatSamples, 16000)
                while (activeSpotter.isReady(activeStream)) {
                    activeSpotter.decode(activeStream)
                }
                val result = activeSpotter.getResult(activeStream)
                if (result.keyword.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime >= 1500L) {
                        lastDetectionTime = now
                        val detected = result.keyword
                        activeSpotter.reset(activeStream)
                        Log.i(TAG, "¡Wake word detectada por KWS: $detected!")
                        _detectionEvents.tryEmit(detected)
                    } else {
                        activeSpotter.reset(activeStream)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando buffer de audio KWS: ${e.message}", e)
            }
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
                Log.i(TAG, "KeywordSpotter liberado.")
            } catch (e: Exception) {
                Log.e(TAG, "Error liberando KeywordSpotter: ${e.message}", e)
            }
        }
    }
}
