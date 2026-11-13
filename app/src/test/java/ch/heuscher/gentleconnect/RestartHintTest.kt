package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.RestartHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartHintTest {

    @Test
    fun `update scenario with lastNotHomeBoot unrecorded returns false`() {
        // App updated on a device where it was already the home launcher
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = 42,
                lastNotHomeBoot = -1,
                dismissedBoot = -1
            )
        )
    }

    @Test
    fun `switch this boot returns true`() {
        // App became home launcher during current boot session
        assertTrue(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = 42,
                lastNotHomeBoot = 42,
                dismissedBoot = -1
            )
        )
    }

    @Test
    fun `after reboot returns false`() {
        // Device rebooted after the switch, currentBoot advanced
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = 43,
                lastNotHomeBoot = 42,
                dismissedBoot = -1
            )
        )
    }

    @Test
    fun `dismissed this boot returns false`() {
        // User dismissed the hint during the current boot session
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = 42,
                lastNotHomeBoot = 42,
                dismissedBoot = 42
            )
        )
    }

    @Test
    fun `boot count unavailable returns false`() {
        // Negative or zero boot count means unavailable or uninitialized
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = -1,
                lastNotHomeBoot = -1,
                dismissedBoot = -1
            )
        )
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = true,
                currentBoot = 0,
                lastNotHomeBoot = 0,
                dismissedBoot = -1
            )
        )
    }

    @Test
    fun `not home returns false`() {
        // App is not the home launcher
        assertFalse(
            RestartHint.shouldShow(
                holdsHome = false,
                currentBoot = 42,
                lastNotHomeBoot = 42,
                dismissedBoot = -1
            )
        )
    }
}
