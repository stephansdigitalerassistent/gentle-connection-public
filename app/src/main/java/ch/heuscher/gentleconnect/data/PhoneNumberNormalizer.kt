package ch.heuscher.gentleconnect.data

/**
 * Puts a phone number into the one shape the Threema directory will recognise: E.164 digits with
 * no leading `+`, no spaces, no punctuation.
 *
 * Her address book will hold the same number written five different ways — `079 123 45 67`,
 * `+41 79 123 45 67`, `0041791234567`, `079-123-45-67`. All of them are one person, and all of
 * them must hash to the same value or the lookup silently finds nobody.
 *
 * ## Why this refuses to guess
 *
 * A number it cannot confidently normalise returns null and is simply not looked up. The
 * temptation is to guess a country code and try anyway, but a guessed number that happens to
 * belong to a real Threema user attaches a stranger's identity to a contact under her friend's
 * name — the same invisible failure [ThreemaContactMatcher] is written to avoid. Not matching is
 * visible and correctable; matching wrongly is neither.
 */
object PhoneNumberNormalizer {

    /**
     * @param frameworkE164 what `PhoneNumberUtils.formatNumberToE164` made of the number, if
     *   anything. Preferred when present: it carries a full national numbering plan that this
     *   file has no business reimplementing.
     * @param raw the number as it appears in her address book, used when the framework declined.
     * @param defaultCallingCode digits only, no `+` — e.g. `41`. Used to expand a national number.
     * @return digits only, or null if the number cannot be normalised without guessing.
     */
    fun matchable(frameworkE164: String?, raw: String, defaultCallingCode: String): String? {
        frameworkE164?.let { e164 ->
            val digits = e164.removePrefix("+").filter { it.isDigit() }
            if (isPlausible(digits)) return digits
        }

        val trimmed = raw.trim()
        // A leading + survives punctuation stripping as a flag; anything else is noise.
        val isInternational = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val normalized = when {
            isInternational -> digits
            digits.startsWith("00") -> digits.removePrefix("00")
            // A leading zero is the trunk prefix: drop it and prepend her country's code.
            digits.startsWith("0") -> defaultCallingCode + digits.drop(1)
            // Already carries her country code, written without a + or 00.
            digits.startsWith(defaultCallingCode) -> digits
            // Anything else — a bare subscriber number, a short code, a foreign number written
            // without its prefix — cannot be resolved without guessing. Leave it alone.
            else -> return null
        }

        return normalized.takeIf { isPlausible(it) }
    }

    /**
     * Rejects lengths no real international number has. Short codes and internal extensions would
     * otherwise be hashed and sent, which is both useless and needlessly revealing.
     */
    private fun isPlausible(digits: String): Boolean = digits.length in 8..15
}
