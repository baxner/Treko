package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.TimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class TimerState(
    val selectedMode: TimerMode = TimerMode.POMODORO,
    val remainingSeconds: Int = TimerMode.POMODORO.seconds,
    val totalSeconds: Int = TimerMode.POMODORO.seconds,
    val isRunning: Boolean = false,
    val completedSessions: Int = 0,
    /** Hora de reloj (epoch ms) en que termina; 0 si está parado. */
    val endsAtEpochMs: Long = 0L
)

data class NamedTimer(
    val id: Int,
    val label: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isRunning: Boolean = true,
    val endsAtEpochMs: Long = 0L
)

/** Programa la alarma que avisa al terminar un temporizador (AlarmManager en la app). */
interface TimerScheduler {
    fun schedule(key: Int, atEpochMs: Long, label: String, totalSeconds: Int)
    fun cancel(key: Int)
}

/** Guarda el estado de los temporizadores para recuperarlo si el proceso muere. */
interface TimerPersistence {
    fun save(encoded: String)
    fun load(): String?

    companion object {
        fun sharedPreferences(context: Context): TimerPersistence {
            val prefs = context.applicationContext.getSharedPreferences("treko_timers", Context.MODE_PRIVATE)
            return object : TimerPersistence {
                override fun save(encoded: String) {
                    prefs.edit().putString("state", encoded).apply()
                }

                override fun load(): String? = prefs.getString("state", null)
            }
        }
    }
}

/**
 * Temporizador principal (Pomodoro / descansos / personalizado) y temporizadores con nombre.
 *
 * La hora de fin se guarda como hora de reloj y cada cambio se persiste, así que el estado
 * sobrevive a que Android mate el proceso. El aviso al terminar no sale de aquí: lo da la
 * alarma de [TimerScheduler] (AlarmManager), que funciona con la app cerrada y en Doze.
 * El contador interno solo refresca la cuenta atrás en pantalla.
 */
object TrekoTimerManager {

    /** Clave de alarma del temporizador principal; los de nombre usan su id (≥ 1). */
    const val MAIN_TIMER_KEY = 0

    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null
    private var nextNamedTimerId: Int = 1
    private var persistence: TimerPersistence? = null
    private var scheduler: TimerScheduler? = null

    /** Reloj inyectable para tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _namedTimers = MutableStateFlow<List<NamedTimer>>(emptyList())
    val namedTimers: StateFlow<List<NamedTimer>> = _namedTimers.asStateFlow()

    /**
     * Conecta persistencia y alarmas. La primera vez recupera el estado guardado y vuelve a
     * programar todas las alarmas (útil tras reiniciar el teléfono). Llamadas siguientes no hacen nada.
     */
    fun attach(persistence: TimerPersistence, scheduler: TimerScheduler) {
        synchronized(lock) {
            if (this.persistence != null) return
            this.persistence = persistence
            this.scheduler = scheduler
            persistence.load()?.let { restoreLocked(it) }
            rescheduleAllLocked()
            ensureTickerLocked()
        }
    }

    /** Solo para tests: olvida persistencia, alarmas y estado. */
    internal fun resetForTests() {
        synchronized(lock) {
            tickerJob?.cancel()
            tickerJob = null
            persistence = null
            scheduler = null
            nextNamedTimerId = 1
            _timerState.value = TimerState()
            _namedTimers.value = emptyList()
            clock = System::currentTimeMillis
        }
    }

    fun start() {
        synchronized(lock) {
            val current = _timerState.value
            if (current.isRunning) return
            val remaining = if (current.remainingSeconds <= 0) current.totalSeconds else current.remainingSeconds
            runMainLocked(current.copy(remainingSeconds = remaining))
        }
    }

    fun pause() {
        synchronized(lock) {
            val current = _timerState.value
            scheduler?.cancel(MAIN_TIMER_KEY)
            _timerState.value = current.copy(
                isRunning = false,
                remainingSeconds = if (current.isRunning) remainingSeconds(current.endsAtEpochMs) else current.remainingSeconds,
                endsAtEpochMs = 0L
            )
            persistLocked()
        }
    }

    fun toggle() {
        synchronized(lock) {
            if (_timerState.value.isRunning) pause() else start()
        }
    }

    fun reset() {
        synchronized(lock) {
            scheduler?.cancel(MAIN_TIMER_KEY)
            val current = _timerState.value
            _timerState.value = current.copy(isRunning = false, remainingSeconds = current.totalSeconds, endsAtEpochMs = 0L)
            persistLocked()
        }
    }

    fun setMode(mode: TimerMode) {
        synchronized(lock) {
            scheduler?.cancel(MAIN_TIMER_KEY)
            _timerState.value = _timerState.value.copy(
                selectedMode = mode,
                totalSeconds = mode.seconds,
                remainingSeconds = mode.seconds,
                isRunning = false,
                endsAtEpochMs = 0L
            )
            persistLocked()
        }
    }

    fun setCustomMinutes(minutes: Int) {
        synchronized(lock) {
            val secs = minutes.coerceAtLeast(1) * 60
            runMainLocked(
                _timerState.value.copy(selectedMode = TimerMode.CUSTOM, totalSeconds = secs, remainingSeconds = secs)
            )
        }
    }

    fun addNamedTimer(minutes: Int = 0, seconds: Int = 0, label: String = ""): NamedTimer {
        val totalSecs = ((minutes.coerceAtLeast(0) * 60) + seconds.coerceAtLeast(0)).coerceAtLeast(1)
        val cleanLabel = label.trim().ifBlank {
            if (totalSecs >= 60 && totalSecs % 60 == 0) "Timer ${totalSecs / 60} min" else "Timer ${totalSecs}s"
        }
        synchronized(lock) {
            val item = NamedTimer(
                id = nextNamedTimerId++,
                label = cleanLabel,
                totalSeconds = totalSecs,
                remainingSeconds = totalSecs,
                isRunning = true,
                endsAtEpochMs = clock() + totalSecs * 1000L
            )
            _namedTimers.value = _namedTimers.value + item
            scheduler?.schedule(item.id, item.endsAtEpochMs, item.label, item.totalSeconds)
            persistLocked()
            ensureTickerLocked()
            return item
        }
    }

    fun cancelNamedTimer(id: Int): Boolean {
        synchronized(lock) {
            val prev = _namedTimers.value
            val next = prev.filterNot { it.id == id }
            if (next.size == prev.size) return false
            _namedTimers.value = next
            scheduler?.cancel(id)
            persistLocked()
            return true
        }
    }

    fun cancelNamedTimerByLabel(query: String): NamedTimer? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        synchronized(lock) {
            val match = _namedTimers.value.firstOrNull { it.label.lowercase(Locale.ROOT).contains(q) } ?: return null
            cancelNamedTimer(match.id)
            return match
        }
    }

    fun cancelAllNamedTimers(): Int {
        synchronized(lock) {
            val current = _namedTimers.value
            current.forEach { scheduler?.cancel(it.id) }
            _namedTimers.value = emptyList()
            persistLocked()
            return current.size
        }
    }

    /**
     * La alarma de [key] sonó. Cierra ese temporizador (suma la sesión Pomodoro si toca) y
     * cancela cualquier alarma duplicada que [attach] hubiera reprogramado.
     * Devuelve true si el temporizador seguía activo.
     */
    fun onAlarmFired(key: Int): Boolean {
        synchronized(lock) {
            scheduler?.cancel(key)
            val changed = if (key == MAIN_TIMER_KEY) {
                val current = _timerState.value
                if (current.isRunning) {
                    finishMainLocked(current)
                    true
                } else {
                    false
                }
            } else {
                val prev = _namedTimers.value
                _namedTimers.value = prev.filterNot { it.id == key }
                prev.size != _namedTimers.value.size
            }
            if (changed) persistLocked()
            return changed
        }
    }

    fun getStatusDescription(): String = describeStatus(_timerState.value, _namedTimers.value)

    /**
     * Text for the model. The Pomodoro is left out while it sits untouched (not running and
     * not paused halfway), so "¿qué temporizadores tengo?" is not answered with it.
     */
    internal fun describeStatus(main: TimerState, active: List<NamedTimer>): String {
        val mainInUse = main.isRunning || main.remainingSeconds in 1 until main.totalSeconds
        val mainLine = if (mainInUse) {
            val stateText = if (main.isRunning) "en marcha" else "en pausa"
            "Pomodoro (${main.selectedMode.title}): quedan ${formatMmSs(main.remainingSeconds)}, $stateText."
        } else {
            null
        }
        if (active.isEmpty()) return mainLine ?: "No hay temporizadores activos."
        val namedLines = active.joinToString("; ") { t -> "${t.label} (${t.id}): quedan ${formatMmSs(t.remainingSeconds)}" }
        val namedText = "Temporizadores activos: $namedLines."
        return if (mainLine != null) "$mainLine $namedText" else namedText
    }

    private fun formatMmSs(totalSeconds: Int) =
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)

    private fun remainingSeconds(endsAtEpochMs: Long): Int =
        ((endsAtEpochMs - clock() + 999L) / 1000L).toInt().coerceAtLeast(0)

    private fun runMainLocked(base: TimerState) {
        val endsAt = clock() + base.remainingSeconds * 1000L
        val next = base.copy(isRunning = true, endsAtEpochMs = endsAt)
        _timerState.value = next
        scheduler?.schedule(MAIN_TIMER_KEY, endsAt, next.selectedMode.title, next.totalSeconds)
        persistLocked()
        ensureTickerLocked()
    }

    private fun finishMainLocked(current: TimerState) {
        val isPomo = current.selectedMode == TimerMode.POMODORO
        _timerState.value = current.copy(
            remainingSeconds = 0,
            isRunning = false,
            endsAtEpochMs = 0L,
            completedSessions = if (isPomo) current.completedSessions + 1 else current.completedSessions
        )
    }

    /** Refresca la cuenta atrás en pantalla. No avisa: eso lo hace la alarma. */
    private fun ensureTickerLocked() {
        if (tickerJob?.isActive == true) return
        if (!_timerState.value.isRunning && _namedTimers.value.isEmpty()) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(250L)
                synchronized(lock) {
                    if (!tickLocked()) {
                        tickerJob = null
                        return@launch
                    }
                }
            }
        }
    }

    /** Un paso del contador; devuelve false cuando ya no queda nada en marcha. */
    private fun tickLocked(): Boolean {
        var finishedSomething = false
        val main = _timerState.value
        if (main.isRunning) {
            val rem = remainingSeconds(main.endsAtEpochMs)
            if (rem <= 0) {
                finishMainLocked(main)
                finishedSomething = true
            } else if (rem != main.remainingSeconds) {
                _timerState.value = main.copy(remainingSeconds = rem)
            }
        }
        val named = _namedTimers.value
        if (named.isNotEmpty()) {
            val updated = named.mapNotNull { t ->
                val rem = remainingSeconds(t.endsAtEpochMs)
                when {
                    rem <= 0 -> null
                    rem != t.remainingSeconds -> t.copy(remainingSeconds = rem)
                    else -> t
                }
            }
            if (updated.size != named.size) finishedSomething = true
            if (updated != named) _namedTimers.value = updated
        }
        if (finishedSomething) persistLocked()
        return _timerState.value.isRunning || _namedTimers.value.isNotEmpty()
    }

    private fun rescheduleAllLocked() {
        val s = scheduler ?: return
        val main = _timerState.value
        if (main.isRunning) s.schedule(MAIN_TIMER_KEY, main.endsAtEpochMs, main.selectedMode.title, main.totalSeconds)
        _namedTimers.value.forEach { s.schedule(it.id, it.endsAtEpochMs, it.label, it.totalSeconds) }
    }

    private fun persistLocked() {
        persistence?.save(encode(_timerState.value, _namedTimers.value, nextNamedTimerId))
    }

    private fun restoreLocked(encoded: String) {
        val decoded = decode(encoded) ?: return
        nextNamedTimerId = maxOf(decoded.nextId, (decoded.named.maxOfOrNull { it.id } ?: 0) + 1)
        val main = decoded.main
        // Un temporizador vencido mientras el proceso estaba muerto se conserva en marcha con
        // 0 s: su alarma (o la que reprograma attach) lo cerrará y avisará.
        _timerState.value = if (main.isRunning) main.copy(remainingSeconds = remainingSeconds(main.endsAtEpochMs)) else main
        _namedTimers.value = decoded.named.map { it.copy(remainingSeconds = remainingSeconds(it.endsAtEpochMs)) }
    }

    internal data class Decoded(val main: TimerState, val named: List<NamedTimer>, val nextId: Int)

    private const val FORMAT_VERSION = "v1"

    /** Formato de texto simple (sin org.json) para poder probarlo en la JVM. */
    internal fun encode(main: TimerState, named: List<NamedTimer>, nextId: Int): String = buildString {
        appendLine(FORMAT_VERSION)
        appendLine("next=$nextId")
        appendLine(
            "main=${main.selectedMode.name}|${main.totalSeconds}|${main.remainingSeconds}|" +
                "${if (main.isRunning) 1 else 0}|${main.endsAtEpochMs}|${main.completedSessions}"
        )
        named.forEach { t ->
            appendLine("t=${t.id}|${t.totalSeconds}|${t.endsAtEpochMs}|${URLEncoder.encode(t.label, "UTF-8")}")
        }
    }

    internal fun decode(encoded: String): Decoded? = try {
        val lines = encoded.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != FORMAT_VERSION) {
            null
        } else {
            var nextId = 1
            var main = TimerState()
            val named = mutableListOf<NamedTimer>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore('=')
                val parts = line.substringAfter('=').split('|')
                when (key) {
                    "next" -> nextId = parts[0].toInt()
                    "main" -> main = TimerState(
                        selectedMode = TimerMode.valueOf(parts[0]),
                        totalSeconds = parts[1].toInt(),
                        remainingSeconds = parts[2].toInt(),
                        isRunning = parts[3] == "1",
                        endsAtEpochMs = parts[4].toLong(),
                        completedSessions = parts[5].toInt()
                    )
                    "t" -> named += NamedTimer(
                        id = parts[0].toInt(),
                        label = URLDecoder.decode(parts[3], "UTF-8"),
                        totalSeconds = parts[1].toInt(),
                        remainingSeconds = 0,
                        isRunning = true,
                        endsAtEpochMs = parts[2].toLong()
                    )
                }
            }
            Decoded(main, named, nextId)
        }
    } catch (_: Exception) {
        null
    }
}
