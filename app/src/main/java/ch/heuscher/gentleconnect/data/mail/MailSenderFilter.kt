package ch.heuscher.gentleconnect.data.mail

import javax.mail.internet.InternetAddress

/**
 * Decides whether a mail is from someone she knows.
 *
 * It no longer decides whether the letter is shown. It used to: mail from an address her
 * contacts did not carry was fetched, stored and hidden, which meant a surgery, a pension
 * office or a neighbour who had moved simply did not exist — and a letter that never appears
 * leaves her nothing to ask about. Every letter now reaches her Post screen; the answer here
 * decides how loudly it is drawn and whether it may take one of the few slots on her home
 * screen. See [ch.heuscher.gentleconnect.model.MailPresentation].
 *
 * The name and [isAllowed] are kept because "allowed" still describes the allowlist half of the
 * question honestly, and renaming reaches into the fetcher, the worker and their tests for no
 * behavioural gain.
 *
 * **Matching is on the email address only, never on the sender's display name.** The display
 * name is a header the sender writes, so `From: "Anna" <anything@example.com>` would sail
 * through a name-based check — and the whole point of this filter is that only real people get
 * through. Matching on names also fails in the other direction, because a mail from
 * "Anna Müller" does not equal a contact stored as "Anna", so her actual letters would vanish.
 *
 * Two sources of known addresses:
 *  1. `ContactsRepository.queryContactEmailAddresses()` — her own address book.
 *  2. The caregiver allowlist, for people who mail her but are not in her contacts.
 *
 * Partners and Threema contacts deliberately contribute nothing: neither model carries an email
 * address, so the only thing they could be matched on is a name.
 */
class MailSenderFilter(
    knownAddresses: Set<String> = emptySet(),
    allowlist: Set<String> = emptySet()
) {

    private val allowed: Set<String> =
        (knownAddresses + allowlist)
            .map { normalizeAddress(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Checks a raw `From` header, e.g. `Anna Müller <anna@example.com>`.
     */
    fun isAllowed(fromHeader: String?): Boolean {
        if (fromHeader.isNullOrBlank()) return false
        return isAllowedAddress(parseFromHeader(fromHeader))
    }

    /**
     * Checks a bare address that has already been parsed out of the header.
     */
    fun isAllowedAddress(senderAddress: String?): Boolean {
        val address = normalizeAddress(senderAddress ?: return false)
        if (address.isEmpty()) return false
        return allowed.contains(address)
    }

    companion object {

        /**
         * Pulls the address out of a `From` header. Returns an empty string when the header
         * carries no address at all — which is never allowed through, since there is then
         * nothing trustworthy to match on.
         */
        fun parseFromHeader(fromHeader: String): String {
            val trimmed = fromHeader.trim()
            if (trimmed.isEmpty()) return ""

            try {
                val parsed = InternetAddress.parse(trimmed, false).firstOrNull()?.address?.trim()
                // Non-strict parsing hands back a bare display name as though it were an
                // address, so "Anna" arrives here as "Anna". Requiring an @ is what keeps a
                // name from being treated as something matchable.
                if (!parsed.isNullOrEmpty() && parsed.contains("@")) return parsed
            } catch (e: Throwable) {
                // Fall through to the regex below: a malformed header is common enough that it
                // must not throw, and a mail whose sender cannot be determined is simply hidden.
            }

            val angled = Regex("<([^>]+)>").find(trimmed)?.groupValues?.get(1)?.trim()
            if (!angled.isNullOrEmpty() && angled.contains("@")) return angled
            if (trimmed.contains("@") && !trimmed.contains(" ")) return trimmed

            return ""
        }

        private fun normalizeAddress(raw: String): String = raw.trim().lowercase()
    }
}
