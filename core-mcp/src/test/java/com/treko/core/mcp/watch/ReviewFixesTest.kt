package com.treko.core.mcp.watch

import com.treko.core.mcp.PrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases found by the phase 7 code review. */
class ReviewFixesTest {

    @Test
    fun `transitions report off to on once, and a sensor already on when first seen`() {
        val t = SensorTransitions()
        // Initial snapshot: one sensor already on (someone is there now).
        assertEquals(listOf("b.a"), t.apply(mapOf("b.a" to "on", "b.b" to "off")))
        // Reconnect snapshot with the same states: nothing new.
        assertEquals(emptyList<String>(), t.apply(mapOf("b.a" to "on", "b.b" to "off")))
        // b.b turned on while the connection was down: reported after the reconnect.
        assertEquals(listOf("b.b"), t.apply(mapOf("b.a" to "on", "b.b" to "on")))
        assertEquals(emptyList<String>(), t.apply(mapOf("b.a" to "off")))
        assertEquals(listOf("b.a"), t.apply(mapOf("b.a" to "on")))
    }

    @Test
    fun `frigate labels beyond the common ones are understood`() {
        assertEquals("entrada" to "fedex", WatchCatalog.parseFrigateOccupancy("binary_sensor.entrada_fedex_occupancy"))
        assertEquals("patio" to "waste_bin", WatchCatalog.parseFrigateOccupancy("binary_sensor.patio_waste_bin_occupancy"))
        // Unknown label: only when Home Assistant says the entity is Frigate's.
        assertEquals(null, WatchCatalog.parseFrigateOccupancy("binary_sensor.patio_wombat_occupancy"))
        assertEquals("patio" to "wombat", WatchCatalog.parseFrigateOccupancy("binary_sensor.patio_wombat_occupancy", allowUnknownLabel = true))
    }

    @Test
    fun `any object uses frigate's all sensor`() {
        val catalog = listOf(
            WatchSensor("b.patio_person_occupancy", "Patio", "patio", "person", WatchSensor.Source.FRIGATE_CAMERA, "camera.patio", null),
            WatchSensor("b.patio_all_occupancy", "Patio", "patio", "all", WatchSensor.Source.FRIGATE_CAMERA, "camera.patio", null)
        )
        assertEquals(listOf("b.patio_all_occupancy"), WatchCatalog.resolve(catalog, listOf("patio"), "any").sensors.map { it.entityId })
        assertEquals(listOf("b.patio_person_occupancy"), WatchCatalog.resolve(catalog, listOf("patio"), "person").sensors.map { it.entityId })
    }

    @Test
    fun `everywhere is cameras only, not indoor motion sensors`() {
        val catalog = listOf(
            WatchSensor("b.patio_person_occupancy", "Patio", "patio", "person", WatchSensor.Source.FRIGATE_CAMERA, "camera.patio", null),
            WatchSensor("b.recamara_motion", "Recámara", "recamara", null, WatchSensor.Source.GENERIC, null, null)
        )
        assertEquals(listOf("b.patio_person_occupancy"), WatchCatalog.resolve(catalog, listOf("toda la casa"), "person").sensors.map { it.entityId })
        // A home without Frigate cameras: its motion sensors are all it has.
        assertEquals(listOf("b.recamara_motion"), WatchCatalog.resolve(catalog.drop(1), emptyList(), "person").sensors.map { it.entityId })
    }

    @Test
    fun `exact place names win over shared words`() {
        val names = listOf("Puerta principal", "Puerta del garaje")
        assertEquals(listOf("Puerta del garaje"), WatchCatalog.matchPlaces("la puerta del garaje", names))
        assertEquals(names, WatchCatalog.matchPlaces("puerta", names))
    }

    @Test
    fun `presence sensors are not places`() {
        val states = listOf(
            HaStateLite("binary_sensor.ana_presence", "on", "Ana", "presence"),
            HaStateLite("binary_sensor.patio_motion", "off", "Patio Motion", "motion")
        )
        assertEquals(listOf("binary_sensor.patio_motion"), WatchCatalog.build(states, emptyMap()).map { it.entityId })
    }

    @Test
    fun `names in other scripts keep their own key`() {
        val states = listOf(
            HaStateLite("binary_sensor.sad_motion", "off", "Сад", "motion"),
            HaStateLite("binary_sensor.dvor_motion", "off", "Двор", "motion")
        )
        val catalog = WatchCatalog.build(states, emptyMap())
        assertEquals(2, catalog.map { it.areaKey }.distinct().size)
        assertEquals(listOf("binary_sensor.sad_motion"), WatchCatalog.resolve(catalog, listOf("сад"), "person").sensors.map { it.entityId })
    }

    @Test
    fun `stop understands everywhere and exact names`() {
        val storage = object : WatchPersistence {
            var v: String? = null
            override fun save(encoded: String) { v = encoded }
            override fun load(): String? = v
        }
        val book = CameraWatchBook(storage)
        book.add(listOf("s.1"), listOf("Puerta principal"), "person", 3_600_000, false, 0)
        book.add(listOf("s.2"), listOf("Puerta del garaje"), "person", 3_600_000, false, 0)
        assertEquals(listOf("Puerta del garaje"), book.stop(listOf("la puerta del garaje")).flatMap { it.areaNames })
        assertEquals(1, book.stop(listOf("toda la casa")).size)
        assertTrue(book.active(1).isEmpty())
    }

    @Test
    fun `one alert uses up every one-shot watch on the sensor`() {
        val storage = object : WatchPersistence {
            var v: String? = null
            override fun save(encoded: String) { v = encoded }
            override fun load(): String? = v
        }
        val book = CameraWatchBook(storage)
        book.add(listOf("s.1"), listOf("Patio"), "person", 3_600_000, false, 0)
        book.add(listOf("s.1"), listOf("Patio"), "person", 3_600_000, true, 0)
        assertTrue(book.onSensorActivated("s.1", 10) != null)
        assertEquals(1, book.active(20).size)
        assertFalse(book.active(20).single().once)
    }

    @Test
    fun `a second printer with numbered ids is its own printer`() {
        val text = PrinterStatus.summarize(
            listOf(
                PrinterStatus.Entity("sensor.octoprint_current_state", "x", "Printing"),
                PrinterStatus.Entity("sensor.octoprint_current_state_2", "x", "Operational")
            ),
            emptyList()
        )
        assertTrue(text, text.contains("'octoprint'") && text.contains("'octoprint_2'"))
    }

    @Test
    fun `octoprint fallback needs real printer sensors`() {
        assertFalse(PrinterStatus.hasDefaultNamedPrinter(listOf("sensor.octoprint_cpu_temperature", "switch.octoprint_plug")))
        assertTrue(PrinterStatus.hasDefaultNamedPrinter(listOf("sensor.octoprint_job_percentage")))
    }

    @Test
    fun `new frigate labels map to their alert group`() {
        assertEquals("car", WatchCatalog.groupOfLabel("truck"))
        assertEquals("animal", WatchCatalog.groupOfLabel("squirrel"))
        assertEquals("package", WatchCatalog.groupOfLabel("fedex"))
        assertEquals(null, WatchCatalog.groupOfLabel("all"))
    }

    @Test
    fun `everywhere includes non-frigate cameras with a motion sensor`() {
        val catalog = listOf(
            WatchSensor("b.patio_person_occupancy", "Patio", "patio", "person", WatchSensor.Source.FRIGATE_CAMERA, "camera.patio", null),
            WatchSensor("b.cochera_motion", "Cochera", "cochera", null, WatchSensor.Source.GENERIC, "camera.cochera", null),
            WatchSensor("b.recamara_motion", "Recámara", "recamara", null, WatchSensor.Source.GENERIC, null, null)
        )
        assertEquals(
            setOf("b.patio_person_occupancy", "b.cochera_motion"),
            WatchCatalog.resolve(catalog, listOf("toda la casa"), "person").sensors.map { it.entityId }.toSet()
        )
    }

    @Test
    fun `hit tells when a one-shot watch was used up behind a persistent one`() {
        val storage = object : WatchPersistence {
            var v: String? = null
            override fun save(encoded: String) { v = encoded }
            override fun load(): String? = v
        }
        val book = CameraWatchBook(storage)
        book.add(listOf("s.1"), listOf("Patio"), "person", 3_600_000, false, 0)
        book.add(listOf("s.1"), listOf("Patio"), "person", 3_600_000, true, 0)
        val hit = book.onSensorActivated("s.1", 10)!!
        assertFalse(hit.watch.once)
        assertTrue(hit.consumedOneShot)
    }
}
