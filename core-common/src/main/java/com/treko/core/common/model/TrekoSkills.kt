package com.treko.core.common.model

/**
 * Optional abilities that depend on a Home Assistant integration being installed.
 *
 * Treko runs against anyone's Home Assistant: a skill's tools are offered to the model
 * only when its integration is detected, so a home without OctoPrint never sees printer tools.
 * Each skill id is the Home Assistant integration domain.
 */
object TrekoSkills {
    const val OCTOPRINT = "octoprint"

    /** Integrations to look for in Home Assistant. */
    val KNOWN: List<String> = listOf(OCTOPRINT)

    private val TOOL_REQUIREMENTS: Map<String, String> = mapOf(
        "get_3d_printer_status" to OCTOPRINT
    )

    fun requiredSkill(toolName: String): String? = TOOL_REQUIREMENTS[toolName]

    /** Tools without a requirement are always available. */
    fun isToolAvailable(toolName: String, detectedSkills: Set<String>): Boolean =
        requiredSkill(toolName)?.let { it in detectedSkills } ?: true
}
