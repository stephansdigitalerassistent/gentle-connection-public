package ch.heuscher.gentleconnect.data.whatsapp

import ch.heuscher.gentleconnect.model.GroupIdentity

/**
 * Answering several people at once on WhatsApp.
 *
 * The whole of this file exists because WhatsApp groups and Threema groups are different in a way
 * that decides what she can do.
 *
 * A Threema group has no address. A message to eight people is eight separate encrypted messages,
 * so answering one means knowing who all eight are — and this app only learns that when somebody
 * sends a group control message. Until then [ch.heuscher.gentleconnect.data.GroupReplyPolicy]
 * refuses the reply, which is why her screen sometimes offers no answer box for a group: the
 * alternative is a reply that reaches three of the eight and looks like it reached everyone.
 *
 * A WhatsApp group *is* an address. `1234567890-1234567890@g.us` takes one message and WhatsApp
 * delivers it to everybody, including whoever joined this morning. So the entire membership
 * question does not arise, and she can answer a WhatsApp group from the moment the message
 * arrives — no control message, no waiting, no partial delivery to report.
 *
 * That is also why the bridge was asked for group names and not for member lists: no reply needs
 * the membership, and it looked like a copy of her social graph kept for nothing.
 *
 * It is asked for both now. The reason the old decision missed is that her screen does not only
 * *address* a group, it *draws* one — as the people in it. With no member list the faces are
 * everyone who has ever written, which keeps people who left the group years ago and misses
 * everyone who has never written. She saw the first half of that on 2026-09-20 and said so. The
 * addresses are of people she is in a group with, they stay on her phone, and they are what makes
 * the strip under a group title true.
 */
object WhatsAppGroups {

    /**
     * Whether this group can be answered.
     *
     * True whenever the identity really is a WhatsApp group, because that is genuinely all it
     * takes. Deliberately not "true whenever we have a name for it": a group nobody has named yet
     * is still a group she can answer, and refusing until a name arrives would take the answer box
     * away from her for no reason she could understand.
     */
    fun canBeAnswered(group: GroupIdentity?): Boolean = jidOf(group) != null

    /**
     * The address a reply goes to, or null if this is not a WhatsApp group.
     *
     * Null must be treated as "it did not go", never as "send it to whoever wrote": that is the
     * mistake that turns an answer meant for eight people into a private message to one of them.
     */
    fun jidOf(group: GroupIdentity?): String? {
        val identity = group ?: return null
        if (!identity.creatorIdentity.equals(WhatsAppMessageMapper.GROUP_CREATOR, ignoreCase = true)) {
            return null
        }
        return identity.groupId.takeIf { WhatsAppNumber.isGroup(it) }
    }

    /**
     * The people in a group, each address once, as the bridge sent them.
     *
     * Groups and blanks are dropped rather than drawn. A group's own address turning up in a
     * member list would become a face with a group behind it — the same confusion that put
     * `120363…@g.us` into her face strips as a person until 2026-09-20.
     */
    fun memberAddresses(members: List<String>?): List<String> =
        members.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !WhatsAppNumber.isGroup(it) }
            .distinct()

    /**
     * The key one member's face is filed under, or null when there is no address here.
     *
     * It has to be exactly what [ch.heuscher.gentleconnect.model.GroupFaces.speakerKey] makes of
     * a message from the same person, or the member list and the messages describe two different
     * sets of people: every member would be drawn as silent and every speaker as somebody who
     * has left. That key is the sender id uppercased, and a WhatsApp message's sender id is the
     * bare digits of the number — see [WhatsAppMessageMapper] — so that is what this makes.
     *
     * An address with no number in it (a hidden-number LID that nothing could resolve) keeps the
     * address as its key. It will not match anything that person wrote, and that is the honest
     * outcome: they are shown as one more person in the group, with no name and no messages
     * attached to them, rather than left out of a group they are in.
     */
    fun faceKey(address: String?): String? {
        val raw = address?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (WhatsAppNumber.isGroup(raw)) return null
        return (WhatsAppNumber.numberFromJid(raw) ?: raw).uppercase()
    }

    /**
     * What to call a group whose name nobody has set, given how many people are in it.
     *
     * WhatsApp allows an unnamed group and plenty of families never name theirs. Showing the JID
     * would be showing her an address, which is the one thing this app never does — so this is the
     * fallback, and the caller supplies the words so that they come from `strings.xml` in both
     * languages rather than from here.
     *
     * Null when there is nothing better to say than the name already given.
     */
    fun displayName(name: String?, participants: Int, unnamed: (Int) -> String): String? {
        val given = name?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null) return given
        if (participants <= 0) return null
        return unnamed(participants)
    }
}
