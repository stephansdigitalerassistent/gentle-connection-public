package ch.heuscher.gentleconnect.model

/**
 * Where she is in something too long to see at once, as two fractions of a track.
 *
 * She has 1424 contacts. Scrolled into the middle of them, every screen looks exactly like every
 * other screen: the same rows, the same buttons, no edge in sight. Nothing on the screen says
 * whether she is near the top, near the bottom, or a thousand names from either — so "go back to
 * where I was" and "am I nearly there" are questions the phone simply does not answer. The same
 * is true of a help text that is taller than its box.
 *
 * A bar is the answer rather than a count ("120 of 1424") because it needs no reading and no
 * arithmetic. Its length says how much there is; its position says where in it she is.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object ScrollIndicator {

    /**
     * @param thumbFraction how much of the track the bar fills, 0..1
     * @param startFraction where the top of the bar sits, 0..1 of the *remaining* track
     */
    data class Bar(
        val thumbFraction: Float,
        val startFraction: Float
    )

    /**
     * For a block of text that scrolls by pixels.
     *
     * Null when it all fits, so nothing is drawn. A scroll bar beside text that does not scroll
     * is a control that does nothing, and this screen has already had one of those.
     */
    fun forText(scrollValue: Int, maxValue: Int, viewportSize: Int): Bar? {
        if (maxValue <= 0 || viewportSize <= 0) return null

        val content = viewportSize + maxValue
        val visible = viewportSize.toFloat() / content
        val progress = scrollValue.toFloat() / maxValue

        return bar(visible, progress)
    }

    /**
     * For a list that scrolls by rows.
     *
     * Counted in rows rather than pixels because that is what a lazy list knows: it has not
     * measured the thousand rows below the fold and cannot say how tall they are. Rows are close
     * enough — the bar is for orientation, not for measuring.
     */
    fun forList(firstVisibleIndex: Int, visibleCount: Int, totalCount: Int): Bar? {
        if (totalCount <= 0 || visibleCount <= 0) return null
        if (visibleCount >= totalCount) return null

        val visible = visibleCount.toFloat() / totalCount
        val lastPossibleFirst = totalCount - visibleCount
        val progress = firstVisibleIndex.toFloat() / lastPossibleFirst

        return bar(visible, progress)
    }

    /**
     * For a magnified / zoomed screen.
     *
     * @param offset current pan offset in pixels (<= 0)
     * @param scale current zoom scale (>= 1.0)
     * @param viewportSize screen dimension along this axis in pixels
     */
    fun forZoom(offset: Float, scale: Float, viewportSize: Float): Bar? {
        if (scale <= 1f || viewportSize <= 0f) return null
        val slack = viewportSize * (scale - 1f)
        if (slack <= 0f) return null

        val visible = 1f / scale
        val progress = (-offset / slack).coerceIn(0f, 1f)

        return bar(visible, progress)
    }

    private fun bar(visibleFraction: Float, progress: Float): Bar {
        // A thousand contacts five at a time makes an honest bar about four pixels tall, which is
        // to say invisible — and an indicator she cannot see is the same as no indicator. Below
        // the floor it stops being to scale and only reports position, which is the part she is
        // actually asking about.
        val thumb = visibleFraction.coerceIn(SHORTEST_WORTH_SEEING, 1f)
        val start = progress.coerceIn(0f, 1f) * (1f - thumb)
        return Bar(thumbFraction = thumb, startFraction = start)
    }

    /** As a fraction of the track. Roughly a fingertip on a phone-sized list. */
    private const val SHORTEST_WORTH_SEEING = 0.08f
}
