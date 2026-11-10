package com.treko.core.common.model

import java.util.UUID

/**
 * Representa un mensaje individual en el hilo conversacional entre el usuario y Treko.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "Tú" o "Treko"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
