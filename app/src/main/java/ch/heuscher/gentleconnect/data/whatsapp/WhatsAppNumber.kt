package ch.heuscher.gentleconnect.data.whatsapp

/**
 * WhatsApp addresses, and the phone numbers underneath them.
 *
 * WhatsApp identifies people by JID — `41791234567@s.whatsapp.net` for a person,
 * `1234567890-1234567890@g.us` for a group — and her address book identifies them by telephone
 * number, written however whoever typed it in felt like writing it. Everything painful about
 * connecting the two lives here, and it is pure, so it is the part CI can actually prove.
 *
 * This deliberately does **not** reuse [ch.heuscher.gentleconnect.data.PhoneNumberNormalizer]'s
 * output format: that one keeps a leading `+` because it is comparing against numbers as people
 * write them, and a JID must never carry one. Both rules are needed and they disagree, so they
 * are separate functions with the difference stated rather than one function with a flag.
 */
object WhatsAppNumber {

    /** What a personal JID ends with. */
    const val PERSON_SUFFIX = "@s.whatsapp.net"

    /** What a group JID ends with. Groups are a different thing and are treated as one. */
    const val GROUP_SUFFIX = "@g.us"

    /**
     * The digits of a JID's user part, or null if this is not a personal JID.
     *
     * A JID can carry a device suffix — `41791234567:12@s.whatsapp.net` — when the message came
     * from a companion of theirs rather than their phone. The device is not part of who they
     * are, so it is dropped: without this, the same person answering from their laptop looks
     * like somebody her address book has never heard of.
     */
    fun numberFromJid(jid: String?): String? {
        val trimmed = jid?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.endsWith(PERSON_SUFFIX)) return null
        val user = trimmed.removeSuffix(PERSON_SUFFIX).substringBefore(':')
        val digits = user.filter { it.isDigit() }
        return digits.ifEmpty { null }
    }

    /**
     * The JID for a telephone number as her address book holds it.
     *
     * Everything that is not a digit is dropped, `+` included: `+41 79 123 45 67` and
     * `0041791234567` are the same person to WhatsApp and must produce the same JID, or a reply
     * goes to nobody and reports success.
     *
     * Returns null rather than a malformed JID for anything too short to be a telephone number.
     * A send to a malformed JID is accepted by the server and silently delivered nowhere, which
     * is the failure this app is least able to afford.
     */
    fun jidForNumber(rawNumber: String?): String? {
        val digits = digitsOf(rawNumber) ?: return null
        return "$digits$PERSON_SUFFIX"
    }

    /**
     * The comparable digits of a telephone number, with international prefixes reconciled.
     *
     * `00` and `+` mean the same thing, so `0041...` becomes `41...`. A national number with a
     * single leading zero — `079 123 45 67` — is left as its digits, because guessing a country
     * code from a phone whose owner may have moved country is how a message reaches a stranger.
     * The caller matches on suffix, which handles that case without anyone having to guess.
     */
    fun digitsOf(rawNumber: String?): String? {
        val raw = rawNumber?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val digits = raw.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null

        // "00" is the written form of "+". Strip it, but never strip a single leading zero:
        // that one is a national trunk prefix and dropping it changes the number.
        val international = if (digits.startsWith("00")) digits.drop(2) else digits
        return international.ifEmpty { null }.takeIf { it == null || it.length >= MIN_DIGITS }
    }

    /**
     * Whether a JID names a group rather than a person.
     *
     * Groups are the harder case everywhere in this app — see `GroupReplyPolicy` — and the first
     * thing anything downstream needs to know.
     */
    fun isGroup(jid: String?): Boolean = jid?.trim()?.endsWith(GROUP_SUFFIX) == true

    /**
     * Whether two numbers are the same person, allowing for one of them lacking a country code.
     *
     * Her address book holds `079 123 45 67`; WhatsApp says `41791234567`. Neither can be turned
     * into the other without knowing which country she was in when she typed it, so they are
     * compared by the end — but *how* they are compared matters a great deal, because getting
     * this wrong sends her message to a stranger and reports success.
     *
     * The first version of this took the last seven digits of each and compared those. A test
     * caught what that costs: `079 123 45 67` and the German number `49 30 123 45 67` both end
     * in `1234567`, so her daughter and a stranger in Berlin were the same person. Seven digits
     * is simply not enough to identify anybody — a subscriber number is longer than that, and
     * the tail of one number appears in plenty of others.
     *
     * So the rule is stricter and describes what actually differs between two ways of writing
     * one number: a national trunk zero, and a country code of one to four digits. The whole of
     * the shorter number must be the end of the longer one, and the difference between them must
     * be small enough to be a country code and nothing more.
     */
    fun sameSubscriber(a: String?, b: String?): Boolean {
        val left = digitsOf(a) ?: return false
        val right = digitsOf(b) ?: return false
        if (left == right) return true

        val leftNational = withoutTrunkPrefix(left)
        val rightNational = withoutTrunkPrefix(right)
        if (leftNational == rightNational) return true

        val longer = if (leftNational.length >= rightNational.length) leftNational else rightNational
        val shorter = if (longer === leftNational) rightNational else leftNational

        // Below this, a suffix match is a coincidence rather than a person.
        if (shorter.length < MIN_SIGNIFICANT_DIGITS) return false

        val difference = longer.length - shorter.length
        if (difference !in 1..MAX_COUNTRY_CODE_DIGITS) return false

        return longer.endsWith(shorter)
    }

    /**
     * The tail of a number that says which subscriber it is, or null when there is not enough of
     * one to be sure.
     *
     * [sameSubscriber] compares two numbers; this makes a key, so that fourteen hundred contacts
     * can be looked up by number without comparing every one of them against every message. It
     * keeps exactly what that comparison keeps: the trunk zero goes, the country code is allowed
     * to differ, and what is left is the last [MIN_SIGNIFICANT_DIGITS] — below which a match is a
     * coincidence rather than a person.
     */
    fun subscriberKey(raw: String?): String? {
        val digits = digitsOf(raw) ?: return null
        val national = withoutTrunkPrefix(digits)
        if (national.length < MIN_SIGNIFICANT_DIGITS) return null
        return national.takeLast(MIN_SIGNIFICANT_DIGITS)
    }

    /**
     * A number without its national trunk prefix.
     *
     * One leading zero is how a country tells its own subscribers to dial each other, and it is
     * not part of the number as the rest of the world writes it. Two leading zeros have already
     * been dealt with by [digitsOf] — that is the written form of `+`.
     */
    private fun withoutTrunkPrefix(digits: String): String =
        if (digits.length > 1 && digits.startsWith("0")) digits.drop(1) else digits

    /** Shorter than this is not a telephone number, it is a typo or a short code. */
    const val MIN_DIGITS = 6

    /**
     * How much of a number has to be present before its end may be trusted to identify somebody.
     *
     * Nine, because that is a national number in the countries her family are in — a Swiss
     * subscriber number is nine digits after the trunk zero — and because eight was still short
     * enough for unrelated numbers to collide in testing.
     */
    const val MIN_SIGNIFICANT_DIGITS = 9

    /** Country codes are one to three digits; four leaves room for being written oddly. */
    const val MAX_COUNTRY_CODE_DIGITS = 4
}
