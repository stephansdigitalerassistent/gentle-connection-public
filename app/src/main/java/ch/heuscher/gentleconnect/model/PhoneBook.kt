package ch.heuscher.gentleconnect.model

/**
 * The order the Call screen puts her address book in: the people she actually rings, then
 * everybody else.
 *
 * Alphabetical from A was complete and unusable. Her favourites are scattered through 1254
 * contacts by first name, so reaching her son meant either scrolling past several hundred people
 * she has never called or typing his name — and typing is the thing she is slowest at and least
 * likely to attempt. The shortlist somebody chose for her is the answer to "who does she call",
 * and it was the one part of the list she could not get to quickly.
 *
 * All of them, not the five the home screen draws: home is a screen and has to stay short,
 * whereas this is the list you come to precisely because the person you want was not one of the
 * five.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object PhoneBook {

    /**
     * The two groups, kept apart rather than concatenated.
     *
     * The screen needs to know where one ends and the other begins — it draws a line there, and
     * marks the people above it — and working that back out of a single list would mean asking
     * "is this one a favourite" per row against the rule this file exists to own.
     */
    data class Listing(
        val favourites: List<Contact>,
        val others: List<Contact>
    ) {
        /** Everyone, in the order she sees them. For the callers that do not care about the split. */
        val all: List<Contact> get() = favourites + others

        val isEmpty: Boolean get() = favourites.isEmpty() && others.isEmpty()
    }

    /**
     * Her favourites first in the order somebody chose for them, then the rest of the address
     * book in the order it arrives, both narrowed by whatever she has typed.
     *
     * Searching does not switch the hoisting off. If she types four letters that match both her
     * daughter and a plumber, her daughter is still the one at the top.
     */
    fun forHer(all: List<Contact>, favourites: List<Contact>, query: String): Listing {
        // Not through [ContactSearch.forHer], which also drops entries filed under a bare
        // telephone number. That rule exists to keep junk out of a list of 1254; a favourite is
        // someone a person deliberately put on her home screen, and dropping one of those
        // because of how it happens to be named would take a face away from her.
        val hers = favourites.filter { ContactSearch.matches(it.name, query) }

        val alreadyAbove = hers.mapTo(mutableSetOf(), ::asSheWouldSeeIt)
        val everybodyElse = ContactSearch.forHer(all, query)
            .filterNot { asSheWouldSeeIt(it) in alreadyAbove }

        return Listing(favourites = hers, others = everybodyElse)
    }

    /**
     * What makes two entries the same person for the purpose of not printing them twice.
     *
     * Name **and** number, which is [ch.heuscher.gentleconnect.data.ContactDeduplicator]'s rule
     * and for its reason: keying on the number alone deleted fifty people from her address book,
     * colleagues who share an office switchboard.
     *
     * Keying on the id would be wrong for the same list read two ways. Favourites now come from
     * the phone's starred contacts and do carry address-book ids, so ids would work — but the
     * legacy path in `resolveFavouritesFrom`, still used by a phone with nothing starred yet,
     * copies the caregiver's own entry and only borrows the photo and number from the match. On
     * that phone, keying on the id prints every favourite twice: once at the top and once in
     * place. Name and number is the rule that holds for both.
     *
     * The last seven digits rather than the whole number, because the two sides are written
     * differently: the caregiver's `079 123 45 67` and the address book's `+41 79 123 45 67` are
     * one telephone and compare as different strings.
     *
     * Deliberately strict: a favourite recorded as "Markus" against an address book entry of
     * "Markus Hartmann" appears twice. That is the failure worth having — she can see a name
     * listed twice and it costs her a moment, whereas a wrongly merged entry hides a real person
     * and she has no way to notice somebody is missing.
     */
    private fun asSheWouldSeeIt(contact: Contact): String {
        val digits = contact.phoneNumber.filter { it.isDigit() }
        return "${contact.name.trim().lowercase()}|${digits.takeLast(SAME_TELEPHONE_DIGITS)}"
    }

    /** Enough of a number to identify it, few enough to survive a country code being written on. */
    private const val SAME_TELEPHONE_DIGITS = 7
}
