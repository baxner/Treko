package com.treko.core.mcp

import com.treko.core.common.model.TrekoSkills
import com.treko.core.mcp.watch.HaStateLite
import com.treko.core.mcp.watch.WatchCatalog

/** Works out which optional Treko skills this user's Home Assistant supports. */
internal object SkillDetection {

    private val DOMAIN = Regex("[a-z0-9_]+")
    private val MOTION_CLASSES = setOf("motion", "occupancy")

    /** Template printing each installed integration (with at least one entity), space-separated. */
    fun buildTemplate(candidates: List<String>): String? {
        val safe = candidates.filter { it.matches(DOMAIN) }
        if (safe.isEmpty()) return null
        val list = safe.joinToString(",") { "'$it'" }
        return "{% for d in [$list] %}{% if integration_entities(d) | count > 0 %}{{ d }} {% endif %}{% endfor %}"
    }

    fun parseTemplateOutput(output: String, candidates: List<String>): Set<String> =
        output.split(Regex("\\s+")).filter { it in candidates }.toSet()

    /** Skills that only depend on entity attributes, whatever integration made them. */
    fun fromStates(states: List<HaStateLite>): Set<String> {
        val found = mutableSetOf<String>()
        if (states.any { it.entityId.startsWith("binary_sensor.") && it.deviceClass in MOTION_CLASSES }) {
            found += TrekoSkills.MOTION_SENSORS
        }
        return found
    }

    /**
     * Skills recognised by the service domains Home Assistant offers (GET /api/services):
     * LLM Vision may have providers and no entity, so the entity template can miss it.
     */
    fun fromServiceDomains(domains: Set<String>): Set<String> =
        TrekoSkills.SERVICE_DOMAIN_SKILLS.filter { it in domains }.toSet()

    /** Fallback when the template API is unavailable: default entity naming of each integration. */
    fun integrationsFromEntityIds(entityIds: List<String>): Set<String> {
        val found = mutableSetOf<String>()
        if (PrinterStatus.hasDefaultNamedPrinter(entityIds)) {
            found += TrekoSkills.OCTOPRINT
        }
        val cameras = entityIds.filter { it.startsWith("camera.") }.map { it.substringAfter('.') }
        if (entityIds.any { id ->
                val area = if (id.startsWith("binary_sensor.")) WatchCatalog.parseFrigateOccupancy(id)?.first else null
                area != null && cameras.any { cam -> area == cam || area.startsWith("${cam}_") }
            }
        ) {
            found += TrekoSkills.FRIGATE
        }
        return found
    }
}
