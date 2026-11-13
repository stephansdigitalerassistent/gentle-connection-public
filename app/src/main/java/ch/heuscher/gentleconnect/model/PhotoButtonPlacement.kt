package ch.heuscher.gentleconnect.model

/**
 * Where the photo button sits on the home screen.
 *
 * Configured per phone on the caregiver website:
 * - [NONE]: No Home button (person-first photo sending in conversations and dialogs stays).
 * - [CLOCK]: A tile in the clock row, in the left slot beside the clock. If the payment card is
 *   shown there, falls back to [CARD] via [effective] so two actions never compete for one slot.
 * - [CARD]: Full-width key directly under the clock row, outside the scrolling column (default).
 * - [FACES]: A key after the favourite faces, scrolling with the content.
 * - [BOTTOM]: A full-width key pinned directly above the bottom navigation bar on Home.
 */
object PhotoButtonPlacement {
    const val NONE = "none"
    const val CLOCK = "clock"
    const val CARD = "card"
    const val FACES = "faces"
    const val BOTTOM = "bottom"

    /** Anything unknown or blank reads as [CARD], which is the default. */
    fun sane(value: String?): String = when (value?.trim()?.lowercase()) {
        NONE -> NONE
        CLOCK -> CLOCK
        CARD -> CARD
        FACES -> FACES
        BOTTOM -> BOTTOM
        else -> CARD
    }

    /**
     * The effective placement on screen, resolving slot competition with the payment card.
     *
     * The clock row's left slot cannot hold both the payment card and the photo button without
     * crushing the clock or displacing the balance. When [paymentCardShown] is true, [CLOCK] falls
     * back to [CARD].
     */
    fun effective(placement: String?, paymentCardShown: Boolean): String {
        val normalized = sane(placement)
        return if (normalized == CLOCK && paymentCardShown) CARD else normalized
    }
}
