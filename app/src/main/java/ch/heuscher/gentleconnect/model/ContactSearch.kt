package ch.heuscher.gentleconnect.model

/**
 * Finding one person in an address book of 1254.
 *
 * Counted on her phone: 1254 contacts, sorted by first name, with no search and no letter index.
 * Reaching Simon meant scrolling past several hundred people she has never called. The list was
 * complete and unusable, which for her is the same as empty.
 *
 * Pure, so the rules that decide who is shown can be tested — CI here runs JVM unit tests only,
 * and nothing that touches a `Context` is testable at all.
 */
object ContactSearch {

    /**
     * The address book as she should see it, narrowed by whatever she has typed.
     *
     * Nameless entries are dropped whatever she types. The first row of her address book was
     * `+41 78 555 01 11` — a number somebody rang once, filed under itself. She cannot decide
     * whether to call that, so offering it is not a choice, it is an obstacle standing in front of
     * the letter A.
     */
    fun forHer(contacts: List<Contact>, query: String): List<Contact> =
        contacts
            .filterNot { looksLikeBareNumber(it.name) }
            .filter { matches(it.name, query) }

    /**
     * Whether a contact's name answers to what she has typed.
     *
     * Matched against the **raw** name, not the shortened one. `Eliane "Eli" Müller` is shown as
     * `Eliane Müller` — see [PersonName] — but "Eli" is what somebody wrote down because it is
     * what that person is called, and it would be perverse to hide the nickname and then refuse to
     * search for it.
     *
     * Anywhere in the name, not just the start: she thinks of her daughter-in-law by her family
     * name about as often as by her first.
     */
    fun matches(name: String?, query: String): Boolean {
        val needle = fold(query)
        if (needle.isEmpty()) return true
        return fold(name.orEmpty()).contains(needle)
    }

    /**
     * Whether this "name" is really just a telephone number.
     *
     * Digits and the punctuation that goes with them, and at least one digit — so `+41 78 555 01
     * 57` goes and a person called `007` (there is no such person, but the rule should not depend
     * on that) is kept only if she also has letters. A name has letters in it.
     */
    fun looksLikeBareNumber(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        if (trimmed.any { it.isLetter() }) return false
        return trimmed.any { it.isDigit() }
    }

    /**
     * The form both sides of a comparison are put into: lower case, umlauts flattened.
     *
     * She types on a keyboard she is not fast on, and "Muller" not finding Müller would read as
     * the person having disappeared from her phone. Flattening rather than expanding, because
     * `ue` typed for `ü` is the rarer habit and folding both directions at once makes the rule
     * hard to reason about.
     */
    private fun fold(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value.trim().lowercase()) {
            when (character) {
                'ä' -> builder.append('a')
                'ö' -> builder.append('o')
                'ü' -> builder.append('u')
                'à', 'á', 'â' -> builder.append('a')
                'è', 'é', 'ê' -> builder.append('e')
                'ç' -> builder.append('c')
                'ß' -> builder.append("ss")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }
}
