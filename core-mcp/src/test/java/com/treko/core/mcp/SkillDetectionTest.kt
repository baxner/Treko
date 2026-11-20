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
            SkillDetection.fromEntityIds(listOf("light.sala", "sensor.octoprint_job_percentage"))
        )
        // A camera alone named octoprint is not the integration.
        assertEquals(emptySet<String>(), SkillDetection.fromEntityIds(listOf("camera.octoprint_camera", "light.sala")))
    }
}
