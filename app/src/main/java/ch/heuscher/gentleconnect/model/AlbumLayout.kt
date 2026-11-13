package ch.heuscher.gentleconnect.model

/**
 * What a status actually has in it to show, which is not always what its text claims.
 *
 * Its own question because the album card and the viewer both have to answer it the same way, and
 * the obvious answers are wrong: a status whose text is "[Foto]" may have no photograph on the phone
 * yet, and one with no photograph may be somebody's words rather than a picture still on its way.
 */
enum class StatusContent {
    /** A photograph that is on the phone. */
    PICTURE,

    /** A film that is on the phone. */
    FILM,

    /** Words and nothing else — posted to be read, and drawn as words. */
    WORDS,

    /**
     * A picture or film that has not arrived yet, or a status with nothing in it at all. Drawn as
     * nothing rather than as the word "Photo" in large type, which would read as though somebody
     * had written it.
     */
    PENDING;

    companion object {
        fun of(message: Message): StatusContent {
            if (!message.videoPath.isNullOrBlank()) return FILM
            if (!message.photoPath.isNullOrBlank()) return PICTURE
            return when (Attachment.of(message.text)) {
                Attachment.PHOTO, Attachment.VIDEO -> PENDING
                null -> if (message.text.isBlank()) PENDING else WORDS
                else -> WORDS
            }
        }
    }
}

/**
 * How one burst is laid out on its album card.
 *
 * The newest status first, drawn as a single status always has been. When there is more than one,
 * the second under it, and behind the second the edges of a pile — one edge for a third status, two
 * for a fourth or more and never deeper, because twelve drawn edges are a stripe and not a pile. How
 * many are in the pile is said in words on the second picture.
 *
 * This replaced a row of three thumbnails a third of the screen wide (2026-09-11): a face
 * an inch across is the one thing her eyes cannot pick out.
 */
data class AlbumLayout(
    /** Index of the status drawn under the newest, or null for a burst of one. */
    val second: Int?,
    /** How many edges of the pile peek out behind the second: 0, 1 or [MAX_EDGES]. */
    val edges: Int,
    /** How many statuses lie under the second, said on it as "10 more". */
    val more: Int,
    /** Whether the burst is words and nothing else: then there is no picture to lead with. */
    val onlyWords: Boolean,
    /**
     * How many texts the card does not show — captions and statuses of words past the newest,
     * except a second that is itself words, which the pile shows. Said under the card, so that
     * "there is more to read in here" is not something she has to discover by opening it.
     */
    val hiddenTexts: Int
) {
    companion object {
        const val MAX_EDGES = 2

        fun of(messages: List<Message>): AlbumLayout {
            require(messages.isNotEmpty()) { "An album has at least one status in it." }

            val kinds = messages.map(StatusContent::of)
            val onlyWords = kinds.all { it == StatusContent.WORDS }
            val underSecond = (messages.size - 2).coerceAtLeast(0)

            return AlbumLayout(
                second = if (messages.size >= 2) 1 else null,
                edges = underSecond.coerceAtMost(MAX_EDGES),
                more = underSecond,
                onlyWords = onlyWords,
                hiddenTexts = if (onlyWords) 0 else messages.indices.drop(1).count { index ->
                    val shownOnPile = index == 1 && kinds[index] == StatusContent.WORDS
                    !shownOnPile && Attachment.captionIn(messages[index].text).isNotBlank()
                }
            )
        }
    }
}
