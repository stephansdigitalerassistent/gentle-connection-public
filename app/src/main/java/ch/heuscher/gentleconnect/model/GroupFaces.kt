package ch.heuscher.gentleconnect.model

/**
 * One of the several people, as a face: who they are, and their photograph if there is one.
 *
 * [key] is whatever [GroupFaces.speakerKey] makes of them, so that a face and the messages that
 * person wrote can be matched without comparing names — two people in one family can share a
 * first name, and one person can arrive under two spellings.
 */
data class GroupFace(val key: String, val name: String, val photoUri: String? = null)

/**
 * A several-people conversation as it is drawn: the picture the group gave itself, if it gave
 * one, and the people in it with whoever wrote last at the front.
 */
data class GroupPicture(val photoUri: String?, val faces: List<GroupFace>)

/**
 * Who is in a several-people conversation, and in what order they are shown.
 *
 * The point of all of it is that writing to several people must *look* different from writing to
 * one. A 👍 in a group reaches everybody in it, and nothing on her screen used to say so: the
 * group row wore one circle, like a person, and the five faces under the conversation were the
 * same five faces she sends her son. So a group is drawn as the people in it — square, where a
 * person is round — and the reply is addressed to them, faces first.
 *
 * **Whoever wrote last goes in front.** Not a fixed order, which would make one member the face
 * of the family for ever, and not a random one, which would change the picture every time she
 * looked without anything having happened. The last speaker is the person she is most likely to
 * be answering, and the order changes exactly when the conversation does.
 *
 * **Who counts as in the group** is what the messenger says it is. Threema sends the member list,
 * so a Threema group is everybody on it **plus its creator** — see [memberIdentities]. WhatsApp
 * sends one too, since 2026-09-20 (see [ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups]).
 *
 * Only when there is no list at all does a group fall back to the people who have written in it.
 * That fallback is what she saw and reported: it keeps whoever has left, for ever, because the
 * only thing it knows about anybody is that they once spoke.
 *
 * Pure, so the ordering is proved in CI rather than on her screen.
 */
object GroupFaces {

    /** How many faces a stack shows. More than three at the sizes a stack is drawn is a smudge. */
    const val STACK_SIZE = 3

    /**
     * How many faces fit whole, one under the other, in the height there is.
     *
     * The two-column conversation stands the group up the side of the reply block instead of
     * laying it across the top — see [ch.heuscher.gentleconnect.ui.ConversationScreen]. A column
     * has no fixed number in it: it holds as many as the window has left after the faces she can
     * send and the line she writes on, which is four on the open Fold and fewer whenever anything
     * else grows. What does not fit is faded out at the bottom rather than cut off square, and
     * this is the sum that says whether there is anything to fade.
     *
     * Everything in points, as floats, so the drawing side can ask without dragging Android into
     * a test. The last card needs no gap after it, which is the whole of the arithmetic.
     */
    fun cardsInColumn(available: Float, card: Float, gap: Float): Int {
        if (card <= 0f) return 0
        val fits = ((available + gap) / (card + gap)).toInt()
        return fits.coerceAtLeast(0)
    }

    /**
     * Who wrote this, as a key that the same person always produces.
     *
     * Their messenger address first, because a name can change and an address does not. Not the
     * address when it is the group's own: a WhatsApp message whose sender has no number carries
     * the group's chat address instead, and taking that at face value would file every such
     * person under one key and draw them as a single face.
     *
     * Null for anything that is not somebody else speaking in a group.
     */
    fun speakerKey(message: Message): String? {
        if (!message.isGroupMessage || message.isOutgoing) return null
        val address = listOf(message.senderId, message.senderAddress)
            .firstNotNullOfOrNull { raw ->
                raw?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith("@g.us", ignoreCase = true) }
            }
        if (address != null) return address.uppercase()
        val name = message.senderName.trim().lowercase()
        return if (name.isEmpty()) null else "name:$name"
    }

    /**
     * Everyone the messenger says is in this group, her excluded — the creator included.
     *
     * Threema's setup message lists the members **without the creator**, because the creator is
     * the one sending it. Taken at face value, the one person guaranteed to be in the group is the
     * one person missing from it, and [of] then goes on to treat them as somebody who wrote and
     * has since left. Found on her phone on 2026-09-20: "Familie Hartmann" holds two member rows,
     * one of them hers, and the creator had written in it three weeks earlier — so the group was
     * drawn as a single person and described as one.
     *
     * The same correction [ch.heuscher.gentleconnect.data.GroupReplyPolicy] already makes for the
     * fan-out, which is why her reply reached somebody her screen did not draw.
     *
     * Empty in, empty out: a group we have never been sent a member list for is a group whose
     * membership we do not know, and inventing a one-person roster out of the creator alone would
     * be a guess. That is also what keeps WhatsApp on the [of] fallback, where the creator is a
     * sentinel rather than a person.
     *
     * @param members the identities from the last setup message, exactly as it sent them.
     * @param own her own identity, which is never drawn as one of the several people.
     */
    fun memberIdentities(group: GroupIdentity, members: List<String>, own: String?): List<String> {
        if (members.isEmpty()) return emptyList()
        val mine = own?.trim()?.uppercase()
        return (members + group.creatorIdentity)
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it == mine }
    }

    /**
     * The faces of one group, whoever wrote last in front.
     *
     * @param members the people the messenger says are in it, each already a face; empty when
     *   the messenger has not said yet, and then the people who have written are used.
     * @param messages anything; only this group's messages from other people are looked at.
     * @param faceOf how a message's author is drawn — their name and photograph as the message
     *   list already resolves them.
     */
    fun of(
        group: GroupIdentity,
        members: List<GroupFace>,
        messages: List<Message>,
        faceOf: (Message) -> GroupFace
    ): List<GroupFace> {
        val speakers = messages
            .filter { it.groupIdentity == group }
            .mapNotNull { message -> speakerKey(message)?.let { it to message } }
            .sortedByDescending { (_, message) -> message.timestamp }
            .distinctBy { (key, _) -> key }

        if (members.isEmpty()) {
            // One person can arrive under two addresses — WhatsApp sends some messages from the
            // number and some from a hidden-number id — and was drawn twice: Katrin Brunner twice
            // in a list of eight, and "7 others" for six. The same name *and* the same photograph
            // is taken as the same person; a shared name alone is not, and neither is a face
            // nobody could name.
            return speakers
                .map { (_, message) -> faceOf(message) }
                // An address is not a person. A WhatsApp message whose sender was never resolved
                // is stored with the *group's own address* in both the sender and the name, and
                // 233 of them sat on her phone on 2026-09-20 — history-synced from before the
                // bridge could name anybody. They share one speaker key, so every group holding
                // one grew an extra member called 120363…@g.us: a stranger in the strip, one more
                // in the count under the title, and an address in the faces her reply is
                // addressed to. Somebody merely *nameless* stays — that is a real voice in the
                // conversation whose name we happen not to know, and she sees them as Someone.
                .filterNot { PersonName.looksLikeAddress(it.name) }
                .distinctBy { face ->
                    PersonName.forHer(face.name)?.lowercase()?.let { "$it|${face.photoUri}" }
                        ?: "key:${face.key}"
                }
        }

        // Somebody who wrote and has since left is not somebody her reply reaches, so they are
        // not drawn as though it did.
        //
        // A member who *has* written is drawn as their messages draw them. The member list says
        // who is in the group; it is not always the better source for what they are called. A
        // WhatsApp member arrives as a telephone number, and for somebody not in her address book
        // that is all it is — while their messages carry the name they chose for themselves.
        // Taking the member row at face value would have replaced "Monika" with 41791234567 on
        // every group holding somebody she has not filed away.
        val memberByKey = members.associateBy { it.key }
        val spoke = speakers.mapNotNull { (key, message) ->
            val member = memberByKey[key] ?: return@mapNotNull null
            val spoken = faceOf(message)
            member.copy(
                name = if (PersonName.forHer(spoken.name) != null) spoken.name else member.name,
                photoUri = spoken.photoUri ?: member.photoUri
            )
        }
        val spokeKeys = spoke.map { it.key }.toSet()
        val silent = members
            .filterNot { it.key in spokeKeys }
            .sortedBy { it.name.lowercase() }
        return spoke + silent
    }

    /**
     * Whether this message starts a run of messages by the same person, and so is the one that
     * carries their face and name.
     *
     * The whole of how several people are told apart in a conversation: a face and a name where
     * somebody starts speaking, nothing on the messages they add after it. No colours — a colour
     * per person is one more thing to learn, and green and amber already mean something here.
     *
     * @param previous the message drawn directly above, or null when there is none — the top of
     *   the conversation, or a day heading in between, which begins everything anew.
     */
    fun startsRun(previous: Message?, current: Message): Boolean {
        val key = speakerKey(current) ?: return false
        if (previous == null) return true
        return speakerKey(previous) != key
    }

    /**
     * What to call a group that has no name of its own: "Monika und 7 weitere".
     *
     * Whoever wrote last, by first name, and how many more there are. It used to be the last
     * speaker's full name and nothing else, in big letters at the top of a conversation with eight
     * people in it — and she read it, reasonably, as a private conversation with Monika.
     *
     * @param faces front first, see [of].
     * @param others how "%d others" reads in her language.
     * @return null when nobody in it has a name she could read; the caller keeps what it had.
     */
    fun unnamedTitle(
        faces: List<GroupFace>,
        conjunction: String,
        others: (Int) -> String
    ): String? {
        val front = faces.firstNotNullOfOrNull { PersonName.forHer(it.name) } ?: return null
        // A number stands as it is: split at its first space, "+41 79 …" would become "+41".
        val first = if (front.any { it.isLetter() }) SupportNames.firstName(front) else front
        val more = faces.size - 1
        return if (more <= 0) first else "$first $conjunction ${others(more)}"
    }

    /**
     * Who to name in front of a list row's preview, or null when the row needs nobody named.
     *
     * A group row is titled by the group, so the words under it had no author: "Kommst du
     * morgen?" under the family, and she could not tell who was asking. A row whose title already
     * is the person — a group we have no name for, which falls back to whoever wrote — would say
     * the same name twice, so it is left alone.
     */
    fun previewSpeaker(message: Message): String? {
        if (!message.isGroupMessage || message.isOutgoing) return null
        val speaker = message.senderName.trim()
        if (speaker.isEmpty()) return null
        if (message.displayName.trim().equals(speaker, ignoreCase = true)) return null
        return speaker
    }
}
