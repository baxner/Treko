package com.treko.core.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PrinterStatusTest {

    private val zone = ZoneId.of("America/Mexico_City")
    private val now = Instant.parse("2026-11-20T19:40:00Z") // 13:40 local

    private fun e(id: String, state: String, unit: String = "") = PrinterStatus.Entity(id, id, state, unit)

    private val printing = listOf(
        e("sensor.octoprint_current_state", "Printing"),
        e("sensor.octoprint_job_percentage", "47.3", "%"),
        e("sensor.octoprint_estimated_finish_time", "2026-11-20T21:00:00+00:00"),
        e("sensor.octoprint_start_time", "2026-11-20T18:10:00+00:00"),
        e("sensor.octoprint_actual_tool0_temp", "209.8", "°C"),
        e("sensor.octoprint_target_tool0_temp", "210", "°C"),
        e("sensor.octoprint_actual_bed_temp", "60.1", "°C"),
        e("sensor.octoprint_target_bed_temp", "60", "°C"),
        e("binary_sensor.octoprint_printing", "on"),
        e("binary_sensor.octoprint_printing_error", "off")
    )

    @Test
    fun `printing job reports progress, finish time and temperatures`() {
        val text = PrinterStatus.summarize(printing, listOf("camera.sala", "camera.octoprint_camera"), now, zone)
        assertTrue(text, text.contains("imprimiendo"))
        assertTrue(text, text.contains("progreso 47%"))
        assertTrue(text, text.contains("empezó a las 12:10"))
        assertTrue(text, text.contains("termina aprox. a las 15:00 (faltan 1 h 20 min)"))
        assertTrue(text, text.contains("boquilla 209/210 °C"))
        assertTrue(text, text.contains("cama 60/60 °C"))
        assertTrue(text, text.contains("camera.octoprint_camera"))
        assertFalse(text, text.contains("ERROR"))
    }

    @Test
    fun `printing error sensor is not mistaken for the printing sensor`() {
        val withError = printing.map {
            if (it.entityId == "binary_sensor.octoprint_printing_error") it.copy(state = "on") else it
        }
        val text = PrinterStatus.summarize(withError, emptyList(), now, zone)
        assertTrue(text, text.contains("ERROR"))
        assertTrue(text, text.contains("imprimiendo"))
    }

    @Test
    fun `idle printer says it is ready and hides stale job data`() {
        val idle = listOf(
            e("sensor.ender3_current_state", "Operational"),
            e("sensor.ender3_job_percentage", "100.0", "%"),
            e("sensor.ender3_estimated_finish_time", "unknown"),
            e("binary_sensor.ender3_printing", "off")
        )
        val text = PrinterStatus.summarize(idle, emptyList(), now, zone)
        assertTrue(text, text.contains("'ender3'"))
        assertTrue(text, text.contains("lista, sin imprimir"))
        assertFalse(text, text.contains("progreso"))
        assertFalse(text, text.contains("termina"))
    }

    @Test
    fun `unavailable printer is reported as off or disconnected`() {
        val off = listOf(
            e("sensor.octoprint_current_state", "unavailable"),
            e("sensor.octoprint_job_percentage", "unavailable"),
            e("binary_sensor.octoprint_printing", "unavailable")
        )
        val text = PrinterStatus.summarize(off, emptyList(), now, zone)
        assertTrue(text, text.contains("no disponible"))
    }

    @Test
    fun `no octoprint entities explains that only the camera is left`() {
        val text = PrinterStatus.summarize(listOf(e("sensor.temperatura_sala", "22")), emptyList(), now, zone)
        assertTrue(text, text.startsWith("No encontré sensores de OctoPrint"))
    }

    @Test
    fun `default naming fallback only matches octoprint entities`() {
        assertTrue(PrinterStatus.looksLikeOctoPrint("sensor.octoprint_job_percentage"))
        assertFalse(PrinterStatus.looksLikeOctoPrint("sensor.sala_temperature"))
    }
}
