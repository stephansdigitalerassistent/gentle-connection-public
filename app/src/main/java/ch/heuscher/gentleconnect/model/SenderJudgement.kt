package ch.heuscher.gentleconnect.model

/**
 * A sender the phone has filed as a company, as the caregiver's website has to see it to be able
 * to disagree.
 *
 * Two fields and no more, like [KnownGroup]. [key] is the phone's own name for the sender — see
 * [SenderJudgement.keyOf] — and [name] is what her screen calls it, which is the only part a
 * caregiver can recognise. No messages and nothing that was said: deciding that "Praxis Dr. Meier"
 * is a person is a decision about a name on a list, and nothing else about the sender has to leave
 * the phone for it.
 */
data class KnownSender(val key: String, val name: String)

/**
 * The caregiver's corrections to [SenderKind] — which senders count as a person after all.
 *
 * [SenderKind] decides from the address alone, and it is right about nearly everything: a laundry
 * writes from a name, a marketing service from four digits, her daughter from a telephone number.
 * What it cannot know is that the four digits belong to her doctor's surgery, or that the short
 * name is the pharmacy that tells her when a prescription is ready. Those arrive filed as
 * companies, and being filed as a company on this phone means three things at once: no face, no
 * card at the top of her home screen, and — since [Home.worthANudge] is what the arrival loop
 * reads — the phone never makes a sound. A message she was waiting for lands in silence.
 *
 * That judgement is about one particular person's life, which is why it belongs to whoever looks
 * after her rather than to a rule in here. So the phone publishes the senders it has filed as
 * companies and reads back the ones a caregiver has ticked, exactly as it does for groups.
 *
 * **What a correction changes and what it must never change.** It changes what she is shown and
 * what wakes the phone: the face, the home screen, the sound. It does not make a sender
 * answerable. There is genuinely nobody behind an alphanumeric originator, and the way that fact
 * reaches the screen is [namesSomebodyToWriteTo], which asks the address and not this list — a
 * reply field on a message that cannot be replied to is her writing an answer to her doctor that
 * no one will ever read, with nothing on the screen to say so. See [SenderKind].
 *
 * **Only companies are ever published.** Somebody in her address book is already a person and
 * needs no correction, so no name or number of anybody she knows leaves the phone through this.
 *
 * Owned by the website, like [GroupVisibility]: the phone publishes what exists and reads back
 * what was ticked; it never writes the ticks itself.
 *
 * Pure, because a mistake here is invisible from her side — a surgery whose correction did not
 * match is a phone that stays silent for exactly the message she was waiting for.
 */
object SenderJudgement {

    /**
     * The phone's name for a sender: its address, in a form that compares.
     *
     * The same number reaches her written five ways — `+41 44 123 45 67`, `+41441234567`,
     * `044 123 45 67` — depending on which network handed it over and which app wrote it down.
     * Spacing and the punctuation people put in telephone numbers is dropped and letters are
     * lowered, so that a tick made against one spelling still matches when the next message
     * arrives spelt the other way. Nothing else is normalised: `+41...` and `044...` are different
     * keys, because they are different strings and the phone has no way to know they are the same
     * subscriber without guessing a country.
     *
     * Blank for an address that is nothing at all, which [known] and [isTreatedAsPerson] both
     * treat as "no sender to correct" rather than as a key that could match.
     */
    fun keyOf(senderAddress: String?): String {
        val trimmed = senderAddress?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return trimmed.filterNot { it.isWhitespace() || it in DROPPED }.lowercase()
    }

    /**
     * The senders a caregiver has said are people, in the form [keyOf] produces.
     *
     * Defensive about what comes back out of Firestore — a blank entry, a stray capital, a number
     * typed with spaces, a value that is not a string at all — because the alternative is a list
     * that silently fails to match and a switch on the website that appears to do nothing.
     */
    fun treatedAsPeople(raw: Collection<String?>?): Set<String> =
        raw.orEmpty().map { keyOf(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Whether this message is from a sender a caregiver has corrected.
     *
     * Asked of every message on the way to the screen, so the empty set — the ordinary state, and
     * the one every phone starts in — costs nothing.
     */
    fun isTreatedAsPerson(message: Message, treated: Set<String>): Boolean {
        if (treated.isEmpty()) return false
        val key = keyOf(message.senderAddress)
        return key.isNotEmpty() && key in treated
    }

    /**
     * The senders to offer the caregiver: the ones this phone has filed as companies.
     *
     * Only companies, for the reason in the class note — everybody she has in her address book is
     * already a person, and publishing her contacts to draw a list of switches nobody needs would
     * send her family's numbers to a website for nothing.
     *
     * One row per sender however many times they have written, named by what her own screen calls
     * them, so that a switch is labelled with the words she would read. Sorted by that name so the
     * list does not reorder itself between visits — the phone's own list is newest-first, and a
     * page whose switches move is one somebody has to read twice before daring to touch it.
     *
     * Her own messages are left out. A sender is somebody who wrote to her; a row for her own
     * outgoing text to a short code is a switch about herself.
     */
    fun known(messages: List<Message>): List<KnownSender> {
        val byKey = LinkedHashMap<String, String>()
        messages.forEach { message ->
            if (message.isOutgoing || message.isStatus) return@forEach
            // The uncorrected kind on purpose: a sender already ticked stays on the list, so a
            // caregiver can see what they did and undo it. Reading the corrected kind would take
            // the row away the moment it was ticked, leaving a switch that cannot be switched back.
            if (SenderKind.of(message.senderAddress, message.channel, message.contactId) !=
                SenderKind.ORGANISATION
            ) {
                return@forEach
            }
            val key = keyOf(message.senderAddress)
            if (key.isEmpty()) return@forEach
            val name = message.displayName.trim()
            if (name.isEmpty()) return@forEach
            // The newest name for the sender, since the list arrives newest first and this keeps
            // the first it sees: a company that has since been given a name in her address book is
            // labelled with that, not with the number it used to show as.
            byKey.putIfAbsent(key, name)
        }
        return byKey.map { (key, name) -> KnownSender(key, name) }.sortedBy { it.name.lowercase() }
    }

    /**
     * Everything she is shown, with the companies left out when somebody has asked for that.
     *
     * Read after the corrections have been stamped on, never before, because the two are halves of
     * one decision: a sender ticked as a person in [treatedAsPeople] survives this filter, which is
     * how "hide the companies, except the surgery" is expressed at all. Reading the address here
     * instead would hide the surgery again the moment the switch was thrown, and the caregiver who
     * had just corrected it would have no way to tell which of the two settings had betrayed them.
     *
     * Her own replies to a company go with it, and that falls out rather than being arranged: her
     * outgoing rows carry the other party in [Message.senderAddress], so they are filed under the
     * same kind. It is also what should happen — half a conversation is stranger than none.
     *
     * Hiding, not deleting. Nothing is removed from the phone and nothing is refused at the
     * network, so switching this back on brings every message back where it was.
     */
    fun shown(messages: List<Message>, showCompanies: Boolean): List<Message> {
        if (showCompanies) return messages
        return messages.filter { it.senderKind == SenderKind.PERSON }
    }

    /** The punctuation people write telephone numbers with, which says nothing about the number. */
    private const val DROPPED = "-()./"
}
