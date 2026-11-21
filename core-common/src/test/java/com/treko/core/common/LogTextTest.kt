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
}
