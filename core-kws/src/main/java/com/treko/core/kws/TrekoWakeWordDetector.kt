package com.treko.core.kws

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Gestor de detección de la palabra de activación "Treko" usando Sherpa-onnx en Android.
 */
class TrekoWakeWordDetector(private val context: Context) {

    private val TAG = "TrekoKWS"
    private val _detectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val detectionEvents: SharedFlow<String> = _detectionEvents.asSharedFlow()

    private var isInitialized = false
    private var modelDir: File? = null

    /**
     * Copia los modelos de ONNX desde assets a almacenamiento interno seguro si es necesario.
     */
    fun initialize(threshold: Float = 0.05f, score: Float = 3.0f): Boolean {
        try {
            val targetDir = File(context.filesDir, "models/en")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val requiredFiles = listOf(
                "encoder.onnx", "decoder.onnx", "joiner.onnx",
                "tokens.txt", "keywords.txt"
            )

            for (fileName in requiredFiles) {
                val targetFile = File(targetDir, fileName)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    context.assets.open("models/en/$fileName").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copiado $fileName a almacenamiento interno.")
                }
            }

            modelDir = targetDir
            isInitialized = true
            Log.i(TAG, "Detector KWS de Treko inicializado con éxito en: ${targetDir.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando detector KWS: ${e.message}", e)
            return false
        }
    }

    /**
     * Procesa un buffer de audio PCM de entrada.
     */
    fun acceptWaveform(samples: ShortArray) {
        if (!isInitialized) return
        // Aquí interactúa con sherpa-onnx KWS stream nativo
    }

    suspend fun notifyDetected(keyword: String = "Treko") {
        Log.i(TAG, "¡Palabra de activación detectada: $keyword!")
        _detectionEvents.emit(keyword)
    }
}
