package com.treko.core.common.model

data class HabitItem(
    val id: Int,
    val title: String,
    val icon: String = "✨",
    var isDone: Boolean = false,
    val lastCompletedDate: String? = null,
    val completedDates: List<String> = emptyList()
)
