package com.treko.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepIntentDetectorTest {

    private val detector = SleepIntentDetector(assistantNames = listOf("Treko"))

    @Test
    fun recognizesGoodbyes() {
        listOf(
            "adiós",
            "Adiós, Treko.",
            "buenas noches Treko",
            "eso es todo, gracias",
            "cállate",
            "nada más",
            "ya, gracias, eso es todo",
            "bueno, hasta luego",
            "silencio",
            "Treko, descansa",
            "that's all, thanks",
            "good night"
        ).forEach { assertTrue("debería despedirse: '$it'", detector.isSleepIntent(it)) }
    }

    @Test
    fun ignoresSentencesThatOnlyContainAGoodbyeWord() {
        listOf(
            "pon el celular en silencio",
            "música para descansar",
            "estoy inquieto",
            "no hay nada más que hacer",
            "¿qué hay de nuevo? nada más quería saber el clima de mañana",
            "pon modo silencio en la sala",
            "descansa la cámara del patio",
            "keep quiet mode on",
            "gracias",
            "Treko",
            ""
        ).forEach { assertFalse("no debería despedirse: '$it'", detector.isSleepIntent(it)) }
    }

    @Test
    fun customAssistantNameIsTreatedAsFiller() {
        val custom = SleepIntentDetector(assistantNames = listOf("Luna Bot"))
        assertTrue(custom.isSleepIntent("adiós Luna Bot"))
        assertTrue(custom.isSleepIntent("Luna, silencio"))
    }

    @Test
    fun normalizeKeepsEnye() {
        assertTrue(SleepIntentDetector.tokenize("Mañana").contains("mañana"))
    }
}
