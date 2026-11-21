package com.treko.assistant.meditation

import com.treko.core.mcp.RadioStation
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

    /**
     * Home Assistant with real-looking lights and one player that follow the service calls
     * (dims, turn-offs, the scene, volume, stop); records every call.
     */
    private class FakeHa : HaRoomBackend {
        var online = true
        val calls = mutableListOf<HaServiceCall>()
        val failing = mutableSetOf<String>()
        var sceneExists = true
        var playerState = "playing"
        var playerVolume: Double? = 0.45
        private var scene: List<LightSnapshot> = emptyList()
        val lights = linkedMapOf(
            "light.techo" to light("light.techo|on|200|color_temp|4000||||||None|color_temp|"),
            "light.lampara" to light("light.lampara|on|150|hs|None|30.0,60.0|||||None|hs,color_temp|"),
            "light.buro" to light("light.buro|off|None|None|None||||||None|color_temp|")
        )

        override fun isConfigured() = true

        override suspend fun renderTemplate(template: String): String? {
            if (!online) return null
            if (template.trim() == "{{ 1 }}") return "1"
            if (template.startsWith("{%- for e in [")) {
                return lights.filterKeys { "'$it'" in template }.values.joinToString("\n") { HaRooms.encodeLight(it) }
            }
            if (template.contains("volume_level")) return "$playerState|${playerVolume ?: "None"}"
            if (template.contains("scene.")) return if (sceneExists) "2024-01-01T00:00:00" else "unknown"
            return null
        }

        override suspend fun callService(call: HaServiceCall): Boolean {
            if (!online) return false
            calls += call
            val key = "${call.domain}.${call.service}"
            if (key in failing) return false
            val id = call.data["entity_id"] as? String
            when (key) {
                "light.turn_on" -> lights[id]?.let { l ->
                    val kelvin = call.data["color_temp_kelvin"] as? Int
                    @Suppress("UNCHECKED_CAST")
                    val hs = call.data["hs_color"] as? List<Double>
                    lights[l.entityId] = l.copy(
                        isOn = true,
                        brightness = call.data["brightness"] as? Int ?: l.brightness,
                        colorMode = if (kelvin != null) "color_temp" else if (hs != null) "hs" else l.colorMode,
                        colorTempKelvin = kelvin ?: l.colorTempKelvin,
                        hsColor = hs ?: l.hsColor
                    )
                }
                "light.turn_off" -> lights[id]?.let { lights[it.entityId] = it.copy(isOn = false) }
                "scene.create" -> scene = (call.data["snapshot_entities"] as List<*>).mapNotNull { lights[it] }
                "scene.turn_on" -> scene.forEach { lights[it.entityId] = it }
                "media_player.volume_set" -> playerVolume = call.data["volume_level"] as Double
                "media_player.media_stop" -> playerState = "idle"
            }
            return true
        }

        fun touched(entityId: String) = calls.any { c ->
            c.data["entity_id"] == entityId || (c.data["snapshot_entities"] as? List<*>)?.contains(entityId) == true
        }

        fun servicesFor(entityId: String) = calls.filter { it.data["entity_id"] == entityId }.map { "${it.domain}.${it.service}" }

        companion object {
            fun light(line: String) = HaRooms.decodeLight(line)!!
        }
    }

    private val bedroom = HaArea(
        id = "dormitorio",
        name = "Recámara",
        mediaPlayers = listOf(HaAreaPlayer("media_player.echo", "idle", isAlexa = true)),
        lights = listOf("light.techo", "light.lampara", "light.buro")
    )

    private fun room(
        ha: FakeHa,
        store: MeditationRestoreStore,
        area: HaArea? = bedroom,
        playOk: Boolean = true,
        played: MutableList<String> = mutableListOf(),
        type: MeditationType = MeditationType.MINDFULNESS,
        stream: RadioStation? = null,
        onPlay: () -> Unit = {}
    ) =
        HomeMeditationRoom(
            rooms = HaRoomControl(ha, sleep = {}),
            area = area,
            type = type,
            settings = MeditationRoomSettings(adjustLights = true, playMusic = true, preferAlexa = true),
            store = store,
            playOnPlayer = { q, id ->
                played += "$id:$q"
                if (playOk) onPlay()
                playOk
            },
            musicQuery = "música para meditar",
            calmStream = { stream },
            phoneMusic = null,
            wallClock = { 1_000L },
            sleep = {}
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
        r.finish(completed = true)
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
        r.finish(completed = true)
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
            playOnPlayer = { _, _ -> error("no room player") }, musicQuery = "", calmStream = { null }, phoneMusic = phone
        )
        assertTrue(r.prepare())
        assertEquals(listOf(MeditationType.SLEEP), started)
    }

    // ---- Sleep sessions: lights off at the end ----

    @Test
    fun aSleepSessionThatRunsToItsEndTurnsItsLightsOffAndRestoresTheVolume() = runBlocking {
        val ha = FakeHa()
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store, type = MeditationType.SLEEP)
        r.prepare()
        assertTrue(store.load()!!.lightsOffAtEnd)
        ha.calls.clear()
        r.finish(completed = true)
        val offs = ha.calls.filter { it.domain == "light" && it.service == "turn_off" }
        assertEquals(listOf("light.techo", "light.lampara"), offs.map { it.data["entity_id"] })
        assertTrue(offs.all { it.data["transition"] == HaRooms.SLEEP_OFF_TRANSITION_S })
        // Not put back on, and the light that was already off is never touched.
        assertTrue(ha.calls.none { it.domain == "light" && it.service == "turn_on" })
        assertTrue(ha.calls.none { "${it.domain}.${it.service}" == "scene.turn_on" })
        assertFalse(ha.touched("light.buro"))
        assertFalse(ha.lights.getValue("light.techo").isOn)
        assertFalse(ha.lights.getValue("light.lampara").isOn)
        // The music fades out and stops; the speaker's own volume comes back.
        assertTrue("media_player.media_stop" in ha.servicesFor("media_player.echo"))
        assertEquals(0.45, ha.calls.last { it.service == "volume_set" }.data["volume_level"])
        assertNull(store.load())
    }

    @Test
    fun aSleepSessionStoppedEarlyPutsTheLightsBackAsTheyWere() = runBlocking {
        val ha = FakeHa()
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store, type = MeditationType.SLEEP)
        r.prepare()
        ha.calls.clear()
        r.finish(completed = false)
        assertTrue(ha.calls.none { it.domain == "light" && it.service == "turn_off" })
        assertTrue(ha.calls.any { "${it.domain}.${it.service}" == "scene.turn_on" })
        assertEquals(200, ha.lights.getValue("light.techo").brightness)
        assertTrue(ha.lights.getValue("light.lampara").isOn)
        assertEquals(0.45, ha.playerVolume)
        assertNull(store.load())
    }

    @Test
    fun aSleepSessionStoppedEarlyWhileHomeAssistantIsAwayIsRestoredLaterNotTurnedOff() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        val r = room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L }), type = MeditationType.SLEEP)
        r.prepare()
        ha.online = false
        r.finish(completed = false)
        val left = MeditationRestoreStore(kv, wallClock = { 3_000L })
        assertFalse(left.load()!!.lightsOffAtEnd)
        ha.online = true
        ha.calls.clear()
        assertEquals(RestoreResult.RESTORED, left.restore(HaRoomControl(ha, sleep = {}), late = true))
        assertTrue(ha.calls.none { it.domain == "light" && it.service == "turn_off" })
        assertEquals(200, ha.lights.getValue("light.techo").brightness)
    }

    @Test
    fun aSleepSessionCutByACrashTurnsItsLightsOffOnRecovery() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L }), type = MeditationType.SLEEP).prepare()
        // The process dies mid-session. Meanwhile the user switched the lamp off by hand.
        ha.lights["light.lampara"] = ha.lights.getValue("light.lampara").copy(isOn = false)
        ha.calls.clear()
        val next = MeditationRestoreStore(kv, wallClock = { 60_000L })
        assertTrue(next.load()!!.lightsOffAtEnd)
        assertEquals(RestoreResult.RESTORED, next.restore(HaRoomControl(ha, sleep = {}), late = true))
        // The dimmed light goes off instead of back to full brightness; the lamp is left alone.
        assertEquals(listOf("light.techo"), ha.calls.filter { it.domain == "light" }.map { it.data["entity_id"] })
        assertEquals("turn_off", ha.calls.single { it.domain == "light" }.service)
        assertFalse(ha.lights.getValue("light.techo").isOn)
        assertTrue(ha.calls.none { "${it.domain}.${it.service}" == "scene.turn_on" })
        // Treko's stream is still playing at Treko's level: stopped, volume put back.
        assertTrue("media_player.media_stop" in ha.servicesFor("media_player.echo"))
        assertEquals(0.45, ha.playerVolume)
        assertNull(next.load())
    }

    // ---- Volume ----

    @Test
    fun anUnknownVolumeIsNeverLoweredNorFadedToZero() = runBlocking {
        val ha = FakeHa().apply {
            playerState = "off"
            playerVolume = null
        }
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store)
        assertTrue(r.prepare())
        assertNull(store.load()!!.volume)
        r.finish(completed = true)
        assertTrue(ha.calls.none { it.service == "volume_set" })
        assertTrue("media_player.media_stop" in ha.servicesFor("media_player.echo"))
        assertNull(store.load())
    }

    @Test
    fun anOffPlayerReportsItsVolumeOnceItPlaysAndGetsItBack() = runBlocking {
        val ha = FakeHa().apply {
            playerState = "off"
            playerVolume = null
        }
        val store = MeditationRestoreStore(MemoryKeyValue(), wallClock = { 2_000L })
        val r = room(ha, store, onPlay = {
            ha.playerState = "playing"
            ha.playerVolume = 0.3
        })
        assertTrue(r.prepare())
        assertEquals(0.3, store.load()!!.volume!!, 0.0001)
        assertEquals(HomeMeditationRoom.PLAYER_LEVEL, ha.playerVolume)
        r.finish(completed = true)
        assertEquals(0.3, ha.playerVolume)
    }

    // ---- Players without Alexa ----

    @Test
    fun aPlayerWithoutAlexaGetsACalmStreamUrlOrNothing() = runBlocking {
        val speaker = bedroom.copy(mediaPlayers = listOf(HaAreaPlayer("media_player.nest", "idle", isAlexa = false)))
        val calm = RadioStation("Calm Radio", "https://example.org/calm.mp3", tags = "meditation,ambient")
        val played = mutableListOf<String>()
        assertTrue(room(FakeHa(), MeditationRestoreStore(MemoryKeyValue()), area = speaker, played = played, stream = calm).prepare())
        assertEquals(listOf("media_player.nest:https://example.org/calm.mp3"), played)

        val none = mutableListOf<String>()
        assertFalse(room(FakeHa(), MeditationRestoreStore(MemoryKeyValue()), area = speaker, played = none, stream = null).prepare())
        // No search for a phrase that could find a pop or news station.
        assertTrue(none.isEmpty())
    }

    // ---- Leftover records ----

    @Test
    fun aLeftoverRecordIsPutBackBeforeTheNextSessionSnapshotsTheRoom() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L })).prepare()
        // Crash; a new session starts before any leftover retry ran.
        ha.calls.clear()
        val store = MeditationRestoreStore(kv, wallClock = { 3_000L })
        room(ha, store).prepare()
        val services = ha.calls.map { "${it.domain}.${it.service}" }
        assertTrue(services.indexOf("scene.turn_on") < services.indexOf("scene.create"))
        // The new snapshot is the user's room, not the dimmed one.
        val saved = store.load()!!
        assertEquals(200, saved.lights.single { it.entityId == "light.techo" }.brightness)
        assertEquals(0.45, saved.volume!!, 0.0001)
    }

    @Test
    fun aLeftoverThatCannotBePutBackIsCarriedNotOverwritten() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L })).prepare()
        // Crash; the lights refuse every change now, so neither restore nor dimming works.
        ha.failing += setOf("light.turn_on", "scene.turn_on")
        ha.calls.clear()
        val store = MeditationRestoreStore(kv, wallClock = { 3_000L })
        room(ha, store).prepare()
        val saved = store.load()!!
        // The older snapshot (before any dimming) is kept for the carried lights.
        assertEquals(200, saved.lights.single { it.entityId == "light.techo" }.brightness)
        assertEquals(150, saved.lights.single { it.entityId == "light.lampara" }.brightness)
        // The restore scene that still holds the original state is not overwritten.
        assertTrue(ha.calls.none { "${it.domain}.${it.service}" == "scene.create" })
    }

    @Test
    fun aLateRestoreLeavesWhatTheUserChangedSince() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        room(ha, MeditationRestoreStore(kv, wallClock = { 2_000L })).prepare()
        // Crash. Later the user turned the lamp off, set the ceiling to full and played their own music.
        ha.lights["light.lampara"] = ha.lights.getValue("light.lampara").copy(isOn = false)
        ha.lights["light.techo"] = ha.lights.getValue("light.techo").copy(brightness = 255)
        ha.playerVolume = 0.6
        ha.calls.clear()
        val next = MeditationRestoreStore(kv, wallClock = { 60_000L })
        assertEquals(RestoreResult.RESTORED, next.restore(HaRoomControl(ha, sleep = {}), late = true))
        assertTrue(ha.calls.none { it.domain == "light" || it.domain == "media_player" })
        assertTrue(ha.calls.none { "${it.domain}.${it.service}" == "scene.turn_on" })
        assertFalse(ha.lights.getValue("light.lampara").isOn)
        assertNull(next.load())
    }

    @Test
    fun anUnreachableHomeAssistantNeverUsesUpTheRetries() = runBlocking {
        val ha = FakeHa()
        val kv = MemoryKeyValue()
        val store = MeditationRestoreStore(kv, wallClock = { 2_000L })
        room(ha, store).prepare()
        ha.online = false
        repeat(MeditationRestoreStore.MAX_ATTEMPTS * 3) {
            assertEquals(RestoreResult.KEPT, store.restore(HaRoomControl(ha, sleep = {}), late = true))
        }
        assertEquals(0, store.attempts)
        ha.online = true
        assertEquals(RestoreResult.RESTORED, store.restore(HaRoomControl(ha, sleep = {}), late = true))
    }
}
