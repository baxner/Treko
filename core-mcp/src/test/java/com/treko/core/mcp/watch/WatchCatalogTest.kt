package com.treko.core.mcp.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCatalogTest {

    // A home with Frigate: two cameras, one zone, plus an unrelated motion sensor elsewhere.
    private val frigateHome = listOf(
        HaStateLite("camera.jardin", "idle", "Jardín"),
        HaStateLite("camera.frente_casa", "idle", "Frente Casa"),
        HaStateLite("binary_sensor.jardin_person_occupancy", "off", "Jardín Person occupancy", "occupancy"),
        HaStateLite("binary_sensor.jardin_car_occupancy", "off", "Jardín Car occupancy", "occupancy"),
        HaStateLite("binary_sensor.jardin_all_occupancy", "off", "Jardín All occupancy", "occupancy"),
        HaStateLite("binary_sensor.frente_casa_person_occupancy", "off", "Frente Casa Person occupancy", "occupancy"),
        HaStateLite("binary_sensor.banqueta_person_occupancy", "off", "Banqueta Person occupancy", "occupancy"),
        HaStateLite("image.jardin_person", "2026-11-20T10:00:00", "Jardín Person"),
        HaStateLite("binary_sensor.pasillo_movimiento", "off", "Pasillo movimiento", "motion"),
        HaStateLite("light.sala", "on", "Sala")
    )
    private val frigateIds: Map<String, String> = frigateHome.map { it.entityId }
        .filter { it.contains("jardin") || it.contains("frente") || it.contains("banqueta") }
        .associateWith { id ->
            when {
                id.contains("jardin") -> "dev-jardin"
                id.contains("frente") -> "dev-frente"
                else -> "dev-banqueta"
            }
        }

    @Test
    fun `parses frigate occupancy sensors`() {
        assertEquals("jardin" to "person", WatchCatalog.parseFrigateOccupancy("binary_sensor.jardin_person_occupancy"))
        assertEquals("puerta_principal" to "license_plate", WatchCatalog.parseFrigateOccupancy("binary_sensor.puerta_principal_license_plate_occupancy"))
        assertNull(WatchCatalog.parseFrigateOccupancy("binary_sensor.sala_occupancy"))
    }

    @Test
    fun `catalog uses each home's own camera names, zones and generic sensors`() {
        val catalog = WatchCatalog.build(frigateHome, frigateIds)
        val jardin = catalog.first { it.entityId == "binary_sensor.jardin_person_occupancy" }
        assertEquals("Jardín", jardin.areaName)
        assertEquals("camera.jardin", jardin.cameraEntityId)
        assertEquals("image.jardin_person", jardin.snapshotEntityId)
        val banqueta = catalog.first { it.entityId == "binary_sensor.banqueta_person_occupancy" }
        assertEquals(WatchSensor.Source.FRIGATE_ZONE, banqueta.source)
        val pasillo = catalog.first { it.entityId == "binary_sensor.pasillo_movimiento" }
        assertEquals(WatchSensor.Source.GENERIC, pasillo.source)
        assertEquals("Pasillo", pasillo.areaName)
    }

    @Test
    fun `asking for people in the front and the garden picks the person sensors`() {
        val catalog = WatchCatalog.build(frigateHome, frigateIds)
        val r = WatchCatalog.resolve(catalog, listOf("el frente", "jardin"), "person")
        assertEquals(
            setOf("binary_sensor.frente_casa_person_occupancy", "binary_sensor.jardin_person_occupancy"),
            r.sensors.map { it.entityId }.toSet()
        )
        assertTrue(r.unknownQueries.isEmpty())
    }

    @Test
    fun `unknown place is reported with the available ones`() {
        val catalog = WatchCatalog.build(frigateHome, frigateIds)
        val r = WatchCatalog.resolve(catalog, listOf("cochera"), "person")
        assertEquals(listOf("cochera"), r.unknownQueries)
        assertTrue(r.availableAreas.containsAll(listOf("Jardín", "Frente Casa", "Banqueta", "Pasillo")))
    }

    @Test
    fun `everywhere means every camera but not its zones`() {
        val catalog = WatchCatalog.build(frigateHome, frigateIds)
        val ids = WatchCatalog.resolve(catalog, listOf("toda la casa"), "person").sensors.map { it.entityId }
        assertTrue(ids.contains("binary_sensor.jardin_person_occupancy"))
        assertTrue(ids.contains("binary_sensor.frente_casa_person_occupancy"))
        assertTrue(!ids.contains("binary_sensor.banqueta_person_occupancy"))
    }

    @Test
    fun `place without frigate falls back to its motion sensor`() {
        val catalog = WatchCatalog.build(frigateHome, frigateIds)
        val r = WatchCatalog.resolve(catalog, listOf("pasillo"), "person")
        assertEquals(listOf("binary_sensor.pasillo_movimiento"), r.sensors.map { it.entityId })
    }

    @Test
    fun `home without frigate still gets generic sensors`() {
        val noFrigate = listOf(
            HaStateLite("binary_sensor.patio_motion", "off", "Patio Motion", "motion"),
            HaStateLite("camera.patio", "idle", "Patio")
        )
        val catalog = WatchCatalog.build(noFrigate, emptyMap())
        assertEquals(1, catalog.size)
        assertEquals("camera.patio", catalog.single().cameraEntityId)
    }

    @Test
    fun `renamed device - camera found through the shared device, zones through their own`() {
        // Real-world shape: the camera's device was renamed, so its sensors got a doubled
        // prefix (patio_patio_*) and the counters a third name; zones have no camera.
        val states = listOf(
            HaStateLite("camera.patio", "recording", "Patio"),
            HaStateLite("binary_sensor.patio_patio_person_occupancy", "off", "Patio Person occupancy", "occupancy"),
            HaStateLite("binary_sensor.patio_patio_all_occupancy", "off", "Patio All occupancy", "occupancy"),
            HaStateLite("binary_sensor.patio_patio_motion", "off", "Patio Motion", "motion"),
            HaStateLite("image.patio_patio_person", "2026-11-20T14:35:17", "Patio Person"),
            HaStateLite("binary_sensor.acera_norte_person_occupancy", "off", "Acera Norte Person occupancy", "occupancy")
        )
        val devices = mapOf(
            "camera.patio" to "d1",
            "binary_sensor.patio_patio_person_occupancy" to "d1",
            "binary_sensor.patio_patio_all_occupancy" to "d1",
            "binary_sensor.patio_patio_motion" to "d1",
            "image.patio_patio_person" to "d1",
            "binary_sensor.acera_norte_person_occupancy" to "d2"
        )
        val catalog = WatchCatalog.build(states, devices)
        val patio = catalog.first { it.entityId == "binary_sensor.patio_patio_person_occupancy" }
        assertEquals(WatchSensor.Source.FRIGATE_CAMERA, patio.source)
        assertEquals("camera.patio", patio.cameraEntityId)
        assertEquals("image.patio_patio_person", patio.snapshotEntityId)
        assertEquals("Patio", patio.areaName)
        val acera = catalog.first { it.entityId == "binary_sensor.acera_norte_person_occupancy" }
        assertEquals(WatchSensor.Source.FRIGATE_ZONE, acera.source)
        assertEquals("Acera Norte", acera.areaName)
        // Frigate's own motion sensor is not offered as a generic one.
        assertTrue(catalog.none { it.entityId == "binary_sensor.patio_patio_motion" })

        val r = WatchCatalog.resolve(catalog, listOf("acera", "el patio"), "person")
        assertEquals(
            setOf("binary_sensor.patio_patio_person_occupancy", "binary_sensor.acera_norte_person_occupancy"),
            r.sensors.map { it.entityId }.toSet()
        )
    }

    @Test
    fun `without device info the camera is the longest id prefix`() {
        val states = listOf(
            HaStateLite("camera.patio", "recording", "Patio"),
            HaStateLite("binary_sensor.patio_patio_person_occupancy", "off", "Patio Person occupancy", "occupancy")
        )
        val sensor = WatchCatalog.build(states, null).single()
        assertEquals("camera.patio", sensor.cameraEntityId)
    }
}
