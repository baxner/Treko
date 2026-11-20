package com.treko.core.mcp

import com.treko.core.common.model.TrekoSkills

/** Works out which optional Treko skills this user's Home Assistant supports. */
internal object SkillDetection {

    private val DOMAIN = Regex("[a-z0-9_]+")

    /** Template printing each installed integration (with at least one entity), space-separated. */
    fun buildTemplate(candidates: List<String>): String? {
        val safe = candidates.filter { it.matches(DOMAIN) }
        if (safe.isEmpty()) return null
        val list = safe.joinToString(",") { "'$it'" }
        return "{% for d in [$list] %}{% if integration_entities(d) | count > 0 %}{{ d }} {% endif %}{% endfor %}"
    }

    fun parseTemplateOutput(output: String, candidates: List<String>): Set<String> =
        output.split(Regex("\\s+")).filter { it in candidates }.toSet()

    /** Fallback when the template API is unavailable: default entity naming of each integration. */
    fun fromEntityIds(entityIds: List<String>): Set<String> {
        val found = mutableSetOf<String>()
        if (entityIds.any { PrinterStatus.looksLikeOctoPrint(it) && (it.startsWith("sensor.") || it.startsWith("binary_sensor.")) }) {
            found += TrekoSkills.OCTOPRINT
        }
        return found
    }
}
