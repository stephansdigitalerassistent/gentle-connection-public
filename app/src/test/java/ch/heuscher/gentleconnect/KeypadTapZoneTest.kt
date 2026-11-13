package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AgitationPolicy
import ch.heuscher.gentleconnect.model.AgitationTracker
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.ui.components.KeypadTapZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [KeypadTapZone] state tracking and its effect on agitation burst detection.
 *
 * Fast dialing of a telephone number (e.g. 10 digits in 4-5 seconds) would otherwise trip
 * [AgitationTrigger.BURST_TAPS] ("schnelles Tippen"), which looks for 4 taps inside 2.5 seconds.
 * By identifying touches landing on the dialpad and counting them as no tap in `recordTouch`, genuine dialing
 * is preserved without triggering false agitation alarms.
 */
class KeypadTapZoneTest {

    @Test
    fun `touchStarted resets landedOnZone to false`() {
        KeypadTapZone.markHit()
        assertTrue(KeypadTapZone.landedOnZone())

        KeypadTapZone.touchStarted()
        assertFalse(KeypadTapZone.landedOnZone())
    }

    @Test
    fun `markHit sets landedOnZone to true`() {
        KeypadTapZone.touchStarted()
        assertFalse(KeypadTapZone.landedOnZone())

        KeypadTapZone.markHit()
        assertTrue(KeypadTapZone.landedOnZone())
    }

    @Test
    fun `rapid dialing sequence filtered through KeypadTapZone does not trigger burst taps`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Simulate dialing 10 digits fast (every 400ms):
        // 10:51:10.868 -> 0
        // 10:51:11.391 -> 3
        // 10:51:11.870 -> 1
        // ...
        for (i in 0 until 10) {
            val tapTime = baseTime + i * 400L

            // Touch begins: reset zone hit
            KeypadTapZone.touchStarted()

            // Down dispatched to Compose: hits keypadZone
            KeypadTapZone.markHit()

            // Up gesture: if not landed on zone, the touch counts as a tap
            if (!KeypadTapZone.landedOnZone()) {
                tracker.onTap(tapTime, hourOfDay = 10)
            }
        }

        // None of the keypad keystrokes should be counted as burst taps
        assertFalse(tracker.isRestless(baseTime + 4000L))
    }

    @Test
    fun `rapid taps outside KeypadTapZone still trigger burst taps`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Four rapid taps outside the keypad zone (e.g. frantic tapping on disabled area)
        var lastTrigger: AgitationTrigger? = null
        for (i in 0 until 4) {
            val tapTime = baseTime + i * 500L
            KeypadTapZone.touchStarted()

            // No markHit() because touch is outside keypad zone
            if (!KeypadTapZone.landedOnZone()) {
                val trigger = tracker.onTap(tapTime, hourOfDay = 10)
                if (trigger != null) lastTrigger = trigger
            }
        }

        assertEquals(AgitationTrigger.BURST_TAPS, lastTrigger)
        assertTrue(tracker.isRestless(baseTime + 1500L))
    }

    @Test
    fun `navigation taps before dialing do not exceed burst threshold`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // 1. Tap "Call" on Home (outside keypad zone)
        KeypadTapZone.touchStarted()
        if (!KeypadTapZone.landedOnZone()) {
            assertNull(tracker.onTap(baseTime, hourOfDay = 10))
        }

        // 2. Tap "Keypad" on PhoneBook (outside keypad zone, 1 second later)
        KeypadTapZone.touchStarted()
        if (!KeypadTapZone.landedOnZone()) {
            assertNull(tracker.onTap(baseTime + 1000L, hourOfDay = 10))
        }

        // 3. Dial digits "0", "3", "1", "9" rapidly on Keypad (each within 500ms)
        for (i in 0 until 4) {
            val tapTime = baseTime + 1500L + i * 400L
            KeypadTapZone.touchStarted()
            KeypadTapZone.markHit() // Landed on keypad key
            if (!KeypadTapZone.landedOnZone()) {
                tracker.onTap(tapTime, hourOfDay = 10)
            }
        }

        // Only 2 taps were counted in tracker (the two navigation taps), well below threshold of 4
        assertFalse(tracker.isRestless(baseTime + 3000L))
    }
}
