package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ConversationScroll.shouldFollowToEnd
import ch.heuscher.gentleconnect.model.ConversationScroll.shrunkBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the bottom of her newest message on the screen.
 *
 * Worth pinning because both of its halves fail invisibly. Get it too cautious and a film's play
 * button sits under the edge of the glass with nothing to say it is there — which is the bug this
 * was written for. Get it too eager and the conversation drags itself downwards while she is
 * reading up through it, and she has no idea what she pressed to cause that.
 */
class ConversationScrollTest {

    @Test
    fun `the first measurement of the newest bubble is followed`() {
        assertTrue(shouldFollowToEnd(400, null, sheHasScrolled = false))
    }

    @Test
    fun `a bubble that has grown is followed`() {
        // A still finished downloading, or a film's length arrived from the disk.
        assertTrue(shouldFollowToEnd(920, 400, sheHasScrolled = false))
    }

    @Test
    fun `a bubble that has not changed is left alone`() {
        // Every measurement after the last one settles reports this, including the ones caused by
        // the scroll itself. Following them would be a loop.
        assertFalse(shouldFollowToEnd(400, 400, sheHasScrolled = false))
    }

    @Test
    fun `a bubble that reports smaller is left alone`() {
        assertFalse(shouldFollowToEnd(120, 400, sheHasScrolled = false))
    }

    @Test
    fun `nothing follows once she has scrolled it herself`() {
        assertFalse(shouldFollowToEnd(920, 400, sheHasScrolled = true))
        assertFalse(shouldFollowToEnd(400, null, sheHasScrolled = true))
    }

    @Test
    fun `a newest bubble that is not on the screen is not chased`() {
        assertFalse(shouldFollowToEnd(null, 400, sheHasScrolled = false))
        assertFalse(shouldFollowToEnd(null, null, sheHasScrolled = false))
    }

    @Test
    fun `a bubble measured at nothing is not a bubble yet`() {
        // What the list reports for an item it has placed but not yet measured.
        assertFalse(shouldFollowToEnd(0, null, sheHasScrolled = false))
    }

    @Test
    fun `the keyboard taking half the screen is scrolled back`() {
        // 1800 pixels of conversation, then the keyboard: the 780 that went are the ones the
        // message she is answering was sitting in.
        assertEquals(780, shrunkBy(1800, 1020))
    }

    @Test
    fun `every frame of the keyboard coming up is followed`() {
        // The window shrinks over an animation, not in one step, and each step is scrolled for —
        // otherwise the list lands wherever the last frame happened to leave it.
        assertEquals(200, shrunkBy(1800, 1600))
        assertEquals(300, shrunkBy(1600, 1300))
        assertEquals(280, shrunkBy(1300, 1020))
    }

    @Test
    fun `the keyboard going away is not scrolled for`() {
        // The room comes back underneath what she is reading, which is where it should go.
        assertEquals(0, shrunkBy(1020, 1800))
    }

    @Test
    fun `the first measurement has taken nothing away`() {
        assertEquals(0, shrunkBy(null, 1800))
        assertEquals(0, shrunkBy(0, 1800))
    }

    @Test
    fun `a window measured at nothing is not a window`() {
        // Between screens, and while the list is being laid out for the first time.
        assertEquals(0, shrunkBy(1800, 0))
    }
}
