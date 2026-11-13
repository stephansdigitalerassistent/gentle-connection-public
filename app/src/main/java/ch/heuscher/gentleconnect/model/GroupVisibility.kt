package ch.heuscher.gentleconnect.model

/**
 * A group, as the caregiver's website has to see it to be able to switch it off.
 *
 * Two fields and no more. [key] is the phone's own name for the group — see
 * [GroupVisibility.keyOf] — and [name] is what her screen calls it, which is the only part a
 * caregiver can recognise. No members, no identities, no messages: switching a group off is a
 * decision about a name on a list, and nothing else about the group has to leave the phone for
 * that decision to be made.
 */
data class KnownGroup(val key: String, val name: String)

/**
 * Which of the several-people conversations she is shown at all.
 *
 * Not every group is one she wants. A family group is the reason this app has groups; the village
 * choir with forty members posting photographs of the rehearsal is thirty messages a day arriving
 * on a home screen with room for three, pushing her son's message off it. She cannot leave a group
 * — leaving is a gesture with consequences she would be asked to confirm and could not undo — so
 * the decision belongs to whoever looks after her, taken remotely, on the website.
 *
 * **Hidden, not blocked.** A hidden group's messages still arrive, are still stored, and are still
 * answered by the group logic if she ever sees one again; what changes is that they are not drawn.
 * So switching a group back on brings its conversation back intact rather than starting it from
 * the moment somebody changed their mind — and nobody in the group is told anything, because
 * nothing about her phone's behaviour towards them has changed.
 *
 * Owned by the website. The phone publishes [KnownGroup]s so there is something to switch, and
 * reads the list of keys back; it never writes the list itself, for the same reason it never
 * writes `favouriteOrder` — a phone that pushed its own copy would undo an arrangement made
 * seconds earlier on a screen it cannot see.
 *
 * Pure, because a mistake here is invisible: a group hidden by a mis-compared key is a family
 * that has simply stopped writing, as far as she can tell.
 */
object GroupVisibility {

    /**
     * The phone's name for a group: its creator and its id, which is the one pair that is stable.
     *
     * Not the group's name — that is what changes, and a caregiver who renames the family group
     * would otherwise switch off a group that no longer exists while the renamed one reappears.
     * See [GroupIdentity] for why the pair, and not either half.
     *
     * Lower case throughout so that a key is comparable as it stands. It is opaque to the website,
     * which stores it and hands it back.
     */
    fun keyOf(group: GroupIdentity): String =
        group.creatorIdentity.trim().lowercase() + ":" + group.groupId.trim().lowercase()

    /**
     * The keys the caregiver has switched off, in the form [keyOf] produces.
     *
     * Defensive about what comes back out of Firestore — a blank entry, a stray capital, a value
     * that is not a string at all — because the alternative is a list that silently fails to match
     * and a switch on the website that appears to do nothing.
     */
    fun hiddenKeys(raw: Collection<String?>?): Set<String> =
        raw.orEmpty().mapNotNull { it?.trim()?.lowercase()?.ifEmpty { null } }.toSet()

    /**
     * Whether this message is one of the several-people conversations that were switched off.
     *
     * Only ever true of a group message. A status wears a group's clothes and is not one — see
     * [Message.isGroupMessage] — and a private message has no group to switch off.
     */
    fun isHidden(message: Message, hidden: Set<String>): Boolean {
        if (hidden.isEmpty() || !message.isGroupMessage) return false
        val group = message.groupIdentity ?: return false
        return keyOf(group) in hidden
    }

    /** Everything she is shown, with the switched-off groups left out. */
    fun visible(messages: List<Message>, hidden: Set<String>): List<Message> {
        if (hidden.isEmpty()) return messages
        return messages.filterNot { isHidden(it, hidden) }
    }

    /**
     * The groups to offer the caregiver, out of the names her screen uses for them.
     *
     * Sorted by name so the list on the website does not reorder itself between visits — the
     * underlying map is keyed by identity, and a list that shuffles is one whose switches somebody
     * has to read twice. Groups with no name yet are left out: an unnamed row is not a decision
     * anybody can take, and the name arrives with the next control message anyway.
     */
    fun known(names: Map<GroupIdentity, String>): List<KnownGroup> =
        names.mapNotNull { (group, name) ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) null else KnownGroup(keyOf(group), trimmed)
        }.sortedBy { it.name.lowercase() }
}
