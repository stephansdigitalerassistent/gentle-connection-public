package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.QuickReactions
import ch.heuscher.gentleconnect.model.QuietHours
import ch.heuscher.gentleconnect.model.RestlessReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestlessReplayTest {

    @Test
    fun `replaying SAME_SPOT fixture detects same spot dead taps`() {
        val result = RestlessReplay.run(RestlessReplayFixtures.SAME_SPOT)
        val episodes = result.episodes()
        assertFalse(episodes.isEmpty())
        val first = episodes.first()
        assertEquals(AgitationTrigger.SAME_SPOT_DEAD_TAPS, first.trigger)
        assertEquals("15:52:12.412", first.clock)
    }

    @Test
    fun `replaying EXIT_HUNTING fixture detects exit hunting in top corners`() {
        val result = RestlessReplay.run(
            RestlessReplayFixtures.EXIT_HUNTING,
            screenWidthDp = 360f
        )
        val episodes = result.episodes()
        assertFalse(episodes.isEmpty())
        val first = episodes.first()
        assertEquals(AgitationTrigger.EXIT_HUNTING, first.trigger)
        assertEquals("20:25:14.053", first.clock)
    }

    @Test
    fun `replaying CLICK_THROUGH with default quiet hours produces no fires and counts click throughs`() {
        val result = RestlessReplay.run(RestlessReplayFixtures.CLICK_THROUGH)
        assertTrue(result.fires.isEmpty())
        assertEquals(2, result.clickThroughs)
    }

    @Test
    fun `replaying CLICK_THROUGH with quiet hour 0 triggers nocturnal activity`() {
        val qh = QuietHours.alwaysQuiet(setOf(0))
        val result = RestlessReplay.run(RestlessReplayFixtures.CLICK_THROUGH, quietHours = qh)
        val episodes = result.episodes()
        assertFalse(episodes.isEmpty())
        assertEquals(AgitationTrigger.NOCTURNAL_ACTIVITY, episodes.first().trigger)
    }

    @Test
    fun `replaying CALM produces no fires`() {
        val result = RestlessReplay.run(RestlessReplayFixtures.CALM)
        assertTrue(result.fires.isEmpty())
    }

    @Test
    fun `synthetic trail navigation loop alone does not fire by day but fires with another soft sign or in quiet hours`() {
        // 5 screen changes 1 s apart at 14:00 (daytime)
        val navLoopLines = listOf(
            "14:00:00.000 screen Home",
            "14:00:01.000 screen PhoneBook",
            "14:00:02.000 screen Messages",
            "14:00:03.000 screen Look",
            "14:00:04.000 screen Home"
        )
        val dayResult = RestlessReplay.run(navLoopLines)
        assertTrue("Navigation loop alone (2 pts) must not fire by day", dayResult.fires.isEmpty())

        // Combine nav loop with 3 hinge transitions (2 pts) within 2 minutes
        val combinedLines = navLoopLines + listOf(
            "14:00:10.000 event [Home] hinge shut",
            "14:00:12.000 event [Home] hinge open",
            "14:00:14.000 event [Home] hinge shut",
            "14:00:16.000 event [Home] hinge open"
        )
        val combinedResult = RestlessReplay.run(combinedLines)
        assertFalse("2 + 2 points must fire by day", combinedResult.fires.isEmpty())

        // In a quiet hour, nav loop alone fires
        val quietResult = RestlessReplay.run(
            navLoopLines,
            quietHours = QuietHours.alwaysQuiet(setOf(14))
        )
        assertFalse("Nav loop doubled to 4 pts must fire alone in quiet hour", quietResult.fires.isEmpty())
        assertEquals(AgitationTrigger.NAVIGATION_LOOP, quietResult.fires.first().trigger)
    }

    @Test
    fun `logcat format lines parse identically to trail lines`() {
        // Take the 8 lines of SAME_SPOT covering the dead taps
        val last8 = RestlessReplayFixtures.SAME_SPOT.takeLast(8)
        val logcatLines = last8.map { line ->
            val clock = line.substring(0, 12)
            val body = line.substring(13)
            "10-24 $clock 1234 5678 I UiTrace : $body"
        }

        val result = RestlessReplay.run(logcatLines)
        val episodes = result.episodes()
        assertFalse(episodes.isEmpty())
        val first = episodes.first()
        assertEquals(AgitationTrigger.SAME_SPOT_DEAD_TAPS, first.trigger)
        assertEquals("15:52:12.412", first.clock)
    }

    @Test
    fun `replaying REACTION_BURST produces no fires`() {
        val result = RestlessReplay.run(RestlessReplayFixtures.REACTION_BURST)
        assertTrue("Reaction emoji bursts must not trigger agitation", result.fires.isEmpty())
    }

    @Test
    fun `a burst on any face of the reaction row produces no fires`() {
        QuickReactions.FACES.forEach { face ->
            val result = RestlessReplay.run(fourTapsOn("click \"$face\""))
            assertTrue("A burst on $face must not fire", result.fires.isEmpty())
        }
    }

    /** "🔊 Vorlesen" and the chooser's "🙂 +" start with an emoji but are not faces in the row. */
    @Test
    fun `a burst on a button whose label starts with an emoji still fires`() {
        listOf("🔊 Vorlesen", "🙂 +").forEach { label ->
            val result = RestlessReplay.run(fourTapsOn("click \"$label\""))
            assertEquals(label, AgitationTrigger.BURST_TAPS, result.fires.singleOrNull()?.trigger)
        }
    }

    /** A 😂 printed in a message is only a picture: tapping it is a dead tap, not a reaction. */
    @Test
    fun `dead taps on a face that cannot be pressed still fire`() {
        val result = RestlessReplay.run(fourTapsOn("view \"😂\""))
        assertEquals(AgitationTrigger.SAME_SPOT_DEAD_TAPS, result.fires.first().trigger)
    }

    @Test
    fun `rim touches while holding the unfolded Fold produce no fires`() {
        val result = RestlessReplay.run(RestlessReplayFixtures.EDGE_GRIP, screenWidthDp = 640f)
        assertTrue(result.fires.isEmpty())
        assertEquals(4, result.gripTouches)
    }

    /** Four taps a quarter of a second apart, all on [target]. */
    private fun fourTapsOn(target: String): List<String> = listOf(0, 2, 5, 7).flatMap { tenths ->
        listOf(
            "14:00:00.${tenths}00 down [Conversation/main] 295,605dp -> $target",
            "14:00:00.${tenths}80 up   [Conversation/main] tap 80ms 0dp"
        )
    }
}
