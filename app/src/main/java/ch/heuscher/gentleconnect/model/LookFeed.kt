package ch.heuscher.gentleconnect.model

/**
 * One thing on the Look screen.
 *
 * Two kinds, drawn differently on purpose. A letter was addressed to her and a picture was not,
 * and a screen that blurred the two would be telling her that her daughter had written to her
 * when her daughter had posted a photograph to everybody she knows.
 *
 * What they have in common is the only promise the screen makes: she can look, and nothing else.
 * Nothing here can be answered, deleted or sent by mistake — which is what lets them share a
 * place at all.
 */
sealed class LookItem {

    abstract val timestamp: Long

    /**
     * This one thing, told apart from every other thing on the screen.
     *
     * The list is drawn by position unless it is given this, and a list drawn by position loses
     * her place the moment anything arrives: post that came in while she was reading a letter
     * pushes everything down a row, and the row she was on is then somebody else's. The kind is
     * part of it because a letter and a picture are counted by two different services and there
     * is nothing stopping the two from handing out the same number.
     */
    abstract val id: String

    /** The resolved photoUri for the sender of this letter or picture, if available. */
    abstract val photoUri: String?

    /** A letter out of her mailbox. */
    data class Letter(val mail: Mail, override val photoUri: String? = null) : LookItem() {
        override val timestamp: Long get() = mail.timestamp
        override val id: String get() = "letter:" + mail.id
    }

    /**
     * Pictures somebody put up for everybody, in one go — see [StatusBroadcast] and [StatusBurst].
     *
     * A list rather than a picture because that is what actually arrives: people empty a camera
     * roll, and thirty-five statuses nine seconds apart are one thing she was shown, not
     * thirty-five. Newest first, and never empty.
     *
     * The two screens draw the same burst differently, and the difference is the difference
     * between them. Home has room for two items in total, so it says who and how many. The Look
     * screen draws a burst as an album card — one full-width picture with up to three small
     * thumbnails underneath — and the viewer that opens on a tap shows every picture with swipe
     * and tap-thirds navigation.
     */
    data class Picture(
        val messages: List<Message>,
        override val photoUri: String? = null
    ) : LookItem() {
        constructor(message: Message, photoUri: String? = null) : this(listOf(message), photoUri)

        /** The newest of them: what the row is dated by, and the one a home row stands for. */
        val message: Message get() = messages.first()

        /** How many were put up in the one go. One is the ordinary case. */
        val count: Int get() = messages.size

        override val timestamp: Long get() = message.timestamp
        override val id: String get() = "picture:" + message.id
    }
}

/**
 * Everything that has arrived for her to look at, newest first.
 *
 * Pure, so CI can prove the ordering. The two sources have nothing to do with each other — a
 * mailbox and a messenger — and the only thing that decides where something sits is when it
 * arrived, which is the one ordering she never has to be taught.
 */
object LookFeed {

    /**
     * @param mediaKeyOf what two identical pictures share and two different ones do not, given a
     *   file path — see [ch.heuscher.gentleconnect.data.MediaFingerprint]. Absent, nothing is
     *   taken for a repeat, which is the safe way to be wrong.
     */
    fun build(
        mails: List<Mail>,
        statuses: List<Message>,
        emailPhotoResolver: ((String) -> String?)? = null,
        phonePhotoResolver: ((String) -> String?)? = null,
        mediaKeyOf: ((String) -> String?)? = null
    ): List<LookItem> {
        val letters = mails.map { mail ->
            val photo = if (emailPhotoResolver != null && mail.senderAddress.isNotBlank()) {
                emailPhotoResolver(mail.senderAddress)
            } else null
            LookItem.Letter(mail, photo)
        }
        // Defensive rather than trusting: a caller handing us the whole message list instead of
        // the statuses out of it would put her conversations on this screen, which is the exact
        // confusion the screen exists to undo.
        val pictures = statuses.filter { it.isStatus }.map { status ->
            val phone = status.senderAddress ?: status.senderId
            val photo = if (phonePhotoResolver != null && !phone.isNullOrBlank()) {
                phonePhotoResolver(phone)
            } else null
            LookItem.Picture(status, photo)
        }
        return burstsFolded((letters + pictures).sortedByDescending { it.timestamp }, mediaKeyOf)
    }

    /**
     * Adjacent pictures from one person, put up in one go, folded into a single item.
     *
     * Adjacent in the finished feed, which is what keeps this from ever reordering her post: two
     * of her sister's pictures with a letter in between are two items, because folding them would
     * have to move the letter. See [StatusBurst] for where the line between "one posting" and
     * "two" falls, and why it is where it is.
     */
    private fun burstsFolded(
        feed: List<LookItem>,
        mediaKeyOf: ((String) -> String?)?
    ): List<LookItem> {
        val folded = mutableListOf<LookItem>()
        for (item in feed) {
            val last = folded.lastOrNull()
            if (item is LookItem.Picture && last is LookItem.Picture &&
                StatusBurst.sameBurst(last.messages.last(), item.message)
            ) {
                // The feed is newest first, so the run grows at its older end and the item keeps
                // the newest picture's time — which is when she would say it arrived.
                folded[folded.lastIndex] = last.copy(messages = last.messages + item.messages)
                continue
            }
            folded.add(item)
        }

        // Repeats are dropped afterwards, never while the run is being assembled. A burst is
        // chained on the gap between one picture and the next, so taking a copy out mid-fold
        // takes its timestamp out of the chain with it — and Hans Müller's nine, which are three
        // pictures posted three times over eight minutes, then broke in half at the hole where a
        // dropped copy used to be and showed her the same three photographs twice.
        return folded.map { item ->
            if (item is LookItem.Picture && item.count > 1) {
                item.copy(messages = withoutRepeats(item.messages, mediaKeyOf))
            } else {
                item
            }
        }
    }

    /**
     * One posting with the same photograph in it twice reduced to the once it really is.
     *
     * Hans Müller put up nine statuses in eight minutes on 16 August and they were three
     * photographs, each posted three times — see [ch.heuscher.gentleconnect.data.MediaFingerprint]
     * for how that is known and why only an exact match counts.
     *
     * Inside the one posting and no further. The same picture put up again next week is somebody
     * posting it again, which is a thing that happened and is hers to be told about; hiding it
     * would need this to remember everything she has ever been shown, and to be right about it.
     * A repeat within the one burst needs no such memory: it is three copies of one act.
     *
     * The first copy is the one kept, which in a newest-first list is the most recent — the same
     * choice the rest of the feed makes, and it keeps the burst's own timestamp true.
     */
    private fun withoutRepeats(
        pictures: List<Message>,
        mediaKeyOf: ((String) -> String?)?
    ): List<Message> {
        if (mediaKeyOf == null || pictures.size < 2) return pictures

        val seen = mutableSetOf<String>()
        return pictures.filter { picture ->
            // A status with nothing downloaded has no contents to be identical to anything, and a
            // status that is words alone is not a picture at all. Neither is ever a repeat.
            val path = picture.photoPath ?: picture.videoPath
            val key = if (path.isNullOrBlank()) null else mediaKeyOf(path)
            key == null || seen.add(key)
        }
    }
}
