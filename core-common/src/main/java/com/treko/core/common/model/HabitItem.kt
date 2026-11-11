package com.treko.core.common.model

data class HabitItem(
    val id: Int,
    val title: String,
    val icon: String = "✨",
    var isDone: Boolean = false
)
