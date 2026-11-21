package com.treko.core.mcp

import com.treko.core.common.model.TrekoSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDetectionTest {

    @Test
    fun `template asks only for safe integration names`() {
        val template = SkillDetection.buildTemplate(listOf("octoprint", "bad'name"))!!
        assertTrue(template, template.contains("['octoprint']"))
        assertNull(SkillDetection.buildTemplate(listOf("x'); drop")))
    }

    @Test
    fun `template output keeps only known integrations`() {
        assertEquals(
            setOf("octoprint"),
            SkillDetection.parseTemplateOutput(" octoprint \n", listOf("octoprint", "frigate"))
        )
        assertEquals(emptySet<String>(), SkillDetection.parseTemplateOutput("", listOf("octoprint")))
    }

    @Test
    fun `fallback finds OctoPrint by its default entity names`() {
        assertEquals(
            setOf(TrekoSkills.OCTOPRINT),
            SkillDetection.integrationsFromEntityIds(listOf("light.sala", "sensor.octoprint_job_percentage"))
        )
        // A camera alone named octoprint is not the integration.
        assertEquals(emptySet<String>(), SkillDetection.integrationsFromEntityIds(listOf("camera.octoprint_camera", "light.sala")))
    }

    @Test
    fun `fallback recognises Frigate by camera occupancy sensors`() {
        assertEquals(
            setOf(TrekoSkills.FRIGATE),
            SkillDetection.integrationsFromEntityIds(listOf("camera.jardin", "binary_sensor.jardin_person_occupancy"))
        )
    }

    @Test
    fun `any brand of motion sensor enables the generic watch skill`() {
        val states = listOf(
            com.treko.core.mcp.watch.HaStateLite("binary_sensor.patio_motion", "off", "Patio", "motion"),
            com.treko.core.mcp.watch.HaStateLite("binary_sensor.puerta", "off", "Puerta", "door")
        )
        assertEquals(setOf(TrekoSkills.MOTION_SENSORS), SkillDetection.fromStates(states))
        assertEquals(emptySet<String>(), SkillDetection.fromStates(states.drop(1)))
    }

    @Test
    fun `fallback recognises Frigate even when the device was renamed`() {
        assertEquals(
            setOf(TrekoSkills.FRIGATE),
            SkillDetection.integrationsFromEntityIds(listOf("camera.patio", "binary_sensor.patio_patio_person_occupancy"))
        )
    }

    @Test
    fun `LLM Vision and Alexa are recognised by their services`() {
        assertEquals(
            setOf(TrekoSkills.LLM_VISION, TrekoSkills.ALEXA_MEDIA),
            SkillDetection.fromServiceDomains(setOf("light", "llmvision", "alexa_media", "notify"))
        )
        assertEquals(emptySet<String>(), SkillDetection.fromServiceDomains(setOf("light", "camera")))
    }
}
