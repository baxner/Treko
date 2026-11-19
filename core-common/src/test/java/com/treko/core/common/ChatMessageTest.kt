package com.treko.core.common

import com.treko.core.common.model.ChatMessage
import com.treko.core.common.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageTest {

    private fun msg(sender: String, text: String, t: Long) = ChatMessage(sender = sender, text = text, timestamp = t)

    @Test
    fun roleComesFromSender() {
        assertEquals(ChatRole.USER, msg("user", "hola", 0).role)
        assertEquals(ChatRole.USER, msg("Tú", "hola", 0).role)
        assertEquals(ChatRole.ASSISTANT, msg("Luna", "hola", 0).role)
        assertEquals(ChatRole.ASSISTANT, msg("Treko", "hola", 0).role)
    }

    @Test
    fun consecutiveAssistantSentencesMergeEvenWithCustomName() {
        var h = ChatMessage.append(emptyList(), msg("Luna", "Hola.", 0))
        h = ChatMessage.append(h, msg("Luna", "¿Qué tal?", 2_000))
        assertEquals(1, h.size)
        assertEquals("Hola. ¿Qué tal?", h.single().text)
    }

    @Test
    fun userMessagesAndOldAssistantMessagesDoNotMerge() {
        var h = ChatMessage.append(emptyList(), msg("user", "uno", 0))
        h = ChatMessage.append(h, msg("user", "dos", 1_000))
        h = ChatMessage.append(h, msg("Treko", "tres", 2_000))
        h = ChatMessage.append(h, msg("Treko", "cuatro", 20_000))
        assertEquals(listOf("uno", "dos", "tres", "cuatro"), h.map { it.text })
    }

    @Test
    fun historyIsCapped() {
        var h = emptyList<ChatMessage>()
        repeat(70) { h = ChatMessage.append(h, msg("user", "m$it", it.toLong())) }
        assertEquals(60, h.size)
        assertEquals("m69", h.last().text)
    }
}
