package com.treko.core.mcp.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaRoomsTest {

    private val sample = """
        recamara|Recámara|media_player.echo_recamara=idle=1,media_player.tv_recamara=off=0|light.lampara,light.techo
        sala|Sala de estar|media_player.sala=unavailable=0|
        bano|Baño||light.bano
        garage|Garage||
        broken line
        |sin id|x|y
    """.trimIndent()

    private val areas = HaRooms.parseAreas(sample)

    @Test
    fun parseAreasReadsPlayersLightsAndAlexaFlag() {
        assertEquals(listOf("recamara", "sala", "bano", "garage"), areas.map { it.id })
        val bedroom = areas[0]
        assertEquals("Recámara", bedroom.name)
        assertEquals(listOf("light.lampara", "light.techo"), bedroom.lights)
        assertEquals(2, bedroom.mediaPlayers.size)
        assertTrue(bedroom.mediaPlayers[0].isAlexa)
        assertFalse(bedroom.mediaPlayers[1].isAlexa)
        assertTrue(areas[2].mediaPlayers.isEmpty())
    }

    @Test
    fun roomCandidatesKeepOnlyAreasWithPlayerOrLight() {
        assertEquals(listOf("recamara", "sala", "bano"), HaRooms.roomCandidates(areas).map { it.id })
    }

    @Test
    fun preferredPlayerSkipsUnavailableAndPrefersAlexa() {
        assertEquals("media_player.echo_recamara", areas[0].preferredPlayer(preferAlexa = true)?.entityId)
        // An "off" player (a TV) can still be turned on to play, so it counts.
        val tvFirst = areas[0].copy(mediaPlayers = areas[0].mediaPlayers.reversed())
        assertEquals("media_player.tv_recamara", tvFirst.preferredPlayer(preferAlexa = false)?.entityId)
        assertNull(areas[1].preferredPlayer(preferAlexa = true))
    }

    @Test
    fun matchPlaceIgnoresAccentsCaseAndFillerWords() {
        val c = HaRooms.roomCandidates(areas)
        assertEquals("recamara", (HaRooms.matchPlace("en la RECAMARA", c) as PlaceMatch.Found).area.id)
        assertEquals("bano", (HaRooms.matchPlace("estoy en el baño", c) as PlaceMatch.Found).area.id)
        assertEquals("sala", (HaRooms.matchPlace("la sala", c) as PlaceMatch.Found).area.id)
        assertEquals("recamara", (HaRooms.matchPlace("recam", c) as PlaceMatch.Found).area.id)
    }

    @Test
    fun matchPlaceUsesGenericSynonymsAndTypos() {
        val c = HaRooms.roomCandidates(areas)
        assertEquals("recamara", (HaRooms.matchPlace("en el dormitorio", c) as PlaceMatch.Found).area.id)
        assertEquals("recamara", (HaRooms.matchPlace("bedroom", c) as PlaceMatch.Found).area.id)
        assertEquals("recamara", (HaRooms.matchPlace("recamra", c) as PlaceMatch.Found).area.id)
        assertEquals(PlaceMatch.None, HaRooms.matchPlace("cocina", c))
    }

    @Test
    fun matchPlaceReportsAmbiguityAndPhoneAnswers() {
        val rooms = listOf(
            HaArea("cuarto_ana", "Cuarto de Ana", emptyList(), listOf("light.a")),
            HaArea("cuarto_luis", "Cuarto de Luis", emptyList(), listOf("light.b"))
        )
        val m = HaRooms.matchPlace("el cuarto", rooms)
        assertTrue(m is PlaceMatch.Ambiguous)
        assertEquals(2, (m as PlaceMatch.Ambiguous).candidates.size)
        assertEquals("cuarto_luis", (HaRooms.matchPlace("cuarto de luis", rooms) as PlaceMatch.Found).area.id)
        assertEquals(PlaceMatch.PhoneOnly, HaRooms.matchPlace("aquí", rooms))
        assertEquals(PlaceMatch.PhoneOnly, HaRooms.matchPlace("solo el teléfono", rooms))
        assertEquals(PlaceMatch.PhoneOnly, HaRooms.matchPlace("just the phone", rooms))
    }

    @Test
    fun decidePlaceUsesPhoneWithoutHomeAssistantOrRooms() {
        assertEquals(
            PlaceDecision.UsePhone(PhoneReason.NO_HOME_ASSISTANT),
            HaRooms.decidePlace(false, HaRooms.roomCandidates(areas), "recámara", null)
        )
        assertEquals(PlaceDecision.UsePhone(PhoneReason.NO_ROOMS), HaRooms.decidePlace(true, emptyList(), null, null))
    }

    @Test
    fun decidePlaceUsesTheOnlyRoomAndAnnouncesIt() {
        val only = listOf(areas[2])
        val d = HaRooms.decidePlace(true, only, null, null)
        assertEquals(PlaceDecision.UseArea(areas[2], announce = true), d)
    }

    @Test
    fun decidePlaceAsksWhenSeveralAndNoPlaceGiven() {
        val c = HaRooms.roomCandidates(areas)
        val d = HaRooms.decidePlace(true, c, null, lastUsed = "recamara")
        assertTrue(d is PlaceDecision.Ask)
        d as PlaceDecision.Ask
        assertEquals(listOf("Recámara", "Sala de estar", "Baño"), d.options)
        assertEquals("Recámara", d.suggestion)
        assertNull(d.unmatched)

        val noLast = HaRooms.decidePlace(true, c, "  ", lastUsed = "cocina") as PlaceDecision.Ask
        assertNull(noLast.suggestion)
    }

    @Test
    fun decidePlaceMatchesTheGivenPlaceOrAsksAgain() {
        val c = HaRooms.roomCandidates(areas)
        assertEquals(PlaceDecision.UseArea(areas[0], announce = false), HaRooms.decidePlace(true, c, "recámara", null))
        assertEquals(PlaceDecision.UsePhone(PhoneReason.USER_CHOSE_PHONE), HaRooms.decidePlace(true, c, "aquí", null))
        val again = HaRooms.decidePlace(true, c, "cocina", null) as PlaceDecision.Ask
        assertEquals("cocina", again.unmatched)
        assertEquals(3, again.options.size)
    }

    // ---- Lights ----

    private val onCt = LightSnapshot(
        "light.techo", true, brightness = 200, colorMode = "color_temp", colorTempKelvin = 4000,
        supportedColorModes = listOf("color_temp")
    )
    private val onHs = LightSnapshot(
        "light.tira", true, brightness = 120, colorMode = "hs", hsColor = listOf(240.0, 80.0),
        effect = "rainbow", supportedColorModes = listOf("hs", "color_temp")
    )
    private val dimOnly = LightSnapshot("light.lampara", true, brightness = 30, colorMode = "brightness", supportedColorModes = listOf("brightness"))
    private val off = LightSnapshot("light.pasillo", false, supportedColorModes = listOf("color_temp"))
    private val onOff = LightSnapshot("light.foco", true, colorMode = "onoff", supportedColorModes = listOf("onoff"))
    private val group = LightSnapshot(
        "light.todas", true, brightness = 200, colorMode = "color_temp", supportedColorModes = listOf("color_temp"),
        members = listOf("light.techo", "light.tira")
    )

    @Test
    fun lightsToAdjustLeavesOffOnOffAndOverlappingGroupsAlone() {
        val picked = HaRooms.lightsToAdjust(listOf(onCt, onHs, dimOnly, off, onOff, group)).map { it.entityId }
        assertEquals(listOf("light.techo", "light.tira", "light.lampara"), picked)
    }

    @Test
    fun planDimNeverTurnsOnOffLightsOrBrightens() {
        val calls = HaRooms.planDim(listOf(onCt, dimOnly, off), kelvin = 2400, brightnessPercent = 22, transitionSeconds = 4)
        assertEquals(2, calls.size)
        assertTrue(calls.none { it.data["entity_id"] == "light.pasillo" })
        val ceiling = calls.first { it.data["entity_id"] == "light.techo" }
        assertEquals("turn_on", ceiling.service)
        assertEquals(56, ceiling.data["brightness"])
        assertEquals(2400, ceiling.data["color_temp_kelvin"])
        assertEquals(4, ceiling.data["transition"])
        val lamp = calls.first { it.data["entity_id"] == "light.lampara" }
        assertEquals(30, lamp.data["brightness"]) // already dimmer than the target
        assertFalse(lamp.data.containsKey("color_temp_kelvin")) // cannot show colour temperature
    }

    @Test
    fun planRestoreUsesTheColourOfEachColourMode() {
        val calls = HaRooms.planRestore(listOf(onCt, onHs, dimOnly, off), transitionSeconds = 3)
        val ceiling = calls[0].data
        assertEquals(200, ceiling["brightness"])
        assertEquals(4000, ceiling["color_temp_kelvin"])
        assertFalse(ceiling.containsKey("hs_color"))
        val strip = calls[1].data
        assertEquals(listOf(240.0, 80.0), strip["hs_color"])
        assertEquals("rainbow", strip["effect"])
        assertFalse(strip.containsKey("color_temp_kelvin"))
        assertEquals(setOf("entity_id", "transition", "brightness"), calls[2].data.keys)
        assertEquals("turn_off", calls[3].service)
    }

    @Test
    fun restorePlanForTheDimmedLightsNeverMentionsOffLights() {
        val touched = HaRooms.lightsToAdjust(listOf(onCt, off, onOff))
        val calls = HaRooms.planRestore(touched)
        assertEquals(listOf("light.techo"), calls.map { it.data["entity_id"] })
        assertTrue(calls.all { it.service == "turn_on" })
    }

    @Test
    fun parseLightsReadsTemplateOutput() {
        val out = "light.techo|on|180|color_temp|2700||||||None|color_temp,hs|\n" +
            "light.tira|on|99.6|xy|None|30.0,60.0|255,128,0|0.5,0.4|||None|xy|\n" +
            "light.pasillo|off|None|None|None||||||None|color_temp|\n" +
            "light.rota|unavailable|None|None|None||||||None||\n" +
            "light.grupo|on|200|brightness|None||||||None|brightness|light.techo,light.tira"
        val lights = HaRooms.parseLights(out)
        assertEquals(listOf("light.techo", "light.tira", "light.pasillo", "light.grupo"), lights.map { it.entityId })
        assertEquals(2700, lights[0].colorTempKelvin)
        assertNull(lights[0].effect)
        assertEquals(100, lights[1].brightness)
        assertEquals(listOf(0.5, 0.4), lights[1].xyColor)
        assertEquals(listOf(255, 128, 0), lights[1].rgbColor)
        assertFalse(lights[2].isOn)
        assertEquals(listOf("light.techo", "light.tira"), lights[3].members)
    }

    @Test
    fun lightsTemplateOnlyEmbedsValidIds() {
        val t = HaRooms.lightsTemplate(listOf("light.ok", "light.bad') }}{{ 1", "LIGHT.x"))
        assertTrue(t.contains("'light.ok'"))
        assertFalse(t.contains("bad"))
    }

    // ---- Volume and record ----

    @Test
    fun volumeFadeStepsEndExactlyAtTheTarget() {
        assertEquals(listOf(0.15, 0.1, 0.05, 0.0), HaRooms.volumeFadeSteps(0.2, 0.0, 4))
        assertEquals(listOf(0.5), HaRooms.volumeFadeSteps(0.1, 0.5, 0))
        assertEquals(listOf(1.0), HaRooms.volumeFadeSteps(0.5, 3.0, 1))
    }

    @Test
    fun parsePlayerReadsStateAndVolume() {
        assertEquals(PlayerSnapshot("media_player.x", "playing", 0.35), HaRooms.parsePlayer("media_player.x", "playing|0.35"))
        assertEquals(PlayerSnapshot("media_player.x", "idle", null), HaRooms.parsePlayer("media_player.x", "idle|None"))
        assertNull(HaRooms.parsePlayer("media_player.x", "unknown|None"))
    }

    @Test
    fun restoreRecordRoundTrips() {
        val record = RoomRestoreRecord(
            sceneEntityId = HaRooms.RESTORE_SCENE_ENTITY,
            lights = listOf(onCt, onHs.copy(effect = "a|b"), dimOnly),
            mediaPlayer = "media_player.echo",
            volume = 0.42,
            startedAtMs = 1234L
        )
        val back = HaRooms.decodeRecord(HaRooms.encodeRecord(record))
        assertNotNull(back)
        assertEquals(record.copy(lights = listOf(onCt, onHs.copy(effect = "a/b"), dimOnly)), back)
    }

    @Test
    fun restoreRecordWithoutSceneOrPlayerRoundTrips() {
        val record = RoomRestoreRecord(sceneEntityId = null, lights = listOf(onCt))
        assertEquals(record, HaRooms.decodeRecord(HaRooms.encodeRecord(record)))
        assertTrue(RoomRestoreRecord(null, emptyList()).isEmpty)
    }

    @Test
    fun decodeRecordRejectsGarbage() {
        assertNull(HaRooms.decodeRecord(null))
        assertNull(HaRooms.decodeRecord(""))
        assertNull(HaRooms.decodeRecord("hola\nscene=x"))
    }
}
