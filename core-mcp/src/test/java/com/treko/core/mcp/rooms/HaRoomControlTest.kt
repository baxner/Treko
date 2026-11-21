package com.treko.core.mcp.rooms

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaRoomControlTest {

    private class FakeBackend(
        var configured: Boolean = true,
        val templates: MutableMap<String, String?> = mutableMapOf(),
        val failing: MutableSet<String> = mutableSetOf()
    ) : HaRoomBackend {
        val calls = mutableListOf<HaServiceCall>()
        val renders = mutableListOf<String>()
        var lightsOutput: String? = null

        override fun isConfigured() = configured

        override suspend fun renderTemplate(template: String): String? {
            renders += template
            if (template == HaRooms.AREAS_TEMPLATE) return templates["areas"]
            if (template.startsWith("{%- for e in [")) return lightsOutput
            return templates.entries.firstOrNull { template.contains(it.key) }?.value
        }

        override suspend fun callService(call: HaServiceCall): Boolean {
            calls += call
            return "${call.domain}.${call.service}" !in failing
        }
    }

    private val lights = "light.techo|on|200|color_temp|4000||||||None|color_temp|\n" +
        "light.pasillo|off|None|None|None||||||None|color_temp|\n" +
        "light.tira|on|120|hs|None|240.0,80.0|||||None|hs|"

    @Test
    fun listAreasIsNullWithoutHomeAssistant() = runBlocking {
        val backend = FakeBackend(configured = false)
        assertNull(HaRoomControl(backend).listRoomCandidates())
        assertTrue(backend.renders.isEmpty())
    }

    @Test
    fun listRoomCandidatesFiltersEmptyAreas() = runBlocking {
        val backend = FakeBackend(templates = mutableMapOf("areas" to "a|A||light.x\nb|B||\nc|C|media_player.c=idle=0|"))
        assertEquals(listOf("a", "c"), HaRoomControl(backend).listRoomCandidates()?.map { it.id })
    }

    @Test
    fun dimSnapshotsSceneOfOnLightsAndNeverTouchesOffOnes() = runBlocking {
        val backend = FakeBackend().apply { lightsOutput = lights }
        val record = HaRoomControl(backend).dimLightsWarm(listOf("light.techo", "light.pasillo", "light.tira"))
        assertNotNull(record)
        assertEquals(HaRooms.RESTORE_SCENE_ENTITY, record!!.sceneEntityId)
        assertEquals(listOf("light.techo", "light.tira"), record.lights.map { it.entityId })
        val scene = backend.calls.first()
        assertEquals("scene.create", "${scene.domain}.${scene.service}")
        assertEquals(listOf("light.techo", "light.tira"), scene.data["snapshot_entities"])
        assertTrue(backend.calls.none { it.data["entity_id"] == "light.pasillo" })
        assertEquals(2, backend.calls.count { it.service == "turn_on" })
    }

    @Test
    fun dimWithoutSceneSupportKeepsLocalSnapshot() = runBlocking {
        val backend = FakeBackend(failing = mutableSetOf("scene.create")).apply { lightsOutput = lights }
        val record = HaRoomControl(backend).dimLightsWarm(listOf("light.techo", "light.pasillo"))!!
        assertNull(record.sceneEntityId)
        assertEquals(listOf("light.techo"), record.lights.map { it.entityId })
    }

    @Test
    fun dimWithAllLightsOffChangesNothing() = runBlocking {
        val backend = FakeBackend().apply { lightsOutput = "light.pasillo|off|None|None|None||||||None|color_temp|" }
        val record = HaRoomControl(backend).dimLightsWarm(listOf("light.pasillo"))!!
        assertTrue(record.isEmpty)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun restoreUsesTheSceneWhenItStillExists() = runBlocking {
        val backend = FakeBackend(templates = mutableMapOf("states('scene." to "2026-11-21T10:00:00"))
        val record = RoomRestoreRecord(HaRooms.RESTORE_SCENE_ENTITY, HaRooms.parseLights(lights).filter { it.isOn })
        assertTrue(HaRoomControl(backend).restoreLights(record))
        assertEquals("scene.turn_on", backend.calls[0].let { "${it.domain}.${it.service}" })
        assertTrue(backend.calls.none { it.domain == "light" })
    }

    @Test
    fun restoreFallsBackToLocalSnapshotWhenSceneIsGone() = runBlocking {
        val backend = FakeBackend(templates = mutableMapOf("states('scene." to "unknown"))
        val record = RoomRestoreRecord(HaRooms.RESTORE_SCENE_ENTITY, HaRooms.parseLights(lights).filter { it.isOn })
        assertTrue(HaRoomControl(backend).restoreLights(record))
        val lightCalls = backend.calls.filter { it.domain == "light" }
        assertEquals(listOf("light.techo", "light.tira"), lightCalls.map { it.data["entity_id"] })
        assertEquals(4000, lightCalls[0].data["color_temp_kelvin"])
    }

    @Test
    fun restoreFallsBackWhenSceneTurnOnFailsAndReportsFailures() = runBlocking {
        val backend = FakeBackend(
            templates = mutableMapOf("states('scene." to "2026-11-21"),
            failing = mutableSetOf("scene.turn_on", "light.turn_on")
        )
        val record = RoomRestoreRecord(HaRooms.RESTORE_SCENE_ENTITY, HaRooms.parseLights(lights).filter { it.isOn })
        assertFalse(HaRoomControl(backend).restoreLights(record))
        assertEquals(2, backend.calls.count { it.domain == "light" })
    }

    @Test
    fun restoreWithoutHomeAssistantKeepsTheRecordPending() = runBlocking {
        val backend = FakeBackend(configured = false)
        val record = RoomRestoreRecord(null, HaRooms.parseLights(lights).filter { it.isOn })
        assertFalse(HaRoomControl(backend).restoreLights(record))
        assertTrue(HaRoomControl(backend).restoreLights(RoomRestoreRecord(null, emptyList())))
    }

    @Test
    fun playerVolumeReadSetAndFade() = runBlocking {
        val backend = FakeBackend(templates = mutableMapOf("volume_level" to "playing|0.6"))
        val sleeps = mutableListOf<Long>()
        val control = HaRoomControl(backend) { sleeps += it }
        assertEquals(0.6, control.getPlayer("media_player.echo")!!.volume!!, 0.0001)
        assertNull(control.getPlayer("media_player.bad'"))
        assertTrue(control.fadeVolume("media_player.echo", 0.2, 0.0, steps = 4, stepMs = 500))
        assertEquals(listOf(0.15, 0.1, 0.05, 0.0), backend.calls.map { it.data["volume_level"] })
        assertEquals(listOf(500L, 500L, 500L), sleeps)
    }

    @Test
    fun stopFallsBackToPause() = runBlocking {
        val backend = FakeBackend(failing = mutableSetOf("media_player.media_stop"))
        assertTrue(HaRoomControl(backend).stopPlayback("media_player.echo"))
        assertEquals(listOf("media_stop", "media_pause"), backend.calls.map { it.service })
    }

    @Test
    fun restoreAllStopsPlayerRestoresVolumeAndLights() = runBlocking {
        val backend = FakeBackend()
        val record = RoomRestoreRecord(
            sceneEntityId = null,
            lights = HaRooms.parseLights(lights).filter { it.isOn },
            mediaPlayer = "media_player.echo",
            volume = 0.45
        )
        assertTrue(HaRoomControl(backend).restoreAll(record))
        assertEquals(
            listOf("media_stop", "volume_set", "turn_on", "turn_on"),
            backend.calls.map { it.service }
        )
        assertEquals(0.45, backend.calls[1].data["volume_level"])
    }
}
