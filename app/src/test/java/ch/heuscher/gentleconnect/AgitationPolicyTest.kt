package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AgitationPolicy
import ch.heuscher.gentleconnect.model.AgitationTracker
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.QuietHours
import ch.heuscher.gentleconnect.model.TouchOutcome
import ch.heuscher.gentleconnect.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgitationPolicyTest {

    @Test
    fun `isolated taps do not trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertNull(tracker.onTap(baseTime, hourOfDay = 14))
        assertNull(tracker.onTap(baseTime + 1000L, hourOfDay = 14))
        assertNull(tracker.onTap(baseTime + 2000L, hourOfDay = 14))
        assertFalse(tracker.isRestless(baseTime + 2000L))
    }

    @Test
    fun `burst tapping triggers agitation and stays active for 5 minutes`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Four taps inside 2.5 seconds: three are not yet a burst, the fourth is.
        for (i in 0 until 3) {
            assertNull(tracker.onTap(baseTime + i * 600L, hourOfDay = 14))
        }
        val trigger = tracker.onTap(baseTime + 1800L, hourOfDay = 14)
        assertEquals(AgitationTrigger.BURST_TAPS, trigger)
        assertTrue(tracker.isRestless(baseTime + 1800L))

        // Still restless after 4 minutes
        assertTrue(tracker.isRestless(baseTime + 1800L + 4 * 60 * 1000L))

        // Settles down after 5 minutes
        assertFalse(tracker.isRestless(baseTime + 1800L + 5 * 60 * 1000L + 1L))
    }

    @Test
    fun `taps spread wider than the burst window are not a burst`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Four taps, but a second apart: someone using the telephone, not someone hammering it.
        // The window only ever holds three of them, so this must stay quiet — otherwise the
        // beacon lights during ordinary use and stops meaning anything.
        for (i in 0 until 4) {
            assertNull(tracker.onTap(baseTime + i * 1000L, hourOfDay = 14))
        }
        assertFalse(tracker.isRestless(baseTime + 3000L))
    }

    @Test
    fun `repeated calls to the same contact trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertNull(tracker.onCallAttempt("Anna", baseTime))
        assertNull(tracker.onCallAttempt("Anna", baseTime + 2 * 60 * 1000L))
        val trigger = tracker.onCallAttempt("Anna", baseTime + 5 * 60 * 1000L)

        assertEquals(AgitationTrigger.CALL_RETRIES, trigger)
        assertTrue(tracker.isRestless(baseTime + 5 * 60 * 1000L))
    }

    @Test
    fun `calls to different contacts do not count as repeated retries`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertNull(tracker.onCallAttempt("Anna", baseTime))
        assertNull(tracker.onCallAttempt("Karolina", baseTime + 2 * 60 * 1000L))
        assertNull(tracker.onCallAttempt("Johann", baseTime + 5 * 60 * 1000L))
        assertFalse(tracker.isRestless(baseTime + 5 * 60 * 1000L))
    }

    @Test
    fun `unconfirmed support loops trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertNull(tracker.onSupportOpenedNotConfirmed(baseTime))
        val trigger = tracker.onSupportOpenedNotConfirmed(baseTime + 5 * 60 * 1000L)

        assertEquals(AgitationTrigger.UNCONFIRMED_SUPPORT, trigger)
        assertTrue(tracker.isRestless(baseTime + 5 * 60 * 1000L))
    }

    @Test
    fun `nocturnal taps during deep night trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertNull(tracker.onTap(baseTime, hourOfDay = 2))
        assertNull(tracker.onTap(baseTime + 10_000L, hourOfDay = 2))
        val trigger = tracker.onTap(baseTime + 20_000L, hourOfDay = 2)

        assertEquals(AgitationTrigger.NOCTURNAL_ACTIVITY, trigger)
        assertTrue(tracker.isRestless(baseTime + 20_000L))
    }

    @Test
    fun `clear immediately resets restlessness`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        for (i in 0 until 5) {
            tracker.onTap(baseTime + i * 200L, hourOfDay = 14)
        }
        assertTrue(tracker.isRestless(baseTime + 800L))

        tracker.clear()
        assertFalse(tracker.isRestless(baseTime + 800L))
    }

    @Test
    fun `unanswered touches trigger agitation when threshold reached`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Four touches that did nothing: not yet a trigger
        for (i in 0 until 4) {
            assertNull(tracker.onUnansweredTouch(baseTime + i * 1_000L))
        }
        assertFalse(tracker.isRestless(baseTime + 3_000L))

        // Fifth touch within 10 seconds triggers UNANSWERED_TOUCHES
        val trigger = tracker.onUnansweredTouch(baseTime + 4_000L)
        assertEquals(AgitationTrigger.UNANSWERED_TOUCHES, trigger)
        assertTrue(tracker.isRestless(baseTime + 4_000L))
    }

    @Test
    fun `unanswered touches spread wider than 10 seconds do not trigger`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Five touches 2.6 seconds apart = span of 10.4 seconds (oldest drops out)
        for (i in 0 until 5) {
            assertNull(tracker.onUnansweredTouch(baseTime + i * 2_600L))
        }
        assertFalse(tracker.isRestless(baseTime + 10_400L))
    }

    @Test
    fun `an answered touch resets the unanswered touch streak`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Four unanswered touches
        for (i in 0 until 4) {
            assertNull(tracker.onUnansweredTouch(baseTime + i * 500L))
        }

        // An answered touch occurs (screen opened or click handled)
        tracker.onAnsweredTouch(baseTime + 2_000L)

        // Four more unanswered touches: streak restarted, so this must not trigger
        for (i in 0 until 4) {
            assertNull(tracker.onUnansweredTouch(baseTime + 2_500L + i * 500L))
        }
        assertFalse(tracker.isRestless(baseTime + 4_000L))
    }

    @Test
    fun `clear resets unanswered touch streak`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        for (i in 0 until 4) {
            tracker.onUnansweredTouch(baseTime + i * 500L)
        }
        tracker.clear()

        // After clear, four touches do not reach threshold 5
        for (i in 0 until 4) {
            assertNull(tracker.onUnansweredTouch(baseTime + 2_000L + i * 500L))
        }
        assertFalse(tracker.isRestless(baseTime + 3_500L))
    }

    @Test
    fun `every agitation trigger is covered exhaustively`() {
        for (trigger in AgitationTrigger.entries) {
            val label = when (trigger) {
                AgitationTrigger.BURST_TAPS -> "schnelles Tippen"
                AgitationTrigger.CALL_RETRIES -> "wiederholte Anrufe"
                AgitationTrigger.UNCONFIRMED_SUPPORT -> "Hilfe geöffnet, nicht bestätigt"
                AgitationTrigger.NOCTURNAL_ACTIVITY -> "nächtliche Aktivität"
                AgitationTrigger.UNANSWERED_TOUCHES -> "Berührungen ohne Wirkung"
                AgitationTrigger.SAME_SPOT_DEAD_TAPS -> "tippt wiederholt auf dieselbe Stelle"
                AgitationTrigger.EXIT_HUNTING -> "sucht den Weg zurück"
                AgitationTrigger.NAVIGATION_LOOP -> "irrt zwischen Bildschirmen"
                AgitationTrigger.FOLD_CYCLES -> "klappt wiederholt auf und zu"
                AgitationTrigger.SCREEN_CYCLES -> "schaltet den Bildschirm wiederholt ein"
            }
            assertEquals(label, trigger.label)
        }
    }

    @Test
    fun `caregiver logging is throttled to once per 10 minutes`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        assertTrue(tracker.shouldLogToCaregiver(baseTime))
        assertFalse(tracker.shouldLogToCaregiver(baseTime + 5 * 60 * 1000L))
        assertTrue(tracker.shouldLogToCaregiver(baseTime + 10 * 60 * 1000L))
    }

    @Test
    fun `click-through exclusion and chaining with counter`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Initial tap on Home
        val tap1 = TouchSample(
            downAt = baseTime,
            upAt = baseTime + 100L,
            xDp = 100f,
            yDp = 500f,
            screenWidthDp = 360f,
            screen = "Home",
            isTap = true,
            outcome = TouchOutcome.ANSWERED
        )
        assertNull(tracker.onTouch(tap1, hourOfDay = 14))
        assertEquals(0, tracker.clickThroughs)

        // Screen changes to PhoneBook within 200 ms of tap1 up
        tracker.onScreenChange("PhoneBook", baseTime + 300L, hourOfDay = 14)

        // Tap 2 lands on PhoneBook at same area within 400 ms of screen change
        val tap2 = TouchSample(
            downAt = baseTime + 600L,
            upAt = baseTime + 700L,
            xDp = 110f,
            yDp = 505f,
            screenWidthDp = 360f,
            screen = "PhoneBook",
            isTap = true,
            outcome = TouchOutcome.ANSWERED
        )
        assertNull(tracker.onTouch(tap2, hourOfDay = 14))
        assertEquals(1, tracker.clickThroughs)

        // Screen changes to Messages
        tracker.onScreenChange("Messages", baseTime + 900L, hourOfDay = 14)

        // Tap 3 chains from tap2
        val tap3 = TouchSample(
            downAt = baseTime + 1100L,
            upAt = baseTime + 1200L,
            xDp = 115f,
            yDp = 510f,
            screenWidthDp = 360f,
            screen = "Messages",
            isTap = true,
            outcome = TouchOutcome.ANSWERED
        )
        assertNull(tracker.onTouch(tap3, hourOfDay = 14))
        assertEquals(2, tracker.clickThroughs)
    }

    @Test
    fun `same-spot dead taps trigger agitation`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Three unanswered taps on the same screen within 48 dp within 10 s
        val t1 = TouchSample(
            downAt = baseTime,
            upAt = baseTime + 50L,
            xDp = 200f,
            yDp = 300f,
            screenWidthDp = 360f,
            screen = "Home",
            isTap = true,
            outcome = TouchOutcome.UNANSWERED
        )
        val t2 = TouchSample(
            downAt = baseTime + 2000L,
            upAt = baseTime + 2050L,
            xDp = 210f,
            yDp = 310f,
            screenWidthDp = 360f,
            screen = "Home",
            isTap = true,
            outcome = TouchOutcome.UNANSWERED
        )
        val t3 = TouchSample(
            downAt = baseTime + 4000L,
            upAt = baseTime + 4050L,
            xDp = 205f,
            yDp = 295f,
            screenWidthDp = 360f,
            screen = "Home",
            isTap = true,
            outcome = TouchOutcome.UNANSWERED
        )

        assertNull(tracker.onTouch(t1, hourOfDay = 14))
        assertNull(tracker.onTouch(t2, hourOfDay = 14))
        val trigger = tracker.onTouch(t3, hourOfDay = 14)
        assertEquals(AgitationTrigger.SAME_SPOT_DEAD_TAPS, trigger)
    }

    @Test
    fun `exit hunting via top corners and back without effect`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // 1. Exit hunting via top-left corner taps
        val c1 = TouchSample(baseTime, baseTime + 50L, 50f, 60f, 360f, "Look", true, TouchOutcome.UNANSWERED)
        val c2 = TouchSample(baseTime + 1000L, baseTime + 1050L, 60f, 70f, 360f, "Look", true, TouchOutcome.UNANSWERED)
        val c3 = TouchSample(baseTime + 2000L, baseTime + 2050L, 40f, 80f, 360f, "Look", true, TouchOutcome.UNANSWERED)

        assertNull(tracker.onTouch(c1, hourOfDay = 14))
        assertNull(tracker.onTouch(c2, hourOfDay = 14))
        val triggerCorners = tracker.onTouch(c3, hourOfDay = 14)
        assertEquals(AgitationTrigger.EXIT_HUNTING, triggerCorners)

        tracker.clear()

        // 2. Exit hunting via onBackWithoutEffect
        assertNull(tracker.onBackWithoutEffect(baseTime + 10_000L, hourOfDay = 14))
        assertNull(tracker.onBackWithoutEffect(baseTime + 12_000L, hourOfDay = 14))
        val triggerBack = tracker.onBackWithoutEffect(baseTime + 14_000L, hourOfDay = 14)
        assertEquals(AgitationTrigger.EXIT_HUNTING, triggerBack)
    }

    @Test
    fun `priority EXIT_HUNTING over SAME_SPOT_DEAD_TAPS on corner taps`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Three taps at (50, 50) on screen "Look": they qualify as both top-corner exit taps AND dead taps in the same spot
        val t1 = TouchSample(baseTime, baseTime + 50L, 50f, 50f, 360f, "Look", true, TouchOutcome.UNANSWERED)
        val t2 = TouchSample(baseTime + 1000L, baseTime + 1050L, 50f, 50f, 360f, "Look", true, TouchOutcome.UNANSWERED)
        val t3 = TouchSample(baseTime + 2000L, baseTime + 2050L, 50f, 50f, 360f, "Look", true, TouchOutcome.UNANSWERED)

        assertNull(tracker.onTouch(t1, hourOfDay = 14))
        assertNull(tracker.onTouch(t2, hourOfDay = 14))
        val trigger = tracker.onTouch(t3, hourOfDay = 14)
        assertEquals(AgitationTrigger.EXIT_HUNTING, trigger)
    }

    @Test
    fun `fold cycles track transitions and ignore first or duplicate reports`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // First report is not a transition
        assertNull(tracker.onHinge(closed = true, baseTime, hourOfDay = 14))
        // Same state is not a transition
        assertNull(tracker.onHinge(closed = true, baseTime + 1000L, hourOfDay = 14))
        // Transition 1: open
        assertNull(tracker.onHinge(closed = false, baseTime + 2000L, hourOfDay = 14))
        // Transition 2: shut
        assertNull(tracker.onHinge(closed = true, baseTime + 3000L, hourOfDay = 14))
        // Transition 3: open (records FOLD_CYCLES 2 pts, which by day does not fire alone)
        assertNull(tracker.onHinge(closed = false, baseTime + 4000L, hourOfDay = 14))
    }

    @Test
    fun `screen cycles ignore screen-on within 2 seconds of hinge change`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Hinge changes to open
        tracker.onHinge(closed = true, baseTime, hourOfDay = 14)
        tracker.onHinge(closed = false, baseTime + 1000L, hourOfDay = 14)

        // Screen-on 500 ms after hinge open: ignored
        assertNull(tracker.onScreenOn(baseTime + 1500L, hourOfDay = 14))

        // Three genuine screen-ons
        assertNull(tracker.onScreenOn(baseTime + 5000L, hourOfDay = 14))
        assertNull(tracker.onScreenOn(baseTime + 20_000L, hourOfDay = 14))
        // 3rd screen-on records SCREEN_CYCLES (2 pts, does not fire alone by day)
        assertNull(tracker.onScreenOn(baseTime + 40_000L, hourOfDay = 14))
    }

    @Test
    fun `navigation loop records 2 points on 5 screens and resets on dwell over 3 seconds`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // 4 screen transitions < 3s apart
        tracker.onScreenChange("Home", baseTime, hourOfDay = 14)
        tracker.onScreenChange("PhoneBook", baseTime + 1000L, hourOfDay = 14)
        tracker.onScreenChange("Messages", baseTime + 2000L, hourOfDay = 14)
        tracker.onScreenChange("Look", baseTime + 3000L, hourOfDay = 14)

        // Pause 4 seconds (> 3s dwell) resets the streak
        tracker.onScreenChange("Home", baseTime + 7000L, hourOfDay = 14)
        tracker.onScreenChange("PhoneBook", baseTime + 8000L, hourOfDay = 14)
        tracker.onScreenChange("Messages", baseTime + 9000L, hourOfDay = 14)
        tracker.onScreenChange("Look", baseTime + 10_000L, hourOfDay = 14)
        // 5th consecutive rapid screen change: records NAVIGATION_LOOP (2 pts)
        assertNull(tracker.onScreenChange("Home", baseTime + 11_000L, hourOfDay = 14))
    }

    @Test
    fun `score needs 3 points so two 2-point detections fire by day`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Navigation loop (2 points)
        for (i in 0 until 5) {
            tracker.onScreenChange("Screen$i", baseTime + i * 1000L, hourOfDay = 14)
        }
        assertFalse(tracker.isRestless(baseTime + 4000L))

        // Followed by 3 hinge transitions (2 points) within 2 minutes
        tracker.onHinge(true, baseTime + 10_000L, hourOfDay = 14)
        tracker.onHinge(false, baseTime + 12_000L, hourOfDay = 14)
        tracker.onHinge(true, baseTime + 14_000L, hourOfDay = 14)
        val trigger = tracker.onHinge(false, baseTime + 16_000L, hourOfDay = 14)

        // 2 + 2 = 4 points >= 3 FIRE_SCORE -> fires!
        assertNotNull(trigger)
        assertTrue(tracker.isRestless(baseTime + 16_000L))
    }

    @Test
    fun `quiet-hour doubling lets 2-point soft signs fire alone and does not double nocturnal activity`() {
        val qh = QuietHours.alwaysQuiet(setOf(22))
        val tracker = AgitationTracker(qh)
        val baseTime = 1_000_000L

        // In hour 22 (quiet hour), navigation loop base 2 points is doubled to 4 points -> fires alone!
        for (i in 0 until 4) {
            assertNull(tracker.onScreenChange("Screen$i", baseTime + i * 1000L, hourOfDay = 22))
        }
        val trigger = tracker.onScreenChange("Screen4", baseTime + 4000L, hourOfDay = 22)
        assertEquals(AgitationTrigger.NAVIGATION_LOOP, trigger)

        // Points for nocturnal activity is not doubled
        assertEquals(3, AgitationPolicy.pointsFor(AgitationTrigger.NOCTURNAL_ACTIVITY, isQuietHour = true))
        assertEquals(3, AgitationPolicy.pointsFor(AgitationTrigger.NOCTURNAL_ACTIVITY, isQuietHour = false))
    }

    @Test
    fun `onGoal resolves within 15 seconds and clears restlessness`() {
        val tracker = AgitationTracker()
        val baseTime = 1_000_000L

        // Trigger burst taps
        for (i in 0 until 3) {
            tracker.onTap(baseTime + i * 600L, hourOfDay = 14)
        }
        val trigger = tracker.onTap(baseTime + 1800L, hourOfDay = 14)
        assertEquals(AgitationTrigger.BURST_TAPS, trigger)
        assertTrue(tracker.isRestless(baseTime + 1800L))

        // Goal reached 10 seconds later: within 15 seconds -> resolved!
        assertTrue(tracker.onGoal(baseTime + 11_800L))
        assertFalse(tracker.isRestless(baseTime + 11_800L))

        // Subsequent goal with no active fire -> false
        assertFalse(tracker.onGoal(baseTime + 12_000L))

        // Fire again
        for (i in 0 until 3) {
            tracker.onTap(baseTime + 20_000L + i * 600L, hourOfDay = 14)
        }
        tracker.onTap(baseTime + 21_800L, hourOfDay = 14)
        assertTrue(tracker.isRestless(baseTime + 21_800L))

        // Goal reached 20 seconds later (> 15 s) -> returns false, but still clears restlessness
        assertFalse(tracker.onGoal(baseTime + 41_800L))
        assertFalse(tracker.isRestless(baseTime + 41_800L))
    }

    @Test
    fun `taps that made one detection are not counted again by another rule`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        fun corner(at: Long, x: Float, y: Float) =
            TouchSample(at, at + 50L, x, y, 360f, "Look", true, TouchOutcome.UNANSWERED)

        assertNull(tracker.onTouch(corner(t, 50f, 60f), hourOfDay = 14))
        assertNull(tracker.onTouch(corner(t + 1000L, 60f, 70f), hourOfDay = 14))
        assertEquals(AgitationTrigger.EXIT_HUNTING, tracker.onTouch(corner(t + 2000L, 40f, 80f), hourOfDay = 14))

        // Without clearing, the three corner taps plus this one would make a same-spot detection
        // and fire again on taps that have already been reported.
        assertNull(tracker.onTouch(corner(t + 3000L, 50f, 70f), hourOfDay = 14))
    }

    private fun deadTap(at: Long, x: Float, y: Float, width: Float = 640f, screen: String = "Home") =
        TouchSample(at, at + 50L, x, y, width, screen, true, TouchOutcome.UNANSWERED)

    @Test
    fun `dead taps on the outermost left and right edge are grip touches and fire nothing`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        // Five dead taps in 10 s along both rims, three of them in the same spot: none counts.
        listOf(2f to 400f, 9f to 410f, 20f to 405f, 620f to 300f, 637f to 310f).forEachIndexed { i, (x, y) ->
            assertNull(tracker.onTouch(deadTap(t + i * 1000L, x, y), hourOfDay = 14))
        }
        assertEquals(5, tracker.gripTouches)
        assertFalse(tracker.isRestless(t + 6000L))
    }

    @Test
    fun `a dead tap just inside the grip strip still counts`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        val x = AgitationPolicy.EDGE_GRIP_DP + 1f
        assertNull(tracker.onTouch(deadTap(t, x, 400f), hourOfDay = 14))
        assertNull(tracker.onTouch(deadTap(t + 2000L, x, 405f), hourOfDay = 14))
        assertEquals(AgitationTrigger.SAME_SPOT_DEAD_TAPS, tracker.onTouch(deadTap(t + 4000L, x, 410f), hourOfDay = 14))
        assertEquals(0, tracker.gripTouches)
    }

    @Test
    fun `the right edge is only a grip strip when the window width is known`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        assertNull(tracker.onTouch(deadTap(t, 630f, 400f, width = 0f), hourOfDay = 14))
        assertNull(tracker.onTouch(deadTap(t + 2000L, 630f, 405f, width = 0f), hourOfDay = 14))
        assertEquals(
            AgitationTrigger.SAME_SPOT_DEAD_TAPS,
            tracker.onTouch(deadTap(t + 4000L, 630f, 410f, width = 0f), hourOfDay = 14)
        )
    }

    @Test
    fun `grip touches in a top corner still count as exit hunting`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        assertNull(tracker.onTouch(deadTap(t, 10f, 60f, screen = "Look"), hourOfDay = 14))
        assertNull(tracker.onTouch(deadTap(t + 1000L, 630f, 70f, screen = "Look"), hourOfDay = 14))
        assertEquals(
            AgitationTrigger.EXIT_HUNTING,
            tracker.onTouch(deadTap(t + 2000L, 12f, 80f, screen = "Look"), hourOfDay = 14)
        )
    }

    @Test
    fun `grip touches do not make up a burst`() {
        val tracker = AgitationTracker()
        val t = 1_000_000L
        assertNull(tracker.onTouch(deadTap(t, 5f, 400f), hourOfDay = 14))
        assertNull(tracker.onTouch(deadTap(t + 300L, 635f, 300f), hourOfDay = 14))
        assertNull(tracker.onTouch(deadTap(t + 600L, 8f, 500f), hourOfDay = 14))
        val answered = TouchSample(t + 900L, t + 950L, 300f, 400f, 640f, "Home", true, TouchOutcome.ANSWERED)
        assertNull(tracker.onTouch(answered, hourOfDay = 14))
    }
}
