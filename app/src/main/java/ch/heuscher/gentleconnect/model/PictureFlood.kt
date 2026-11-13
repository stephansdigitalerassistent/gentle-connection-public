package ch.heuscher.gentleconnect.model

/**
 * What stops one person's camera roll from taking her telephone.
 *
 * Read off her phone: Martin Nauer put up 35 pictures in nine seconds. On the Look screen that is
 * now one album — see [AlbumLayout] — but the same dump can arrive as messages, and there it costs
 * her three things that have nothing to do with tidiness:
 *
 *  - **Her home screen.** It has room for two messages ([Home.LIST_LENGTH]), so twelve pictures
 *    from one person take both rows and her daughter's message never reaches the screen at all.
 *  - **Her quiet.** The phone says somebody has written, once per message — twelve notices for one
 *    afternoon, which is a telephone that gets put in a drawer.
 *  - **The app itself.** Twelve photographs at the width of a bubble are twelve full-size decodes;
 *    that is how a launcher gets killed, and she experiences it as the screen going blank.
 *
 * Each is guarded separately below, and none of them hides anything: the Message tab still lists
 * every message, the conversation still shows every picture, and nothing is ever reordered.
 */
object PictureFlood {

    /**
     * How many pictures of one run are drawn at the full width of a bubble before the rest shrink.
     *
     * Ten, so that a family outing sent as a handful of photos is drawn the way it was sent —
     * nine from a mountain walk in the Hartmanns group were three large and six at under half
     * width, on the Fold's wide screen. Ten is still far short of a camera-roll dump like the 35
     * above, which is what costs the process its memory. The rest are still there, still in
     * order, still openable — they are simply drawn at [SMALL_WIDTH].
     */
    const val FULL_SIZE_IN_A_ROW = 10

    /** The width the rest of a run gets, as a fraction of the bubble. */
    const val SMALL_WIDTH = 0.45f

    /**
     * Who a message is from, as firmly as the message can say it, and which way it went.
     *
     * The direction is part of it because a run of her own pictures and a run of her son's are two
     * runs, not one — see [shrunken], which would otherwise shrink hers because he sent three
     * first.
     */
    fun senderOf(message: Message): String {
        val who = message.groupIdentity?.let { "group:$it" }
            ?: message.contactId?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.senderId?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.senderAddress?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.senderName.trim()
        return (if (message.isOutgoing) "me:" else "them:") + who.lowercase()
    }

    /**
     * The same list with one row per person, newest of theirs kept.
     *
     * For her home screen, where the whole list is two rows long. Order is untouched: the row that
     * stays is the one that was already highest, so a flood cannot promote anybody either.
     */
    fun oneRowPerSender(messages: List<Message>): List<Message> = messages.distinctBy(::senderOf)

    /**
     * Which pictures of a conversation are drawn small: everything past the first
     * [FULL_SIZE_IN_A_ROW] of an uninterrupted run from one person.
     *
     * A run is broken by anything at all — a word from either of them, a voice message, the other
     * person answering — because those are the things that make a conversation, and a picture that
     * somebody wrote something about is not part of a flood.
     *
     * A film is never drawn small, and it neither counts towards a run nor breaks one: the
     * photographs either side of it shrink exactly as they would without it. Read off the Fold on
     * 2026-10-19 — a video from Hans-Peter Jud was the twelfth of 25 pictures, so it was drawn at
     * under half width, and what it shows until the film lands is the thumbnail WhatsApp packs into
     * the message — 41 by 72 pixels for that one. That is a picture she can hardly make out, with a
     * play control too small to find. A film is somebody showing her something on purpose, and
     * the still is a small decode, so the reason for shrinking does not apply to it.
     */
    fun shrunken(messages: List<Message>): Set<String> {
        val small = mutableSetOf<String>()
        var sender: String? = null
        var inARow = 0

        for (message in messages) {
            if (isFilm(message)) continue

            val isPicture = !message.photoPath.isNullOrBlank() || !message.videoPath.isNullOrBlank()
            if (!isPicture) {
                sender = null
                inARow = 0
                continue
            }

            val from = senderOf(message)
            inARow = if (from == sender) inARow + 1 else 1
            sender = from
            if (inARow > FULL_SIZE_IN_A_ROW) small.add(message.id)
        }

        return small
    }

    /** A video message, whether or not its film has landed yet — before that it is only a still. */
    private fun isFilm(message: Message): Boolean =
        !message.videoPath.isNullOrBlank() || Attachment.of(message.text) == Attachment.VIDEO
}
