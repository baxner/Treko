package com.treko.core.common

import com.treko.core.common.util.LogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTextTest {

    @Test
    fun previewCollapsesToOneLineAndCuts() {
        assertEquals("a b c", LogText.preview("a\n  b\tc ", 300))
        val long = "x".repeat(400)
        val cut = LogText.preview(long, 300)
        assertEquals(301, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("", LogText.preview(null, 10))
    }

    @Test
    fun previewMasksCredentials() {
        val text = "Entidad: Cámara\n  - access_token: 0123456789abcdef0123\n" +
            "url /api/camera_proxy/camera.x?token=abcdef123456&w=1 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"
        val out = LogText.preview(text, 500)
        assertFalse(out.contains("0123456789abcdef0123"))
        assertFalse(out.contains("abcdef123456"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(out.contains("access_token: ***"))
        assertTrue(out.contains("&w=1"))
    }

    @Test
    fun ordinaryTextIsKept() {
        val text = "Luz de la sala encendida. Temperatura 22 °C."
        assertEquals(text, LogText.preview(text, 300))
    }

    @Test
    fun personalToolResultsLogOnlyTheirSize() {
        val notes = "comida_favorita: tacos; cumple_mama: 3 de mayo"
        val logged = LogText.toolResult("manage_vault_memory", notes, 300)
        assertEquals("${notes.length} chars", logged)
        assertFalse(logged.contains("tacos"))
        assertEquals("error", LogText.toolResult("manage_vault_memory", "Error: clave vacía", 300))
        assertEquals("<no result>", LogText.toolResult("manage_vault_memory", null, 300))
        // Other tools keep the masked preview.
        assertEquals("Luz de la sala encendida.", LogText.toolResult("home_assistant_control", "Luz de la sala encendida.", 300))
        assertEquals("<no result>", LogText.toolResult("home_assistant_control", "", 300))
    }
}
