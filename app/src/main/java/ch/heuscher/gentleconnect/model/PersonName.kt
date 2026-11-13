package ch.heuscher.gentleconnect.model

/**
 * How a person's name is put on her screen.
 *
 * Two things go wrong between her address book and the screen, and both were found on the phone.
 *
 * Her contacts carry nicknames in quotes — `Johann "Papa Opa" Hartmann`, `Eliane "Eli" Müller`.
 * The row has space for about sixteen characters, so what she actually read was `Johann "Papa Opa…`
 * and `Eliane "Eli"…`: the part that got kept was the punctuation and the part that got cut was
 * the family name. Dropping the nickname first means the truncation, when it still happens, eats
 * the end of a real name instead.
 *
 * And a message from somebody she has no contact for is displayed under their Threema identity, so
 * `TSTMBR01` appeared where a person's name goes. A code is not a person, and she has no way to
 * turn one into one.
 *
 * The same hole was open on the other side. WhatsApp names an unnamed group by its address, and
 * `120363000099999999@g.us` was standing at the top of a conversation and over every message in
 * it — the exact thing [ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups] says this app
 * never shows her. One rule covers both: a code and an address are both things she cannot read
 * and cannot act on, and "Someone" is better than either.
 *
 * Pure on purpose: CI here runs JVM unit tests only, so a rule that is not in an object like this
 * one is a rule that is never tested.
 */
object PersonName {

    /**
     * The name as she should see it, or null when there is no name here at all.
     *
     * Null rather than a placeholder, because the word for a nameless person belongs in
     * `strings.xml` in both languages — this object has no business knowing which language the
     * phone is in.
     */
    fun forHer(rawName: String?): String? {
        val withoutNickname = withoutNickname(rawName)
        if (withoutNickname.isEmpty()) return null
        if (looksLikeIdentity(withoutNickname)) return null
        if (looksLikeAddress(withoutNickname)) return null
        return withoutNickname
    }

    /**
     * One or two uppercase initials to draw when a contact has no face photograph.
     *
     * Used on the View screen (LookScreen) to ensure no blank spaces or broken image boxes appear.
     * For email addresses, extracts initials from the local part before the `@`.
     */
    fun initials(rawName: String?): String {
        val clean = rawName?.trim().orEmpty()
        if (clean.isEmpty()) return "?"

        // If an email address is passed: "steven@example.com" -> "S", "anna.meier@domain.com" -> "AM"
        val candidate = if ('@' in clean) {
            clean.substringBefore('@')
        } else {
            withoutNickname(clean).ifEmpty { clean }
        }

        // Split on whitespace or common name separators (. _ -)
        val parts = candidate.split(Regex("[\\s._\\-]+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> {
                val letters = parts[0].filter { it.isLetter() }
                if (letters.isEmpty()) {
                    val digits = parts[0].filter { it.isDigit() }
                    if (digits.isNotEmpty()) digits.take(1) else "?"
                } else {
                    letters.take(1).uppercase()
                }
            }
            else -> {
                val first = parts.first().filter { it.isLetter() }.take(1).uppercase()
                val last = parts.last().filter { it.isLetter() }.take(1).uppercase()
                if (first.isEmpty() && last.isEmpty()) "?"
                else "$first$last"
            }
        }
    }

    /**
     * Whether this is an address rather than a name.
     *
     * The `@` is the whole test, and it is enough: every address that reaches this point is a
     * WhatsApp JID (`…@g.us`, `…@s.whatsapp.net`) or an e-mail address, and no name contains one.
     *
     * Deliberately not a test for digits. A bare telephone number is a legitimate last resort —
     * see WhatsAppMessageMapper, which falls back to the number on purpose — because a number is
     * at least something she may recognise, and it is how her phone has always shown a stranger.
     */
    fun looksLikeAddress(name: String): Boolean = '@' in name

    /**
     * The name with any quoted nickname taken out.
     *
     * Double quotes only — straight and typographic, including the German low-nine opening quote.
     * Not the apostrophe: O'Brien and D'Angelo are names, and a rule that treated `'` as a
     * delimiter would swallow half of one.
     *
     * An opening quote with no closing one takes everything after it. A name that has started a
     * nickname and not finished it has nothing readable left in the tail, and leaving the stray
     * quote mark in would put punctuation on her screen for no reason.
     */
    fun withoutNickname(rawName: String?): String {
        val name = rawName?.trim().orEmpty()
        if (name.isEmpty()) return ""
        if (QUOTES.none { it in name }) return collapseSpaces(name)

        val kept = StringBuilder()
        var closing: Char? = null
        for (character in name) {
            when {
                closing != null -> if (character == closing) closing = null
                character in QUOTES -> closing = CLOSING_FOR[character] ?: character
                else -> kept.append(character)
            }
        }

        val stripped = collapseSpaces(kept.toString())
        // A name that was nothing but its nickname keeps the nickname. `"Grosi"` is what somebody
        // deliberately wrote down for this person, and it is a great deal better than nothing.
        return stripped.ifEmpty { collapseSpaces(name.filterNot { it in QUOTES }) }
    }

    /**
     * The name a message arrived with, when nobody has managed to resolve it to a person.
     *
     * Three ways that happens and they are all the same thing to her: nothing at all, the address
     * the message came in on, or the placeholder the bridge writes when it has no better answer.
     * One rule rather than four copies of it — this is asked wherever a name is about to be put in
     * front of her, and a copy that falls behind is a code where a person should be.
     */
    fun isUnresolved(name: String, identity: String?): Boolean =
        name.isBlank() || name == identity || name == UNKNOWN_PERSON

    /** What the bridge writes when it cannot say who somebody is. */
    const val UNKNOWN_PERSON = "Unbekannte Person"

    /**
     * Whether this is a Threema identity wearing a name's clothes.
     *
     * Eight characters, uppercase letters and digits, **and at least one digit**. The digit is what
     * keeps KAROLINA and MATTHIAS — eight uppercase letters each, and real names. An identity of
     * letters only slips through; that is the deliberate direction to be wrong in, because the
     * other one turns her family into "Someone".
     */
    fun looksLikeIdentity(name: String): Boolean {
        if (name.length != IDENTITY_LENGTH) return false
        if (!name.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        return name.any { it.isDigit() }
    }

    private fun collapseSpaces(value: String): String =
        value.trim().replace(WHITESPACE_RUN, " ")

    private val WHITESPACE_RUN = Regex("\\s+")

    private val CLOSING_FOR = mapOf('“' to '”', '„' to '“')

    private val QUOTES = setOf('"', '“', '”', '„')

    private const val IDENTITY_LENGTH = 8
}
