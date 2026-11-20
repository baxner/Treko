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
}
