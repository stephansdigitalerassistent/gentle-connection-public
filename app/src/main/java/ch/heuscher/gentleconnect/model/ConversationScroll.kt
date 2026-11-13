package ch.heuscher.gentleconnect.model

/**
 * Whether an open conversation should be moved back down to its end.
 *
 * A conversation opens on its newest message, scrolled so that the *bottom* of that message is at
 * the bottom of the screen. That is done once, the moment the list has its messages — and at that
 * moment the newest bubble is frequently not yet its final height. A still is still downloading; a
 * film's length is still being read off the disk. Each of those adds to the bubble afterwards, and
 * whatever was at the bottom of it slides underneath the edge of the screen: the picture, the
 * button that plays the film, the button that reads the message out.
 *
 * She has no way of knowing any of that is there. The bubble simply ends at the edge of the glass,
 * which is what a bubble that has ended looks like. This is the same failure the initial scroll was
 * written to fix — it fixed the version caused by long text and left the version caused by things
 * that arrive late.
 *
 * So the end of the list is followed down while it grows. Two rules keep that from becoming a
 * screen that moves on its own:
 *
 * - **Only growth.** A bubble that reports a smaller height — because it scrolled halfway out of
 *   view, because the list was measured mid-change — is not a reason to move anything.
 * - **Only until she touches it.** The first time she scrolls the conversation herself, this stops
 *   for good. A screen that pulls itself back down while she is reading upwards is worse than a
 *   button she has to scroll to find, and she would have no idea what she had done to cause it.
 *
 * The keyboard is the other half of the same subject — see [shrunkBy].
 */
object ConversationScroll {

    /**
     * @param lastBubbleHeight how tall the newest bubble is now, in pixels, or null when it is not
     *   on the screen at all — she has scrolled far up, and nothing here applies.
     * @param heightWhenLastFollowed the height it had the last time this said yes; null before the
     *   first time.
     * @param sheHasScrolled whether she has moved the conversation with her own thumb. Taken from
     *   the drag rather than from the scroll position, because this screen scrolls itself and must
     *   never mistake its own work for hers.
     */
    fun shouldFollowToEnd(
        lastBubbleHeight: Int?,
        heightWhenLastFollowed: Int?,
        sheHasScrolled: Boolean
    ): Boolean {
        if (sheHasScrolled) return false
        if (lastBubbleHeight == null || lastBubbleHeight <= 0) return false
        if (heightWhenLastFollowed == null) return true
        return lastBubbleHeight > heightWhenLastFollowed
    }

    /**
     * How much of the conversation the window has just taken away, in pixels.
     *
     * The keyboard comes up and takes nearly half the screen. A list keeps its *top* where it was
     * when that happens, so everything it loses goes off the bottom — which is exactly where the
     * message she tapped Answer under was sitting. She reported it plainly: "when the keyboard
     * comes up, the message i want to answer is hidden by it."
     *
     * Scrolling the same distance the window lost puts the bottom edge back where she left it, so
     * the last thing she read stays the last thing she can see. It is deliberately about the
     * window and not about the end of the list: if she has scrolled up to answer something from
     * Tuesday, Tuesday is what stays on the screen, rather than the conversation jumping to the
     * newest message because she happened to touch the keyboard.
     *
     * Only what is taken away. The keyboard going down hands the room back at the bottom, and the
     * list showing more underneath what she is reading is the right thing for it to do.
     *
     * @param previousHeight the height the list had room for a moment ago, or null on the first
     *   measurement — nothing has been taken away yet.
     */
    fun shrunkBy(previousHeight: Int?, height: Int): Int {
        if (previousHeight == null || previousHeight <= 0) return 0
        if (height <= 0) return 0
        return (previousHeight - height).coerceAtLeast(0)
    }
}
