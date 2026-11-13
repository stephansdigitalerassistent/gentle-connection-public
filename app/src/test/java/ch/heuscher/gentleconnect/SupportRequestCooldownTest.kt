package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportRequestCooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportRequestCooldownTest {

    @Test
    fun `canReAlert returns false within 5 minutes of last alert`() {
        val lastAlertAt = 1_000_000L

        // Immediately after
        assertFalse(SupportRequestCooldown.canReAlert(lastAlertAt, lastAlertAt))

        // 4 min 59 sec after
        assertFalse(SupportRequestCooldown.canReAlert(lastAlertAt, lastAlertAt + 4 * 60 * 1000L + 59_000L))

        // 1 millisecond before 5 minutes
        assertFalse(SupportRequestCooldown.canReAlert(lastAlertAt, lastAlertAt + SupportRequestCooldown.COOLDOWN_MS - 1L))
    }

    @Test
    fun `canReAlert returns true at exactly 5 minutes or later`() {
        val lastAlertAt = 1_000_000L

        // Exactly 5 minutes
        assertTrue(SupportRequestCooldown.canReAlert(lastAlertAt, lastAlertAt + SupportRequestCooldown.COOLDOWN_MS))

        // 6 minutes
        assertTrue(SupportRequestCooldown.canReAlert(lastAlertAt, lastAlertAt + 6 * 60 * 1000L))
    }

    @Test
    fun `remainingCooldownMs calculates correct remaining duration and bounds to 0`() {
        val lastAlertAt = 1_000_000L

        // Immediately after: full 5 minutes
        assertEquals(SupportRequestCooldown.COOLDOWN_MS, SupportRequestCooldown.remainingCooldownMs(lastAlertAt, lastAlertAt))

        // 2 minutes later: 3 minutes remaining
        assertEquals(3L * 60 * 1000, SupportRequestCooldown.remainingCooldownMs(lastAlertAt, lastAlertAt + 2 * 60 * 1000L))

        // Exactly 5 minutes: 0
        assertEquals(0L, SupportRequestCooldown.remainingCooldownMs(lastAlertAt, lastAlertAt + SupportRequestCooldown.COOLDOWN_MS))

        // 10 minutes later: 0
        assertEquals(0L, SupportRequestCooldown.remainingCooldownMs(lastAlertAt, lastAlertAt + 10 * 60 * 1000L))
    }
}
