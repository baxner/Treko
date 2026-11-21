package com.treko.core.mcp.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneCamerasTest {

    private fun camera(cam: String, obj: String = "person", name: String = cam.replaceFirstChar { it.uppercase() }) = WatchSensor(
        entityId = "binary_sensor.${cam}_${obj}_occupancy",
        areaName = name,
        areaKey = WatchCatalog.normalize(name),
        objectType = obj,
        source = WatchSensor.Source.FRIGATE_CAMERA,
        cameraEntityId = "camera.$cam",
        snapshotEntityId = "image.${cam}_$obj"
    )

    private fun zone(slug: String, obj: String = "person", name: String = slug.replace('_', ' ')) = WatchSensor(
        entityId = "binary_sensor.${slug}_${obj}_occupancy",
        areaName = name,
        areaKey = WatchCatalog.normalize(name),
        objectType = obj,
        source = WatchSensor.Source.FRIGATE_ZONE,
        cameraEntityId = null,
        snapshotEntityId = null
    )

    private class MemoryStore(var value: String? = null) : WatchPersistence {
        override fun save(encoded: String) { value = encoded }
        override fun load(): String? = value
    }

    private val entrada = camera("entrada")
    private val patio = camera("patio")
    private val patioCar = camera("patio", "car")
    private val cercano = zone("frente_cercano", name = "Frente Cercano")
    private val twoCameras = listOf(entrada, patio, patioCar, cercano)

    @Test
    fun `the only frigate camera is the zone's camera`() {
        assertEquals("camera.entrada", ZoneCameras.linkedCamera(cercano, listOf(entrada, cercano)))
    }

    @Test
    fun `a zone named after its camera links to it, longest name first`() {
        val frente = camera("frente")
        val frenteCasa = camera("frente_casa")
        val z = zone("frente_casa_puerta")
        assertEquals("camera.frente_casa", ZoneCameras.linkedCamera(z, listOf(frente, frenteCasa, patio, z)))
        // Camera name at the end works too.
        val z2 = zone("puerta_patio")
        assertEquals("camera.patio", ZoneCameras.linkedCamera(z2, listOf(entrada, patio, z2)))
    }

    @Test
    fun `names that say nothing leave the zone's camera unknown`() {
        assertNull(ZoneCameras.linkedCamera(cercano, twoCameras))
        // "patio" inside "patiotrasero" is not a word of it.
        val z = zone("patiotrasero")
        assertNull(ZoneCameras.linkedCamera(z, listOf(entrada, patio, z)))
    }

    @Test
    fun `companion sensors are the cameras' sensors for the zone's object`() {
        assertEquals(listOf(entrada, patio), ZoneCameras.companionSensors(cercano, twoCameras))
        val carZone = zone("frente_cercano", "car", "Frente Cercano")
        assertEquals(listOf(patioCar), ZoneCameras.companionSensors(carZone, twoCameras + carZone))
        assertTrue(ZoneCameras.companionSensors(entrada, twoCameras).isEmpty())
    }

    @Test
    fun `co-firing within five seconds teaches the camera, either order`() {
        val learner = ZoneCameraLearner()
        // Camera first, then the zone 3 s later.
        assertTrue(learner.onSensorOn(patio.entityId, 10_000, twoCameras).isEmpty())
        assertEquals(listOf(cercano to "camera.patio"), learner.onSensorOn(cercano.entityId, 13_000, twoCameras))

        // Zone first, camera 4 s later.
        val l2 = ZoneCameraLearner()
        assertTrue(l2.onSensorOn(cercano.entityId, 50_000, twoCameras).isEmpty())
        assertEquals(listOf(cercano to "camera.entrada"), l2.onSensorOn(entrada.entityId, 54_000, twoCameras))
    }

    @Test
    fun `too far apart, another object or two cameras at once teach nothing`() {
        val far = ZoneCameraLearner()
        far.onSensorOn(patio.entityId, 0, twoCameras)
        assertTrue(far.onSensorOn(cercano.entityId, 6_000, twoCameras).isEmpty())

        val otherObject = ZoneCameraLearner()
        otherObject.onSensorOn(patioCar.entityId, 0, twoCameras)
        assertTrue(otherObject.onSensorOn(cercano.entityId, 1_000, twoCameras).isEmpty())

        val both = ZoneCameraLearner()
        both.onSensorOn(patio.entityId, 0, twoCameras)
        both.onSensorOn(entrada.entityId, 500, twoCameras)
        assertTrue(both.onSensorOn(cercano.entityId, 1_000, twoCameras).isEmpty())
    }

    @Test
    fun `memory keeps the camera seen most often and survives a restart`() {
        val store = MemoryStore()
        val memory = ZoneCameraMemory(store)
        assertNull(memory.cameraFor(cercano.areaKey))
        assertTrue(memory.learn(cercano.areaKey, "camera.patio"))
        assertFalse(memory.learn(cercano.areaKey, "camera.patio"))
        // One odd co-firing does not override a camera seen twice.
        assertFalse(memory.learn(cercano.areaKey, "camera.entrada"))
        assertEquals("camera.patio", memory.cameraFor(cercano.areaKey))

        val reloaded = ZoneCameraMemory(store)
        assertEquals("camera.patio", reloaded.cameraFor(cercano.areaKey))
        assertNull(ZoneCameraMemory(MemoryStore("garbage")).cameraFor(cercano.areaKey))
    }

    @Test
    fun `known camera prefers the names, then what was learned, if that camera still exists`() {
        val memory = ZoneCameraMemory(MemoryStore())
        assertNull(ZoneCameras.knownCamera(cercano, twoCameras, memory))
        memory.learn(cercano.areaKey, "camera.patio")
        assertEquals("camera.patio", ZoneCameras.knownCamera(cercano, twoCameras, memory))
        // The learned camera was removed from Home Assistant.
        val other = camera("cochera")
        assertNull(ZoneCameras.knownCamera(cercano, listOf(entrada, other, cercano), memory))
        // Names win over memory.
        val named = zone("entrada_puerta")
        memory.learn(named.areaKey, "camera.patio")
        assertEquals("camera.entrada", ZoneCameras.knownCamera(named, listOf(entrada, patio, named), memory))
    }

    @Test
    fun `at alert time the camera seeing the same object right now is used`() {
        val states = mapOf(entrada.entityId to "off", patio.entityId to "on", patioCar.entityId to "on")
        assertEquals(patio, ZoneCameras.cameraSeeingNow(cercano, twoCameras, states))
        assertNull(ZoneCameras.cameraSeeingNow(cercano, twoCameras, mapOf(patio.entityId to "off")))
        assertEquals(patio, ZoneCameras.sensorOfCamera("camera.patio", cercano, twoCameras))
    }
}
