package ch.heuscher.gentleconnect.model

/**
 * The faces under a message, and what the last of them is.
 *
 * Five, and always five: they share the width of the screen rather than taking a fixed size, and
 * the row is the one way of answering that never raises a keyboard. Which five was decided by
 * what her family actually sends.
 *
 * **The chooser costs the last one, and only the last one.** Where somebody looking after her has
 * turned it on — see [RemoteSettings.enableEmojiChoice] — the fifth slot stops being the laughing
 * face and becomes the way to any of the others. The first four do not move: a row whose contents
 * shuffled when a setting changed would mean the heart she reaches for without looking is
 * somewhere else, and reaching without looking is the whole value of a fixed row.
 *
 * Off by default. Four faces and a door is a smaller row than five faces, and for most of what she
 * answers the five are enough; whether she wants more of them is a judgement about one particular
 * person, which is the caregiver's to make and not this file's.
 */
object QuickReactions {

    /** The five, in the order they are drawn. */
    val FACES: List<String> = listOf("❤️", "👍", "😊", "🙏", "😂")

    /** How many circles are in the row, with or without the chooser. */
    const val SLOTS: Int = 5

    /**
     * The faces to draw. All five normally; the first four when the fifth slot is the door to the
     * rest, so the row is still [SLOTS] wide either way.
     */
    fun faces(canChooseEmoji: Boolean): List<String> =
        if (canChooseEmoji) FACES.take(SLOTS - 1) else FACES
}
