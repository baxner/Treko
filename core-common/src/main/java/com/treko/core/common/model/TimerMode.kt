package com.treko.core.common.model

import java.util.Locale

enum class TimerMode(val title: String, val seconds: Int) {
    POMODORO("Pomodoro (25m)", 25 * 60),
    SHORT_BREAK("Descanso (5m)", 5 * 60),
    LONG_BREAK("Descanso Largo (15m)", 15 * 60),
    CUSTOM("Personalizado", 10 * 60);

    fun localizedTitle(isSpanish: Boolean = Locale.getDefault().language == "es"): String {
        return if (isSpanish) {
            title
        } else {
            when (this) {
                POMODORO -> "Pomodoro (25m)"
                SHORT_BREAK -> "Short Break (5m)"
                LONG_BREAK -> "Long Break (15m)"
                CUSTOM -> "Custom"
            }
        }
    }
}
