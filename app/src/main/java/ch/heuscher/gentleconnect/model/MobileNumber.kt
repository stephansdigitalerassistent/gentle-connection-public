package ch.heuscher.gentleconnect.model

/**
 * Whether a number is a mobile one — the kind somebody writes to, rather than the kitchen phone,
 * the surgery or the plumber's office.
 *
 * Used to keep the people at the end of the message list to those a message can sensibly go to:
 * an SMS to a landline arrives nowhere, and an address book of 1,250 entries is mostly landlines
 * and businesses. See [NotYetWritten].
 *
 * Takes the matchable form of the number — E.164 digits with no `+`, as
 * [ch.heuscher.gentleconnect.data.PhoneNumberNormalizer] makes them — so `079 123 45 67`,
 * `+41 79 123 45 67` and `0041791234567` are all the same `41791234567` by the time they get here.
 *
 * Only the countries her people are likely to have numbers in are known. A number from anywhere
 * else is not called mobile: leaving somebody off this list costs her nothing, since they are
 * still in the phone book, whereas offering a landline invites a message that goes nowhere.
 */
object MobileNumber {

    /** Mobile ranges by calling code, as the digits that follow the code. */
    private val MOBILE_PREFIXES: Map<String, List<String>> = mapOf(
        // Switzerland: every 07x.
        "41" to listOf("7"),
        // Liechtenstein.
        "423" to listOf("6", "7"),
        // Germany: 015x, 016x, 017x.
        "49" to listOf("15", "16", "17"),
        // Austria: 065x–069x.
        "43" to listOf("65", "66", "67", "68", "69"),
        // France: 06, 07.
        "33" to listOf("6", "7"),
        // Italy: 3xx.
        "39" to listOf("3")
    )

    /** @param matchable E.164 digits with no `+`, or null when the number could not be read. */
    fun isMobile(matchable: String?): Boolean {
        val digits = matchable ?: return false
        if (digits.length !in 9..15 || !digits.all { it.isDigit() }) return false
        // Longest code first, so Liechtenstein's 423 is not read as some 42x.
        return MOBILE_PREFIXES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { digits.startsWith(it.key) }
            ?.let { (code, prefixes) -> prefixes.any { digits.startsWith(code + it) } }
            ?: false
    }
}
