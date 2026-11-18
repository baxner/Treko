package com.treko.assistant.service

/**
 * Decide cuándo un turno de conversación lleva demasiado tiempo sin avanzar.
 *
 * Es lógica pura: no programa nada por sí misma. El servicio le informa de cada
 * evento con la hora actual y vuelve a programar su temporizador con [remainingMs].
 * Cada evento rearma el plazo de la fase en la que queda el turno:
 *
 * - [Phase.AWAITING_RESPONSE]: se envió el turno y aún no llega nada ([responseTimeoutMs]).
 * - [Phase.TOOL_RUNNING]: hay herramientas MCP ejecutándose en el teléfono; el plazo es
 *   el de la herramienta más lenta en curso ([slowToolTimeoutMs] o [toolTimeoutMs]).
 * - [Phase.AWAITING_AUDIO]: llegó la respuesta `llm` pero aún no el audio ([audioTimeoutMs]).
 *
 * Tras un vencimiento, [acceptsLateTts] abre una ventana de gracia para reproducir una
 * respuesta tardía de la misma sesión en vez de descartarla.
 */
class ConversationWatchdog(
    private val responseTimeoutMs: Long = 12_000L,
    private val toolTimeoutMs: Long = 20_000L,
    private val slowToolTimeoutMs: Long = 60_000L,
    private val audioTimeoutMs: Long = 7_500L,
    private val lateTtsGraceMs: Long = 20_000L,
    private val slowTools: Set<String> = DEFAULT_SLOW_TOOLS
) {
    enum class Phase { DISARMED, AWAITING_RESPONSE, TOOL_RUNNING, AWAITING_AUDIO }

    private val runningTools = mutableMapOf<Long, String>()
    private var nextToolId = 0L
    private var gotLlm = false
    private var deadlineAtMs = 0L
    private var lastTimeoutAtMs = Long.MIN_VALUE
    private var lastTimeoutSessionId: String? = null

    var phase: Phase = Phase.DISARMED
        private set

    /** Se envió un turno (voz o disparador) y se espera la respuesta del servidor. */
    @Synchronized
    fun onTurnSent(nowMs: Long) {
        runningTools.clear()
        gotLlm = false
        lastTimeoutSessionId = null
        rearm(nowMs)
    }

    /** Empieza una herramienta MCP. Devuelve un id para [onToolFinished]. */
    @Synchronized
    fun onToolStarted(name: String, nowMs: Long): Long {
        val id = nextToolId++
        runningTools[id] = name
        if (phase != Phase.DISARMED) rearm(nowMs)
        return id
    }

    @Synchronized
    fun onToolFinished(id: Long, nowMs: Long) {
        runningTools.remove(id)
        if (phase != Phase.DISARMED) rearm(nowMs)
    }

    /** Llegó texto `llm`: falta el audio. */
    @Synchronized
    fun onLlmReceived(nowMs: Long) {
        if (phase == Phase.DISARMED) return
        gotLlm = true
        rearm(nowMs)
    }

    /** Empezó a sonar la respuesta, o el turno terminó por otra vía. */
    @Synchronized
    fun disarm() {
        phase = Phase.DISARMED
        runningTools.clear()
        gotLlm = false
    }

    /** Milisegundos hasta el vencimiento, o null si no hay plazo armado. */
    @Synchronized
    fun remainingMs(nowMs: Long): Long? =
        if (phase == Phase.DISARMED) null else (deadlineAtMs - nowMs).coerceAtLeast(0L)

    /**
     * Si el plazo venció, desarma, recuerda el momento y la sesión para la ventana de
     * gracia y devuelve true. Si se rearmó entretanto, devuelve false.
     */
    @Synchronized
    fun checkExpired(nowMs: Long, sessionId: String?): Boolean {
        if (phase == Phase.DISARMED || nowMs < deadlineAtMs) return false
        disarm()
        lastTimeoutAtMs = nowMs
        lastTimeoutSessionId = sessionId
        return true
    }

    /** True si llega `tts start` poco después de un vencimiento y en la misma sesión. */
    @Synchronized
    fun acceptsLateTts(nowMs: Long, sessionId: String?): Boolean {
        val timedOutSession = lastTimeoutSessionId ?: return false
        return sessionId == timedOutSession && nowMs - lastTimeoutAtMs in 0..lateTtsGraceMs
    }

    /** Consume la ventana de gracia para que no se reproduzca dos veces. */
    @Synchronized
    fun consumeLateTtsGrace() {
        lastTimeoutSessionId = null
    }

    private fun rearm(nowMs: Long) {
        val timeout = when {
            runningTools.isNotEmpty() -> {
                phase = Phase.TOOL_RUNNING
                if (runningTools.values.any { it in slowTools }) slowToolTimeoutMs else toolTimeoutMs
            }
            gotLlm -> {
                phase = Phase.AWAITING_AUDIO
                audioTimeoutMs
            }
            else -> {
                phase = Phase.AWAITING_RESPONSE
                responseTimeoutMs
            }
        }
        deadlineAtMs = nowMs + timeout
    }

    companion object {
        val DEFAULT_SLOW_TOOLS = setOf("analyze_camera", "view_camera", "play_music_or_radio")
    }
}
