package com.treko.assistant.meditation

import com.treko.core.mcp.rooms.HaArea
import com.treko.core.mcp.rooms.HaAreaPlayer
import com.treko.core.mcp.rooms.PhoneReason
import com.treko.core.mcp.rooms.PlaceDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeditationPlannerTest {

    private fun player(id: String, alexa: Boolean = false) = HaAreaPlayer(id, "idle", alexa)

    private val bedroom = HaArea("dormitorio", "Recámara", listOf(player("media_player.echo_dormitorio", alexa = true)), listOf("light.dormitorio"))
    private val living = HaArea("sala", "Sala", listOf(player("media_player.tv")), listOf("light.sala", "light.lampara"))
    private val garage = HaArea("cochera", "Cochera", emptyList(), listOf("light.cochera"))
    private val office = HaArea("oficina", "Oficina", listOf(player("media_player.bocina_oficina")), emptyList())
    private val storage = HaArea("bodega", "Bodega", emptyList(), emptyList())
    private val areas = listOf(bedroom, living, garage, office, storage)

    private val all = MeditationRoomSettings(adjustLights = true, playMusic = true)

    @Test
    fun candidatesKeepAreasWithSomethingTheSessionWillUse() {
        assertNull(MeditationPlanner.candidates(null, all))
        assertEquals(listOf("dormitorio", "sala", "cochera", "oficina"), MeditationPlanner.candidates(areas, all)!!.map { it.id })
        // Music off: only areas with lights; lights off: only areas with a player.
        assertEquals(listOf("dormitorio", "sala", "cochera"), MeditationPlanner.candidates(areas, all.copy(playMusic = false))!!.map { it.id })
        assertEquals(listOf("dormitorio", "sala", "oficina"), MeditationPlanner.candidates(areas, all.copy(adjustLights = false))!!.map { it.id })
    }

    @Test
    fun noHomeAssistantOrNoRoomsUsesThePhoneWithoutAsking() {
        assertEquals(PlaceDecision.UsePhone(PhoneReason.NO_HOME_ASSISTANT), MeditationPlanner.decide(null, all, null, null))
        assertEquals(PlaceDecision.UsePhone(PhoneReason.NO_ROOMS), MeditationPlanner.decide(listOf(storage), all, null, null))
        // Nothing to adjust in any room: no question either.
        val nothing = MeditationRoomSettings(adjustLights = false, playMusic = false)
        assertTrue(MeditationPlanner.decide(areas, nothing, null, null) is PlaceDecision.UsePhone)
    }

    @Test
    fun aSingleRoomIsUsedAndAnnounced() {
        val d = MeditationPlanner.decide(listOf(living, storage), all, null, null)
        assertEquals(PlaceDecision.UseArea(living, announce = true), d)
    }

    @Test
    fun severalRoomsAskWithTheLastPlaceAsSuggestion() {
        val d = MeditationPlanner.decide(areas, all, null, "sala") as PlaceDecision.Ask
        assertEquals(listOf("Recámara", "Sala", "Cochera", "Oficina"), d.options)
        assertEquals("Sala", d.suggestion)
        assertNull(d.unmatched)
        // A last place that no longer exists is not suggested.
        assertNull((MeditationPlanner.decide(areas, all, null, "terraza") as PlaceDecision.Ask).suggestion)
    }

    @Test
    fun theAnswerIsMatchedLooselyOrAskedAgain() {
        assertEquals(PlaceDecision.UseArea(bedroom, announce = false), MeditationPlanner.decide(areas, all, "en la recamara", null))
        assertEquals(PlaceDecision.UseArea(living, announce = false), MeditationPlanner.decide(areas, all, "Estoy en la SALA", null))
        assertEquals(PlaceDecision.UsePhone(PhoneReason.USER_CHOSE_PHONE), MeditationPlanner.decide(areas, all, "aquí, solo el teléfono", null))
        val again = MeditationPlanner.decide(areas, all, "el jardín", null) as PlaceDecision.Ask
        assertEquals("el jardín", again.unmatched)
    }

    @Test
    fun anAnswerKeepsTypeAndMinutesOfTheStartThatAsked() {
        val pending = MeditationPlanner.PendingStart(MeditationType.SLEEP, 15, atMs = 1_000L)
        assertEquals(MeditationType.SLEEP to 15, MeditationPlanner.resolveStart(null, null, pending, nowMs = 60_000L))
        // What the new call says wins.
        assertEquals(MeditationType.BOX_BREATHING to 5, MeditationPlanner.resolveStart("respiracion_caja", 5, pending, 60_000L))
        // An old question is forgotten.
        assertEquals(
            MeditationType.MINDFULNESS to 10,
            MeditationPlanner.resolveStart(null, null, pending, 1_000L + MeditationPlanner.PENDING_START_TTL_MS + 1)
        )
    }

    @Test
    fun meditationHabitsAreRecognised() {
        listOf("Meditar", "Meditación 10 min", "meditation", "Respiración consciente", "Morning mindfulness").forEach {
            assertTrue(it, MeditationPlanner.isMeditationHabit(it))
        }
        listOf("Tomar agua", "Leer", "Ejercicio").forEach { assertFalse(it, MeditationPlanner.isMeditationHabit(it)) }
    }
}
