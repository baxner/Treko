package com.treko.core.common.model

data class TaskItem(
    val id: Int,
    val title: String,
    var isDone: Boolean = false
)
