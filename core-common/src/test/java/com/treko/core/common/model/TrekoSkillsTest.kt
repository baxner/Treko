package com.treko.core.common.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrekoSkillsTest {

    @Test
    fun `printer tool is hidden without OctoPrint`() {
        assertFalse(TrekoSkills.isToolAvailable("get_3d_printer_status", emptySet()))
    }

    @Test
    fun `printer tool is offered when OctoPrint is detected`() {
        assertTrue(TrekoSkills.isToolAvailable("get_3d_printer_status", setOf(TrekoSkills.OCTOPRINT)))
    }

    @Test
    fun `core tools never depend on detected integrations`() {
        assertTrue(TrekoSkills.isToolAvailable("view_camera", emptySet()))
        assertTrue(TrekoSkills.isToolAvailable("manage_timer", emptySet()))
    }

    @Test
    fun `camera watches need Frigate or any motion sensor`() {
        assertFalse(TrekoSkills.isToolAvailable("watch_cameras", setOf(TrekoSkills.OCTOPRINT)))
        assertTrue(TrekoSkills.isToolAvailable("watch_cameras", setOf(TrekoSkills.FRIGATE)))
        assertTrue(TrekoSkills.isToolAvailable("watch_cameras", setOf(TrekoSkills.MOTION_SENSORS)))
    }

    @Test
    fun `camera analysis needs LLM Vision`() {
        assertFalse(TrekoSkills.isToolAvailable("analyze_camera", setOf(TrekoSkills.FRIGATE)))
        assertTrue(TrekoSkills.isToolAvailable("analyze_camera", setOf(TrekoSkills.LLM_VISION)))
        assertTrue(TrekoSkills.LLM_VISION in TrekoSkills.KNOWN_INTEGRATIONS)
    }
}
