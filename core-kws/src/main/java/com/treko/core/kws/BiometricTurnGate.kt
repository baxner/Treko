package com.treko.core.kws

/**
 * Máquina de estados pura (sin dependencias Android) para la compuerta biométrica por turno en LISTENING.
 *
 * Acumula paquetes Opus en pre-verificación hasta juntar ~0.80s de voz activa, evalúa si coincide
 * con la huella biométrica del propietario y decide entre:
 * - VERIFIED: abrir sesión de escucha en Xiaozhi y volcar paquetes retenidos + streaming continuo.
 * - COLLECTING (reintento en frontera): esperar hasta 1.40s si el score inicial estuvo cerca del umbral.
 * - REJECTED: descartar audio ajeno (ej. TV/terceros) localmente sin enviarlo al servidor.
 */
class BiometricTurnGate {

    enum class TurnAuthState {
        DISABLED,
        COLLECTING,
        CHECKING,
        VERIFIED,
        REJECTED
    }

    enum class CheckOutcome {
        VERIFIED,
        NEED_MORE_SPEECH,
        REJECTED
    }

    private val lock = Any()
    private val pcmChunks = ArrayList<ShortArray>(32)
    private val pendingOpus = ArrayDeque<ByteArray>(MAX_PENDING_OPUS_PACKETS)
    private var totalVoicedSamples = 0
    private var retriedOnce = false

    @Volatile
    var state: TurnAuthState = TurnAuthState.DISABLED
        private set

    fun startTurn(enabled: Boolean) {
        synchronized(lock) {
            pcmChunks.clear()
            pendingOpus.clear()
            totalVoicedSamples = 0
            retriedOnce = false
            state = if (enabled) TurnAuthState.COLLECTING else TurnAuthState.DISABLED
        }
    }

    fun reset() {
        synchronized(lock) {
            pcmChunks.clear()
            pendingOpus.clear()
            totalVoicedSamples = 0
            retriedOnce = false
            state = TurnAuthState.DISABLED
        }
    }

    fun onSpeechPcm(pcmFrames: List<ShortArray>) {
        if (pcmFrames.isEmpty()) return
        synchronized(lock) {
            if (state != TurnAuthState.COLLECTING && state != TurnAuthState.CHECKING) return
            for (frame in pcmFrames) {
                if (frame.isEmpty()) continue
                if (totalVoicedSamples < MAX_VOICED_BUFFER_SAMPLES) {
                    val remaining = MAX_VOICED_BUFFER_SAMPLES - totalVoicedSamples
                    val toAdd = if (frame.size <= remaining) frame else frame.copyOf(remaining)
                    pcmChunks.add(toAdd)
                    totalVoicedSamples += toAdd.size
                }
            }
        }
    }

    /**
     * Si el turno aún no está verificado, retiene los paquetes Opus en cola local y devuelve lista vacía.
     * Una vez VERIFIED o DISABLED, devuelve los paquetes directamente para envío inmediato.
     */
    fun bufferOrPassOpus(packets: List<ByteArray>): List<ByteArray> {
        if (packets.isEmpty()) return emptyList()
        synchronized(lock) {
            return when (state) {
                TurnAuthState.DISABLED, TurnAuthState.VERIFIED -> packets
                TurnAuthState.REJECTED -> emptyList()
                TurnAuthState.COLLECTING, TurnAuthState.CHECKING -> {
                    for (pkt in packets) {
                        if (pendingOpus.size >= MAX_PENDING_OPUS_PACKETS) {
                            pendingOpus.removeFirst()
                        }
                        pendingOpus.addLast(pkt)
                    }
                    emptyList()
                }
            }
        }
    }

    fun drainPendingOpus(): List<ByteArray> {
        synchronized(lock) {
            if (pendingOpus.isEmpty()) return emptyList()
            val out = pendingOpus.toList()
            pendingOpus.clear()
            return out
        }
    }

    fun voicedSamplesCount(): Int = synchronized(lock) { totalVoicedSamples }

    /**
     * Devuelve una instantánea PCM lista para verificación biométrica si se alcanzó el umbral de muestra,
     * o null si aún no toca verificar / ya hay verificación en curso.
     */
    fun pollSnapshotForCheck(silenceDurationMs: Long, isTurnEnding: Boolean): ShortArray? {
        synchronized(lock) {
            if (state != TurnAuthState.COLLECTING) return null

            val minTarget = if (!retriedOnce) MIN_VERIFY_SAMPLES else BORDERLINE_RETRY_SAMPLES
            val hitNormalTarget = totalVoicedSamples >= minTarget
            val hitShortPhraseTrigger = !retriedOnce &&
                totalVoicedSamples >= MIN_FINALIZE_SAMPLES &&
                (silenceDurationMs >= EARLY_CHECK_SILENCE_MS || isTurnEnding)
            val hitRetryOnEnd = retriedOnce &&
                totalVoicedSamples >= MIN_FINALIZE_SAMPLES &&
                isTurnEnding

            if (!hitNormalTarget && !hitShortPhraseTrigger && !hitRetryOnEnd) {
                return null
            }

            state = TurnAuthState.CHECKING
            return flattenVoicedLocked()
        }
    }

    fun applyCheckResult(
        similarity: Float,
        threshold: Float,
        isTurnEnding: Boolean
    ): CheckOutcome {
        synchronized(lock) {
            if (state != TurnAuthState.CHECKING) {
                return when (state) {
                    TurnAuthState.VERIFIED -> CheckOutcome.VERIFIED
                    TurnAuthState.REJECTED -> CheckOutcome.REJECTED
                    else -> CheckOutcome.NEED_MORE_SPEECH
                }
            }

            // Slight tolerance only on short finalized phrases (<0.80s)
            val effectiveThreshold = if (isTurnEnding && totalVoicedSamples < MIN_VERIFY_SAMPLES) {
                (threshold - SHORT_UTTERANCE_GRACE).coerceAtLeast(0.35f)
            } else {
                threshold
            }

            if (similarity >= effectiveThreshold) {
                state = TurnAuthState.VERIFIED
                return CheckOutcome.VERIFIED
            }

            val isBorderline = similarity >= (effectiveThreshold - BORDERLINE_BAND)
            if (!isTurnEnding && !retriedOnce && isBorderline && totalVoicedSamples < BORDERLINE_RETRY_SAMPLES) {
                retriedOnce = true
                state = TurnAuthState.COLLECTING
                return CheckOutcome.NEED_MORE_SPEECH
            }

            state = TurnAuthState.REJECTED
            pendingOpus.clear()
            pcmChunks.clear()
            return CheckOutcome.REJECTED
        }
    }

    /**
     * Resuelve un turno que terminó antes de juntar [MIN_FINALIZE_SAMPLES] (~0.45s),
     * p. ej. un comando de una sola palabra ("Sí", "Para") vs ruido mínimo en seguimiento automático.
     */
    fun finalizeUltraShortTurn(strictBlockShortAmbient: Boolean): CheckOutcome {
        synchronized(lock) {
            if (state == TurnAuthState.VERIFIED || state == TurnAuthState.DISABLED) {
                return CheckOutcome.VERIFIED
            }
            if (state == TurnAuthState.REJECTED) {
                return CheckOutcome.REJECTED
            }
            return if (strictBlockShortAmbient) {
                state = TurnAuthState.REJECTED
                pendingOpus.clear()
                pcmChunks.clear()
                CheckOutcome.REJECTED
            } else {
                state = TurnAuthState.VERIFIED
                CheckOutcome.VERIFIED
            }
        }
    }

    private fun flattenVoicedLocked(): ShortArray {
        val out = ShortArray(totalVoicedSamples)
        var offset = 0
        for (chunk in pcmChunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MIN_FINALIZE_SAMPLES = 7_200        // 0.45s
        const val MIN_VERIFY_SAMPLES = 12_800         // 0.80s
        const val BORDERLINE_RETRY_SAMPLES = 22_400   // 1.40s
        const val MAX_VOICED_BUFFER_SAMPLES = 48_000  // 3.0s
        const val MAX_PENDING_OPUS_PACKETS = 50       // 3.0s at 60ms/packet
        const val EARLY_CHECK_SILENCE_MS = 320L
        const val BORDERLINE_BAND = 0.08f
        const val SHORT_UTTERANCE_GRACE = 0.04f
    }
}
