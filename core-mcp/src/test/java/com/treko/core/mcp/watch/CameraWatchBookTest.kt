package com.treko.core.mcp.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWatchBookTest {

    private class MemoryPersistence : WatchPersistence {
        var stored: String? = null
        override fun save(encoded: String) { stored = encoded }
        override fun load(): String? = stored
    }

    private val hour = 60 * 60_000L

    @Test
    fun `alerts once per visitor thanks to the cooldown`() {
        val book = CameraWatchBook(MemoryPersistence())
        book.add(listOf("binary_sensor.jardin_person_occupancy"), listOf("Jardín"), "person", 12 * hour, once = false, nowMs = 0)
        assertNotNull(book.onSensorActivated("binary_sensor.jardin_person_occupancy", 1_000))
        assertNull(book.onSensorActivated("binary_sensor.jardin_person_occupancy", 60_000))
        assertNotNull(book.onSensorActivated("binary_sensor.jardin_person_occupancy", 1_000 + CameraWatchBook.DEFAULT_COOLDOWN_MS))
    }

    @Test
    fun `once watch stops after the first alert`() {
        val book = CameraWatchBook(MemoryPersistence())
        book.add(listOf("s.a"), listOf("Frente"), "person", hour, once = true, nowMs = 0)
        assertNotNull(book.onSensorActivated("s.a", 10))
        assertTrue(book.active(20).isEmpty())
    }

    @Test
    fun `watches expire and are capped at 12 hours`() {
        val book = CameraWatchBook(MemoryPersistence())
        val w = book.add(listOf("s.a"), listOf("Frente"), "person", 48 * hour, once = false, nowMs = 0)
        assertEquals(12 * hour, w.expiresAtMs)
        assertNull(book.onSensorActivated("s.a", 12 * hour + 1))
        assertTrue(book.active(12 * hour + 1).isEmpty())
    }

    @Test
    fun `stop by place leaves the others`() {
        val book = CameraWatchBook(MemoryPersistence())
        book.add(listOf("s.jardin"), listOf("Jardín"), "person", hour, false, 0)
        book.add(listOf("s.frente"), listOf("Frente Casa"), "person", hour, false, 0)
        assertEquals(1, book.stop(listOf("jardin")).size)
        assertEquals(setOf("s.frente"), book.watchedSensorIds(1))
        assertEquals(1, book.stop(emptyList()).size)
        assertTrue(book.active(1).isEmpty())
    }

    @Test
    fun `stop by id ends only that watch and survives a restart`() {
        val storage = MemoryPersistence()
        val book = CameraWatchBook(storage)
        val first = book.add(listOf("s.jardin"), listOf("Jardín"), "person", hour, false, 0)
        book.add(listOf("s.frente"), listOf("Frente"), "car", hour, false, 0)
        assertEquals(first, book.stopById(first.id))
        assertNull(book.stopById(first.id))
        assertEquals(setOf("s.frente"), book.watchedSensorIds(1))
        assertEquals(setOf("s.frente"), CameraWatchBook(storage).watchedSensorIds(1))
    }

    @Test
    fun `watches survive a restart`() {
        val storage = MemoryPersistence()
        CameraWatchBook(storage).add(listOf("s.a|b", "s.c"), listOf("Jardín, atrás"), "car", hour, true, 5)
        val restored = CameraWatchBook(storage).active(10).single()
        assertEquals(listOf("s.a|b", "s.c"), restored.sensorIds)
        assertEquals(listOf("Jardín, atrás"), restored.areaNames)
        assertEquals("car", restored.objectGroup)
        assertTrue(restored.once)
    }
}
