package com.treko.assistant.meditation

import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.HaAreaPlayer
import com.treko.core.mcp.rooms.HaRoomBackend
import com.treko.core.mcp.rooms.HaRoomControl
import com.treko.core.mcp.rooms.HaRooms
import com.treko.core.mcp.rooms.HaServiceCall
import com.treko.core.mcp.rooms.LightSnapshot
import com.treko.core.mcp.rooms.RoomRestoreRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeditationRoomRestoreTest {

    private class MemoryKeyValue : MeditationKeyValue {
        val strings = mutableMapOf<String, String>()
        val ints = mutableMapOf<String, Int>()
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }
        override fun getInt(key: String) = ints[key] ?: 0
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }
    }

    /** Home Assistant with real-looking lights and one player; records every service call. */
    private class FakeHa : HaRoomBackend {
        var online = true
        val calls = mutableListOf<HaServiceCall>()
        val failing = mutableSetOf<String>()
        var sceneExists = true
        val lights = linkedMapOf(
            "light.techo" to "light.techo|on|200|color_temp|4000||||||None|color_temp|",
            "light.lampara" to "light.lampara|on|150|hs|None|30.0,60.0|||||None|hs,color_temp|",
            "light.buro" to "light.buro|off|None|None|None||||||None|color_temp|"
        )

        override fun isConfigured() = true

        override suspend fun renderTemplate(template: String): String? {
            if (!online) return null
            if (template.startsWith("{%- for e in [")) {
                return lights.filterKeys { "'$it'" in template }.values.joinToString("\n")
            }
            if (template.contains("volume_level")) return "playing|0.45"
            if (template.contains("scene.")) return if (sceneExists) "2024-01-01T00:00:00" else "unknown"
            return null
        }

        override suspend fun callService(call: HaServiceCall): Boolean {
            if (!online) return false
            calls += call
            return "${call.domain}.${call.service}" !in failing
        }

        fun touched(entityId: String) = calls.any { c ->
            c.data["entity_id"] == entityId || (c.data["snapshot_entities"] as? List<*>)?.contains(entityId) == true
        }
    }

    private val bedroom = HaArea(
        id = "dormitorio",
        name = "Recámara",
        mediaPlayers = listOf(HaAreaPlayer("media_player.echo", "idle", isAlexa = true)),
        lights = listOf("light.techo", "light.lampara", "light.buro")
    )

    private fun room(ha: FakeHa, store: MeditationRestoreStore, area: HaArea? = bedroom, playOk: Boolean = true, played: MutableList<String> = mutableListOf()) =
        HomeMeditationRoom(
            rooms = HaRoomControl(ha, sleep = {}),
            area = area,
            type = MeditationType.MINDFULNESS,
            settings = MeditationRoomSettings(adjustLights = true, playMusic = true, preferAlexa = true),
            store = store,
            playOnPlayer = { q, id ->
                played += "$id:$q"
                playOk
            },
            musicQuery = "música para meditar",
            phoneMusic = null,
            wallClock = { 1_000L }
        )

    @Test
    fun prepareDimsOnlyLightsThatWereOnAndSavesTheRecordFirst() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        val store = MeditationRestoreStore(kv, wallClock = { 2_000L })
        val played = mutableListOf<String>()
        val musicOk = room(ha, store, played = played).prepare()
        assertTrue(musicOk)
        assertEquals(listOf("media_player.echo:música para meditar"), played)
        // The light that was off is never touched: not dimmed, not in the scene, not in the record.
        assertFalse(ha.touched("light.buro"))
        val saved = store.load()!!
        assertEquals(listOf("light.techo", "light.lampara"), saved.lights.map { it.entityId })
        assertEquals(HaRooms.RESTORE_SCENE_ENTITY, saved.sceneEntityId)
        assertEquals("media_player.echo", saved.mediaPlayer)
        assertEquals(0.45, saved.volume!!, 0.0001)
        // Volume was saved before it was lowered for the session.
        val firstVolumeSet = ha.calls.indexOfFirst { it.service == "volume_set" }
        assertEquals(HomeMeditationRoom.PLAYER_LEVEL, ha.calls[firstVolumeSet].data["volume_level"])
        val dims = ha.calls.filter { it.domain == "light" && it.service == "turn_on" }
        assertEquals(setOf("light.techo", "light.lampara"), dims.map { it.data["entity_id"] }.toSet())
    }

    @Test
    fun finishFadesStopsAndPutsEverythingBack() = runBlocking {
        val ha = FakeHa()
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store)
        r.prepare()
        ha.calls.clear()
        r.finish()
        val services = ha.calls.map { "${it.domain}.${it.service}" }
        // Fade down in steps, stop, then the saved volume, then the lights through the scene.
        val volumes = ha.calls.filter { it.service == "volume_set" }.map { it.data["volume_level"] }
        assertEquals(0.0, volumes[volumes.size - 2])
        assertEquals(0.45, volumes.last())
        assertTrue(services.indexOf("media_player.media_stop") < services.lastIndexOf("media_player.volume_set"))
        assertTrue("scene.turn_on" in services)
        assertFalse(ha.touched("light.buro"))
        assertNull(store.load())
    }

    @Test
    fun withoutTheSceneLightsComeBackOneByOneAsTheyWere() = runBlocking {
        val ha = FakeHa().apply { failing += "scene.create" }
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store)
        r.prepare()
        assertNull(store.load()!!.sceneEntityId)
        ha.calls.clear()
        r.finish()
        val restores = ha.calls.filter { it.domain == "light" }
        val techo = restores.single { it.data["entity_id"] == "light.techo" }
        assertEquals(200, techo.data["brightness"])
        assertEquals(4000, techo.data["color_temp_kelvin"])
        val lampara = restores.single { it.data["entity_id"] == "light.lampara" }
        assertEquals(listOf(30.0, 60.0), lampara.data["hs_color"])
        assertTrue(restores.none { it.data["entity_id"] == "light.buro" })
        assertNull(store.load())
    }

    @Test
    fun aRecordLeftByACrashIsRestoredOnTheNextStart() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L })).prepare()
        // The process dies here. A new store (next service start) finds the record on disk.
        ha.calls.clear()
        ha.sceneExists = false // Home Assistant restarted meanwhile: scene.create scenes are gone.
        val next = MeditationRestoreStore(kv, wallClock = { 60_000L })
        assertNotNull(next.load())
        assertEquals(RestoreResult.RESTORED, next.restore(HaRoomControl(ha, sleep = {})))
        val services = ha.calls.map { "${it.domain}.${it.service}" }
        assertTrue("media_player.media_stop" in services)
        assertEquals(0.45, ha.calls.last { it.service == "volume_set" }.data["volume_level"])
        assertEquals(2, ha.calls.count { it.domain == "light" && it.service == "turn_on" })
        assertNull(next.load())
        assertEquals(RestoreResult.NOTHING, next.restore(HaRoomControl(ha, sleep = {})))
    }

    @Test
    fun aFailedRestoreKeepsOnlyWhatIsLeftAndGivesUpEventually() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        val store = MeditationRestoreStore(kv, wallClock = { 2_000L })
        room(ha, store).prepare()
        // Lights can be restored, the speaker is offline.
        ha.failing += "media_player.volume_set"
        assertEquals(RestoreResult.KEPT, store.restore(HaRoomControl(ha, sleep = {})))
        val left = store.load()!!
        assertTrue(left.lights.isEmpty())
        assertNull(left.sceneEntityId)
        assertEquals("media_player.echo", left.mediaPlayer)
        assertEquals(1, store.attempts)
        repeat(MeditationRestoreStore.MAX_ATTEMPTS - 2) {
            assertEquals(RestoreResult.KEPT, store.restore(HaRoomControl(ha, sleep = {})))
        }
        assertEquals(RestoreResult.DROPPED, store.restore(HaRoomControl(ha, sleep = {})))
        assertNull(store.load())
    }

    @Test
    fun withoutHomeAssistantTheRecordWaits() = runBlocking {
        val kv = MemoryKeyValue()
        val store = MeditationRestoreStore(kv, wallClock = { 2_000L })
        store.save(
            RoomRestoreRecord(
                sceneEntityId = null,
                lights = listOf(LightSnapshot("light.techo", isOn = true, brightness = 200, colorMode = "color_temp", colorTempKelvin = 4000, supportedColorModes = listOf("color_temp"))),
                startedAtMs = 1_000L
            )
        )
        assertEquals(RestoreResult.KEPT, store.restore(null))
        assertNotNull(store.load())
    }

    @Test
    fun aVeryOldRecordIsDroppedInsteadOfUndoingTheUsersRoom() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        val store = MeditationRestoreStore(kv, wallClock = { 1_000L + MeditationRestoreStore.MAX_AGE_MS + 1 })
        store.save(RoomRestoreRecord(sceneEntityId = null, lights = emptyList(), mediaPlayer = "media_player.echo", volume = 0.3, startedAtMs = 1_000L))
        assertEquals(RestoreResult.DROPPED, store.restore(HaRoomControl(ha, sleep = {})))
        assertTrue(ha.calls.isEmpty())
        assertNull(store.load())
    }

    @Test
    fun failedRoomMusicIsReportedAndPhoneIsUsedWithoutARoomPlayer() = runBlocking {
        val ha = FakeHa()
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        assertFalse(room(ha, store, playOk = false).prepare())

        val started = mutableListOf<MeditationType>()
        val phone = object : PhoneMeditationMusic {
            override suspend fun start(type: MeditationType): Boolean {
                started += type
                return true
            }
            override fun setLevel(level: Float) {}
            override suspend fun fadeOutAndStop() {}
        }
        val lightsOnly = bedroom.copy(mediaPlayers = emptyList())
        val r = HomeMeditationRoom(
            rooms = HaRoomControl(FakeHa(), sleep = {}), area = lightsOnly, type = MeditationType.SLEEP,
            settings = MeditationRoomSettings(), store = MeditationRestoreStore(MemoryKeyValue()),
            playOnPlayer = { _, _ -> error("no room player") }, musicQuery = "", phoneMusic = phone
        )
        assertTrue(r.prepare())
        assertEquals(listOf(MeditationType.SLEEP), started)
    }
}
