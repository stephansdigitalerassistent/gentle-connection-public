package ch.heuscher.gentleconnect.model

/**
 * Who is on her home screen, and in what order.
 *
 * Two different people answer those two questions, and until now one of them answered both badly.
 *
 * **Who** is decided on the phone, by starring a contact in the ordinary Android address book.
 * That is where her people already are — with their photographs, their numbers and their real
 * spelling — and starring someone is a thing a family member can do in the app they already know,
 * without an account, a website or a pairing code. The caregiver portal used to ask instead for a
 * name, a telephone number and a photo *URL* typed into three boxes, which produced entries that
 * duplicated contacts already on the phone, carried no picture, and drifted out of date silently.
 *
 * **In what order** is the caregiver's, from the website. Starring has no order — Android sorts
 * starred contacts the same way it sorts everyone else — but the top of her home screen is the
 * most valuable space in this app, and which three faces land there is a real decision that the
 * address book cannot express.
 *
 * So the phone publishes who is starred, the website arranges them, and this puts the two
 * together. Pure, because CI here runs JVM unit tests only.
 */
object FavouriteArrangement {

    /**
     * The starred contacts, in the caregiver's order.
     *
     * Everything in [order] that is still starred comes first, in exactly that sequence. Anyone
     * starred since the caregiver last arranged them follows, keeping the order [starred] arrived
     * in — **appended, never inserted**. A person who appears at the top of her home screen
     * without anyone having put them there is a change she has no way to understand, and the top
     * is precisely where a stranger would do the most harm.
     *
     * Ids in [order] that are no longer starred simply fall out. Unstarring someone on the phone
     * is the whole gesture for removing them; it must not need a second confirmation on a website
     * that a family member may not even know exists.
     *
     * @param starred everyone starred on the phone, in address-book order.
     * @param order contact ids as the caregiver arranged them, most important first.
     * @return the arranged list, each with [Contact.order] renumbered from 1 so the rest of the
     *   app can sort by it without knowing any of this.
     */
    fun arrange(starred: List<Contact>, order: List<String>): List<Contact> {
        val byId = starred.associateBy { it.id }

        // Distinct, because an order list is written by a browser and read here: a duplicated id
        // would otherwise draw the same person twice, and two identical faces that do the same
        // thing are not a choice she can make.
        val arranged = order.distinct().mapNotNull { byId[it] }
        val arrangedIds = arranged.map { it.id }.toSet()
        val newlyStarred = starred.filterNot { it.id in arrangedIds }

        return (arranged + newlyStarred).mapIndexed { index, contact ->
            contact.copy(order = index + 1, isFavorite = true)
        }
    }

    /**
     * The order to store after a caregiver has moved someone.
     *
     * Kept as bare ids rather than as whole contacts: the website is arranging people it did not
     * create and cannot describe. Storing a name and a number alongside each id would be a second
     * copy of her address book — one that goes stale the moment anybody fixes a spelling, and one
     * that has to be protected all over again.
     */
    fun orderOf(contacts: List<Contact>): List<String> = contacts.map { it.id }

    /**
     * Move the contact at [index] by [delta] places, returning the new order.
     *
     * Bounds-checked rather than trusted: this is driven by two arrow buttons, and the top and
     * bottom rows have one that cannot do anything.
     */
    fun move(order: List<String>, index: Int, delta: Int): List<String> {
        val target = index + delta
        if (index !in order.indices || target !in order.indices) return order
        val moved = order.toMutableList()
        moved.add(target, moved.removeAt(index))
        return moved
    }
}
