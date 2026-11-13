package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AgitationTracker
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.ui.components.ReactionTapZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReactionTapZone] state tracking and its effect on agitation burst detection.
 *
 * Tapping a reaction emoji (e.g. laughing face 😂 or heart ❤️) repeatedly in quick succession
 * is a natural expression of joy or humor, familiar from emoji keyboards and chat reactions.
 * Without exclusion, 4 taps within 2.5 seconds trigger [AgitationTrigger.BURST_TAPS] ("schnelles Tippen").
 * By identifying touches landing on reaction emoji zones and excluding them from the tap count in `recordTouch`,
 * joyful emoji reactions are preserved without false agitation alarms.
 */
class ReactionTapZoneTest {

    @Test
    fun `touchStarted resets landedOnZone to false`() {
        ReactionTapZone.markHit()
        assertTrue(ReactionTapZone.landedOnZone())

        ReactionTapZone.touchStarted()
        assertFalse(ReactionTapZone.landedOnZone())
    }

    @Test
    fun `markHit sets landedOnZone to true`() {
        ReactionTapZone.touchStarted()
        assertFalse(ReactionTapZone.landedOnZone())

        ReactionTapZone.markHit()
        assertTrue(ReactionTapZone.landedOnZone())
    }

    @Test
    fun `rapid emoji reaction taps filtered through ReactionTapZone do not trigger burst taps`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Simulate 4 rapid taps on the laughing emoji 😂 (every 250ms, about the Fold incident's pace)
        for (i in 0 until 4) {
            val tapTime = baseTime + i * 250L

            ReactionTapZone.touchStarted()
            ReactionTapZone.markHit() // Landed on reaction emoji key

            if (!ReactionTapZone.landedOnZone()) {
                tracker.onTap(tapTime, hourOfDay = 16)
            }
        }

        // None of the reaction emoji taps should be counted as burst taps
        assertFalse(tracker.isRestless(baseTime + 1000L))
    }

    @Test
    fun `rapid taps outside ReactionTapZone still trigger burst taps`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        var lastTrigger: AgitationTrigger? = null
        for (i in 0 until 4) {
            val tapTime = baseTime + i * 500L
            ReactionTapZone.touchStarted()

            // Outside reaction zone (e.g. frantic tapping on disabled area)
            if (!ReactionTapZone.landedOnZone()) {
                val trigger = tracker.onTap(tapTime, hourOfDay = 16)
                if (trigger != null) lastTrigger = trigger
            }
        }

        assertEquals(AgitationTrigger.BURST_TAPS, lastTrigger)
        assertTrue(tracker.isRestless(baseTime + 1500L))
    }

    @Test
    fun `message send tap followed by reaction emoji burst does not trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // 1. Send button tapped (not in reaction zone)
        ReactionTapZone.touchStarted()
        if (!ReactionTapZone.landedOnZone()) {
            assertNull(tracker.onTap(baseTime, hourOfDay = 16))
        }

        // 2. 4 rapid taps on laughing emoji 5 seconds later
        for (i in 0 until 4) {
            val tapTime = baseTime + 5000L + i * 250L
            ReactionTapZone.touchStarted()
            ReactionTapZone.markHit()
            if (!ReactionTapZone.landedOnZone()) {
                tracker.onTap(tapTime, hourOfDay = 16)
            }
        }

        // Only 1 tap counted in total (the send button), well below threshold
        assertFalse(tracker.isRestless(baseTime + 6000L))
    }
}
