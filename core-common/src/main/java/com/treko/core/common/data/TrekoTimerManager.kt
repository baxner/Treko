package com.treko.core.common.data

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
import java.util.Locale

data class TimerState(
    val selectedMode: TimerMode = TimerMode.POMODORO,
    val remainingSeconds: Int = TimerMode.POMODORO.seconds,
    val totalSeconds: Int = TimerMode.POMODORO.seconds,
    val isRunning: Boolean = false,
    val completedSessions: Int = 0
)

data class NamedTimer(
    val id: Int,
    val label: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isRunning: Boolean = true,
    val endElapsedRealtimeMs: Long = 0L
)

object TrekoTimerManager {

    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null
    private var namedTickerJob: Job? = null
    private var activeRunId: Long = 0L
    private var nextNamedTimerId: Int = 1

    /** Invoked on a background thread when either the main timer or any named timer reaches 0. */
    @Volatile
    var onTimerFinished: ((label: String, totalSeconds: Int) -> Unit)? = null

    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _namedTimers = MutableStateFlow<List<NamedTimer>>(emptyList())
    val namedTimers: StateFlow<List<NamedTimer>> = _namedTimers.asStateFlow()

    fun start() {
        synchronized(lock) {
            val current = _timerState.value
            if (current.isRunning) return
            val targetRemaining = if (current.remainingSeconds <= 0) current.totalSeconds else current.remainingSeconds
            _timerState.value = current.copy(
                remainingSeconds = targetRemaining,
                isRunning = true
            )
            startTickerLocked(targetRemaining)
        }
    }

    fun pause() {
        synchronized(lock) {
            activeRunId++
            tickerJob?.cancel()
            tickerJob = null
            _timerState.value = _timerState.value.copy(isRunning = false)
        }
    }

    fun toggle() {
        synchronized(lock) {
            if (_timerState.value.isRunning) {
                pause()
            } else {
                start()
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            activeRunId++
            tickerJob?.cancel()
            tickerJob = null
            val current = _timerState.value
            _timerState.value = current.copy(
                isRunning = false,
                remainingSeconds = current.totalSeconds
            )
        }
    }

    fun setMode(mode: TimerMode) {
        synchronized(lock) {
            activeRunId++
            tickerJob?.cancel()
            tickerJob = null
            _timerState.value = _timerState.value.copy(
                selectedMode = mode,
                totalSeconds = mode.seconds,
                remainingSeconds = mode.seconds,
                isRunning = false
            )
        }
    }

    fun setCustomMinutes(minutes: Int) {
        synchronized(lock) {
            val secs = minutes.coerceAtLeast(1) * 60
            _timerState.value = _timerState.value.copy(
                selectedMode = TimerMode.CUSTOM,
                totalSeconds = secs,
                remainingSeconds = secs,
                isRunning = true
            )
            startTickerLocked(secs)
        }
    }

    fun addNamedTimer(minutes: Int = 0, seconds: Int = 0, label: String = ""): NamedTimer {
        val totalSecs = ((minutes.coerceAtLeast(0) * 60) + seconds.coerceAtLeast(0)).coerceAtLeast(1)
        val cleanLabel = label.trim().ifBlank {
            if (totalSecs >= 60 && totalSecs % 60 == 0) {
                "Timer ${totalSecs / 60} min"
            } else {
                "Timer ${totalSecs}s"
            }
        }
        synchronized(lock) {
            val id = nextNamedTimerId++
            val endMs = System.nanoTime() / 1_000_000L + totalSecs * 1000L
            val item = NamedTimer(
                id = id,
                label = cleanLabel,
                totalSeconds = totalSecs,
                remainingSeconds = totalSecs,
                isRunning = true,
                endElapsedRealtimeMs = endMs
            )
            _namedTimers.value = _namedTimers.value + item
            ensureNamedTickerLocked()
            return item
        }
    }

    fun cancelNamedTimer(id: Int): Boolean {
        synchronized(lock) {
            val prev = _namedTimers.value
            val next = prev.filterNot { it.id == id }
            if (next.size == prev.size) return false
            _namedTimers.value = next
            if (next.isEmpty()) {
                namedTickerJob?.cancel()
                namedTickerJob = null
            }
            return true
        }
    }

    fun cancelNamedTimerByLabel(query: String): NamedTimer? {
        val q = query.trim().lowercase(Locale.ROOT)
        synchronized(lock) {
            val match = _namedTimers.value.firstOrNull {
                q.isNotBlank() && it.label.lowercase(Locale.ROOT).contains(q)
            } ?: return null
            _namedTimers.value = _namedTimers.value.filterNot { it.id == match.id }
            if (_namedTimers.value.isEmpty()) {
                namedTickerJob?.cancel()
                namedTickerJob = null
            }
            return match
        }
    }

    fun cancelAllNamedTimers(): Int {
        synchronized(lock) {
            val count = _namedTimers.value.size
            _namedTimers.value = emptyList()
            namedTickerJob?.cancel()
            namedTickerJob = null
            return count
        }
    }

    private fun ensureNamedTickerLocked() {
        if (namedTickerJob?.isActive == true) return
        namedTickerJob = scope.launch {
            while (isActive) {
                delay(250L)
                val nowMs = System.nanoTime() / 1_000_000L
                val finishedList = mutableListOf<NamedTimer>()
                var keepRunning = false
                synchronized(lock) {
                    val current = _namedTimers.value
                    if (current.isEmpty()) {
                        namedTickerJob = null
                        return@launch
                    }
                    val updated = ArrayList<NamedTimer>(current.size)
                    for (t in current) {
                        if (!t.isRunning) {
                            updated.add(t)
                            continue
                        }
                        val rem = ((t.endElapsedRealtimeMs - nowMs + 999L) / 1000L).toInt().coerceAtLeast(0)
                        if (rem <= 0) {
                            finishedList.add(t.copy(remainingSeconds = 0, isRunning = false))
                        } else {
                            updated.add(t.copy(remainingSeconds = rem))
                            keepRunning = true
                        }
                    }
                    _namedTimers.value = updated
                    if (updated.isEmpty() || !keepRunning) {
                        namedTickerJob = null
                    }
                }
                for (done in finishedList) {
                    onTimerFinished?.invoke(done.label, done.totalSeconds)
                }
                if (!keepRunning) break
            }
        }
    }

    fun getStatusDescription(): String {
        val s = _timerState.value
        val mins = s.remainingSeconds / 60
        val secs = s.remainingSeconds % 60
        val timeFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)
        val stateText = if (s.isRunning) "en progreso" else "pausado"
        val mainLine = "Temporizador principal (${s.selectedMode.title}): $timeFormatted restante ($stateText)."
        val active = _namedTimers.value
        if (active.isEmpty()) return mainLine
        val namedLines = active.joinToString("\n") { t ->
            val m = t.remainingSeconds / 60
            val sec = t.remainingSeconds % 60
            "• [${t.id}] ${t.label}: ${String.format(Locale.US, "%02d:%02d", m, sec)} restante"
        }
        return "$mainLine\nTemporizadores activos (${active.size}):\n$namedLines"
    }

    private fun startTickerLocked(initialRemainingSeconds: Int) {
        val runId = ++activeRunId
        tickerJob?.cancel()
        val startNano = System.nanoTime()

        tickerJob = scope.launch {
            var lastReportedRemaining = initialRemainingSeconds
            while (isActive) {
                delay(250L)
                val elapsedSeconds = ((System.nanoTime() - startNano) / 1_000_000_000L).toInt()
                val newRemaining = (initialRemainingSeconds - elapsedSeconds).coerceAtLeast(0)
                if (newRemaining == lastReportedRemaining) continue
                lastReportedRemaining = newRemaining

                var shouldStop = false
                var finishedLabel: String? = null
                var finishedTotal = 0
                synchronized(lock) {
                    if (runId != activeRunId || !_timerState.value.isRunning) {
                        shouldStop = true
                    } else if (newRemaining <= 0) {
                        val current = _timerState.value
                        val isPomo = current.selectedMode == TimerMode.POMODORO
                        val newSessions = if (isPomo) current.completedSessions + 1 else current.completedSessions
                        _timerState.value = current.copy(
                            remainingSeconds = 0,
                            isRunning = false,
                            completedSessions = newSessions
                        )
                        finishedLabel = current.selectedMode.title
                        finishedTotal = current.totalSeconds
                        shouldStop = true
                    } else {
                        _timerState.value = _timerState.value.copy(remainingSeconds = newRemaining)
                    }
                }
                if (finishedLabel != null) {
                    onTimerFinished?.invoke(finishedLabel!!, finishedTotal)
                }
                if (shouldStop) break
            }
        }
    }
}
