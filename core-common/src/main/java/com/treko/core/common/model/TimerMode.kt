package com.treko.core.common.model

enum class TimerMode(val title: String, val seconds: Int) {
    POMODORO("Pomodoro (25m)", 25 * 60),
    SHORT_BREAK("Descanso (5m)", 5 * 60),
    LONG_BREAK("Descanso Largo (15m)", 15 * 60),
    CUSTOM("Personalizado", 10 * 60)
}
