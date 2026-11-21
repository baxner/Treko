package com.treko.core.mcp

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** One entity a Home Assistant call would act on. */
data class ActionTarget(
    val entityId: String,
    val friendlyName: String = "",
    val deviceClass: String = ""
)

/** A Home Assistant service call about to run, with the targets Treko resolved for it. */
data class ServiceCallPlan(
    val domain: String,
    val service: String,
    val targets: List<ActionTarget>
) {
    /** Same action on the same entities, whichever tool asked for it. */
    val key: String
        get() = "$domain.$service|" + targets.map { it.entityId }.distinct().sorted().joinToString(",")
}

/**
 * Looks at a call just before it runs. Null lets it run; any text is returned to the model
 * instead of running it (for example, "ask the user to confirm first").
 */
fun interface ActionGate {
    fun review(plan: ServiceCallPlan): String?
}

enum class SensitiveKind {
    /** lock.unlock / lock.open */
    UNLOCK,
    /** alarm_control_panel.alarm_disarm */
    DISARM,
    /** Opening a garage door, gate or motorised door (cover). */
    OPEN_ACCESS
}

/** Which Home Assistant calls open the house and therefore need the user's confirmation. */
object SensitiveActions {

    /** Cover device classes that let someone in. */
    private val ACCESS_COVER_CLASSES = setOf("garage", "gate", "door")

    /** Generic words for a garage door or gate, used only when the cover has no device class. */
    private val ACCESS_WORDS = listOf("garage", "garaje", "cochera", "porton", "gate", "reja", "zaguan")

    private val COVER_OPENING = setOf("open_cover", "toggle", "set_cover_position")
    private val GENERIC_OPENING = setOf("turn_on", "toggle")

    fun classify(plan: ServiceCallPlan): SensitiveKind? {
        val domain = plan.domain.trim().lowercase(Locale.ROOT)
        val service = plan.service.trim().lowercase(Locale.ROOT)
        return when (domain) {
            "lock" -> if (service == "unlock" || service == "open") SensitiveKind.UNLOCK else null
            "alarm_control_panel" -> if (service == "alarm_disarm") SensitiveKind.DISARM else null
            "cover" -> {
                if (service !in COVER_OPENING) return null
                // No explicit entity (an area, "all"): it may include the garage.
                if (plan.targets.isEmpty() || plan.targets.any { isAccessCover(it) }) SensitiveKind.OPEN_ACCESS else null
            }
            // homeassistant.turn_on / toggle open covers too.
            "homeassistant" -> {
                if (service !in GENERIC_OPENING) return null
                if (plan.targets.any { it.entityId.startsWith("cover.") && isAccessCover(it) }) SensitiveKind.OPEN_ACCESS else null
            }
            else -> null
        }
    }

    internal fun isAccessCover(target: ActionTarget): Boolean {
        val deviceClass = target.deviceClass.trim().lowercase(Locale.ROOT)
        if (deviceClass.isNotEmpty()) return deviceClass in ACCESS_COVER_CLASSES
        val text = normalize("${target.entityId} ${target.friendlyName}")
        return ACCESS_WORDS.any { text.contains(it) }
    }

    /** Entity ids written as "a, b" or a single id; blanks and "all" are dropped. */
    fun splitEntityIds(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }

    private val DIACRITICS = Regex("\\p{Mn}+")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(DIACRITICS, "")
}

/**
 * Two-step confirmation for sensitive calls, enforced in code rather than in the prompt.
 *
 * The first call for an action stores it as pending and answers [Decision.Confirm]: the model
 * must ask the user. Only a second call for the same action ([ServiceCallPlan.key]) with
 * `confirm=true` within [windowMs] runs it, once. `confirm=true` with nothing pending, with a
 * different target, or after the window starts over.
 */
class SensitiveActionGuard(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed interface Decision {
        data object Proceed : Decision
        data class Confirm(val token: String) : Decision
    }

    private data class Pending(val token: String, val atMs: Long)

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun check(key: String, confirmed: Boolean): Decision {
        val now = clock()
        pending.entries.removeAll { now - it.value.atMs > windowMs }
        if (confirmed && pending.remove(key) != null) return Decision.Proceed
        val token = UUID.randomUUID().toString().substring(0, 8)
        pending.remove(key)
        pending[key] = Pending(token, now)
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
        return Decision.Confirm(token)
    }

    /** Pending actions not yet expired (for tests and logs). */
    @Synchronized
    fun pendingCount(): Int {
        val now = clock()
        return pending.values.count { now - it.atMs <= windowMs }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        private const val MAX_PENDING = 8
    }
}
