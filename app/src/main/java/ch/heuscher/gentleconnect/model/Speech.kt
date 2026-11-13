package ch.heuscher.gentleconnect.model

/**
 * How fast she is read to.
 *
 * Android's own default is 1.0, which is the speed of somebody who already knows what the
 * sentence is going to say. Read aloud at that speed, a message from her daughter arrives as one
 * long sound: she hears that somebody spoke, asks for it again, and hears the same thing. The
 * words are not the problem and neither is the volume — it is that there is no gap between them
 * to hold one in.
 *
 * So the default here is slower than Android's, and it is a caregiver setting rather than a
 * constant because how slow is right is a fact about one particular person's hearing on one
 * particular day. Somebody who finds 0.8 laboured can be put back to 1.0 from the website
 * without anybody touching the phone.
 *
 * The engine takes anything and says nothing about it. A rate of 0 or a negative one is not a
 * slow voice, it is silence — and silence from this app looks exactly like the read-aloud button
 * being broken. [sane] is the one place a number from outside is made into a speed, so that a
 * mistyped field on a website cannot take her voice away.
 */
object Speech {

    /** Slower than Android's 1.0, and the speed the phone reads at until somebody changes it. */
    const val DEFAULT_RATE = 0.8f

    /** Slower than this is not gentler, it is a drawl that is harder to follow, not easier. */
    const val SLOWEST_RATE = 0.5f

    /** Android's default is 1.0; a little above it is allowed for somebody who finds that slow. */
    const val FASTEST_RATE = 1.2f

    /**
     * The speed to actually hand the engine, given whatever arrived from the website.
     *
     * Anything that is not a usable speed — absent, not a number, zero, negative, infinite —
     * becomes [DEFAULT_RATE] rather than being passed on. A number that is merely too far in one
     * direction is pulled back to the nearest end instead, because somebody who typed 0.3 meant
     * "as slow as it goes" and should get it.
     */
    fun sane(rate: Float?): Float {
        if (rate == null || rate.isNaN() || rate.isInfinite() || rate <= 0f) return DEFAULT_RATE
        return rate.coerceIn(SLOWEST_RATE, FASTEST_RATE)
    }
}
