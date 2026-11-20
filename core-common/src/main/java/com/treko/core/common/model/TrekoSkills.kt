package com.treko.core.common.model

/**
 * Optional abilities that depend on what this user's Home Assistant has.
 *
 * Treko runs against anyone's Home Assistant: a skill's tools are offered to the model
 * only when the skill is detected, so a home without OctoPrint never sees printer tools.
 */
object TrekoSkills {
    /** OctoPrint integration: 3D printer status. */
    const val OCTOPRINT = "octoprint"

    /** Frigate integration: object detection per camera and zone. */
    const val FRIGATE = "frigate"

    /** Any motion / occupancy / presence binary sensor (any brand, any integration). */
    const val MOTION_SENSORS = "motion_sensors"

    /** Home Assistant integration domains to look for. */
    val KNOWN_INTEGRATIONS: List<String> = listOf(OCTOPRINT, FRIGATE)

    /** Tool → skills that enable it (any one is enough). */
    private val TOOL_REQUIREMENTS: Map<String, Set<String>> = mapOf(
        "get_3d_printer_status" to setOf(OCTOPRINT),
        "watch_cameras" to setOf(FRIGATE, MOTION_SENSORS)
    )

    fun requiredSkills(toolName: String): Set<String>? = TOOL_REQUIREMENTS[toolName]

    /** Tools without a requirement are always available. */
    fun isToolAvailable(toolName: String, detectedSkills: Set<String>): Boolean =
        requiredSkills(toolName)?.let { needed -> needed.any { it in detectedSkills } } ?: true
}
