package com.treko.core.common.model

/**
 * Nivel de audio en tiempo real y bandas de ecualización calculadas a partir del PCM crudo.
 */
data class AudioLevel(
    val rms: Float = 0f,              // 0.0 a 1.0 (amplitud RMS general)
    val isSpeaking: Boolean = false,  // VAD flag
    val bands: FloatArray = FloatArray(5) { 0f } // 5 barras de frecuencia para ecualizador
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioLevel
        return rms == other.rms && isSpeaking == other.isSpeaking && bands.contentEquals(other.bands)
    }

    override fun hashCode(): Int {
        var result = rms.hashCode()
        result = 31 * result + isSpeaking.hashCode()
        result = 31 * result + bands.contentHashCode()
        return result
    }
}
