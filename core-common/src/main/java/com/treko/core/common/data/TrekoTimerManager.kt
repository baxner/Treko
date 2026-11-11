package com.treko.core.common.data

import android.content.Context
import com.treko.core.common.model.TimerMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TimerState(
    val selectedMode: TimerMode = TimerMode.POMODORO,
    val remainingSeconds: Int = TimerMode.POMODORO.seconds,
    val totalSeconds: Int = TimerMode.POMODORO.seconds,
    val isRunning: Boolean = false,
    val completedSessions: Int = 0
)

object TrekoTimerManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null

    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    fun start() {
        if (_timerState.value.isRunning) return
        if (_timerState.value.remainingSeconds <= 0) {
            reset()
        }
        _timerState.value = _timerState.value.copy(isRunning = true)
        startTicker()
    }

    fun pause() {
        _timerState.value = _timerState.value.copy(isRunning = false)
        tickerJob?.cancel()
    }

    fun toggle() {
        if (_timerState.value.isRunning) {
            pause()
        } else {
            start()
        }
    }

    fun reset() {
        tickerJob?.cancel()
        val current = _timerState.value
        _timerState.value = current.copy(
            isRunning = false,
            remainingSeconds = current.totalSeconds
        )
    }

    fun setMode(mode: TimerMode) {
        tickerJob?.cancel()
        _timerState.value = _timerState.value.copy(
            selectedMode = mode,
            totalSeconds = mode.seconds,
            remainingSeconds = mode.seconds,
            isRunning = false
        )
    }

    fun setCustomMinutes(minutes: Int) {
        val secs = (minutes.coerceAtLeast(1) * 60)
        tickerJob?.cancel()
        _timerState.value = _timerState.value.copy(
            selectedMode = TimerMode.CUSTOM,
            totalSeconds = secs,
            remainingSeconds = secs,
            isRunning = true
        )
        startTicker()
    }

    fun getStatusDescription(): String {
        val s = _timerState.value
        val mins = s.remainingSeconds / 60
        val secs = s.remainingSeconds % 60
        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
        val stateText = if (s.isRunning) "en progreso" else "pausado"
        return "Temporizador (${s.selectedMode.title}): $timeFormatted restante ($stateText). Sesiones completadas hoy: ${s.completedSessions}."
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_timerState.value.isRunning && _timerState.value.remainingSeconds > 0) {
                delay(1000L)
                val newRemaining = _timerState.value.remainingSeconds - 1
                if (newRemaining <= 0) {
                    val isPomo = _timerState.value.selectedMode == TimerMode.POMODORO
                    val newSessions = if (isPomo) _timerState.value.completedSessions + 1 else _timerState.value.completedSessions
                    _timerState.value = _timerState.value.copy(
                        remainingSeconds = 0,
                        isRunning = false,
                        completedSessions = newSessions
                    )
                    break
                } else {
                    _timerState.value = _timerState.value.copy(remainingSeconds = newRemaining)
                }
            }
        }
    }
}
