package com.treko.core.common.model

import java.util.UUID

enum class ChatRole { USER, ASSISTANT }

/**
 * Representa un mensaje individual en el hilo conversacional entre el usuario y Treko.
 *
 * [sender] es solo el nombre mostrado (puede ser un nombre personalizado del asistente);
 * la agrupación y el lado de la burbuja dependen de [role].
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val role: ChatRole = roleForSender(sender)
) {
    companion object {
        private val USER_SENDERS = setOf("user", "tú", "tu", "you")

        fun roleForSender(sender: String): ChatRole =
            if (sender.trim().lowercase() in USER_SENDERS) ChatRole.USER else ChatRole.ASSISTANT

        /**
         * Añade [message] a [history]. Las frases seguidas del asistente con menos de
         * [mergeWindowMs] entre ellas se unen en una sola burbuja. Conserva las [maxSize] últimas.
         */
        fun append(
            history: List<ChatMessage>,
            message: ChatMessage,
            mergeWindowMs: Long = 10_000L,
            maxSize: Int = 60
        ): List<ChatMessage> {
            val last = history.lastOrNull()
            if (message.role == ChatRole.ASSISTANT && last != null && last.role == ChatRole.ASSISTANT &&
                message.timestamp - last.timestamp < mergeWindowMs
            ) {
                return history.dropLast(1) + last.copy(text = "${last.text} ${message.text}", timestamp = message.timestamp)
            }
            return (history + message).takeLast(maxSize)
        }
    }
}
