package com.treko.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionsTest {

    private fun plan(domain: String, service: String, vararg targets: ActionTarget) =
        ServiceCallPlan(domain, service, targets.toList())

    @Test
    fun `unlocking and disarming are sensitive, locking and arming are not`() {
        val lock = ActionTarget("lock.front_door", "Front door")
        assertEquals(SensitiveKind.UNLOCK, SensitiveActions.classify(plan("lock", "unlock", lock)))
        assertEquals(SensitiveKind.UNLOCK, SensitiveActions.classify(plan("lock", "open", lock)))
        assertNull(SensitiveActions.classify(plan("lock", "lock", lock)))
        val alarm = ActionTarget("alarm_control_panel.home")
        assertEquals(SensitiveKind.DISARM, SensitiveActions.classify(plan("alarm_control_panel", "alarm_disarm", alarm)))
        assertNull(SensitiveActions.classify(plan("alarm_control_panel", "alarm_arm_away", alarm)))
    }

    @Test
    fun `opening a garage or gate cover is sensitive, blinds are not`() {
        val garage = ActionTarget("cover.door_1", "Door 1", "garage")
        val gate = ActionTarget("cover.entrance", "Entrance", "gate")
        val blind = ActionTarget("cover.living_blind", "Living blind", "blind")
        assertEquals(SensitiveKind.OPEN_ACCESS, SensitiveActions.classify(plan("cover", "open_cover", garage)))
        assertEquals(SensitiveKind.OPEN_ACCESS, SensitiveActions.classify(plan("cover", "toggle", gate)))
        assertNull(SensitiveActions.classify(plan("cover", "close_cover", garage)))
        assertNull(SensitiveActions.classify(plan("cover", "open_cover", blind)))
        assertEquals(SensitiveKind.OPEN_ACCESS, SensitiveActions.classify(plan("homeassistant", "turn_on", garage)))
        assertNull(SensitiveActions.classify(plan("homeassistant", "turn_on", blind)))
    }

    @Test
    fun `a cover without device class is judged by generic words, and no target is sensitive`() {
        assertEquals(
            SensitiveKind.OPEN_ACCESS,
            SensitiveActions.classify(plan("cover", "open_cover", ActionTarget("cover.porton_principal", "Portón principal")))
        )
        assertNull(SensitiveActions.classify(plan("cover", "open_cover", ActionTarget("cover.persiana", "Persiana"))))
        // An area or "all": it may include the garage.
        assertEquals(SensitiveKind.OPEN_ACCESS, SensitiveActions.classify(plan("cover", "open_cover")))
        assertNull(SensitiveActions.classify(plan("light", "turn_on", ActionTarget("light.garage", "Garage light"))))
    }

    @Test
    fun `entity ids are split and all is dropped`() {
        assertEquals(listOf("lock.a", "lock.b"), SensitiveActions.splitEntityIds(" lock.a , lock.b ,"))
        assertEquals(emptyList<String>(), SensitiveActions.splitEntityIds("all"))
    }

    @Test
    fun `plan key ignores target order`() {
        val a = ActionTarget("lock.a")
        val b = ActionTarget("lock.b")
        assertEquals(plan("lock", "unlock", a, b).key, plan("lock", "unlock", b, a).key)
    }

    @Test
    fun `first call asks, confirmed second call within the window runs once`() {
        var now = 1_000L
        val guard = SensitiveActionGuard(windowMs = 60_000L, clock = { now })
        val key = plan("lock", "unlock", ActionTarget("lock.front_door")).key
        assertTrue(guard.check(key, confirmed = false) is SensitiveActionGuard.Decision.Confirm)
        assertEquals(1, guard.pendingCount())
        now += 20_000L
        assertEquals(SensitiveActionGuard.Decision.Proceed, guard.check(key, confirmed = true))
        // Consumed: confirming again asks again.
        assertTrue(guard.check(key, confirmed = true) is SensitiveActionGuard.Decision.Confirm)
    }

    @Test
    fun `confirm without a pending action, for another target or too late asks again`() {
        var now = 0L
        val guard = SensitiveActionGuard(windowMs = 60_000L, clock = { now })
        val front = plan("lock", "unlock", ActionTarget("lock.front_door")).key
        val back = plan("lock", "unlock", ActionTarget("lock.back_door")).key
        // The model passes confirm=true straight away: still asks first.
        assertTrue(guard.check(front, confirmed = true) is SensitiveActionGuard.Decision.Confirm)
        // A different target does not use the pending confirmation.
        assertTrue(guard.check(back, confirmed = true) is SensitiveActionGuard.Decision.Confirm)
        now += 61_000L
        assertTrue(guard.check(front, confirmed = true) is SensitiveActionGuard.Decision.Confirm)
        assertEquals(1, guard.pendingCount())
    }

    @Test
    fun `every confirmation request gets a new token`() {
        val guard = SensitiveActionGuard(clock = { 0L })
        val key = plan("alarm_control_panel", "alarm_disarm").key
        val first = guard.check(key, confirmed = false) as SensitiveActionGuard.Decision.Confirm
        val second = guard.check(key, confirmed = false) as SensitiveActionGuard.Decision.Confirm
        assertTrue(first.token != second.token)
        assertEquals(1, guard.pendingCount())
    }
}
