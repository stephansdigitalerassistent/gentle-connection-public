package ch.heuscher.gentleconnect.model

/**
 * One telephone number in her address book, with what kind of line it is and whose it is.
 *
 * [Contact] carries a single number, because the screens that show a face want one number to
 * dial. That is the wrong shape for asking *how can this person be reached*: her son is in the
 * address book with a mobile and two office lines, and which of the three became his
 * [Contact.phoneNumber] is decided by the order the contacts provider happens to return rows in.
 *
 * On her telephone that order put his **work landline** first. Everything hung off it: the Threema
 * directory was asked about the landline (no match, so no Threema), the WhatsApp check looked for
 * a profile picture for the landline (none, so no WhatsApp), and the help request fell back to an
 * SMS — to a landline, which cannot receive one. He was in the support group and could not be
 * reached at all, and nothing anywhere said so.
 */
data class ContactPhone(
    val number: String,
    val contactId: String,
    val kind: PhoneKind = PhoneKind.OTHER
)

/**
 * What the address book says a number is.
 *
 * [OTHER] is not a third kind of telephone, it is *not stated* — and it must not be treated as a
 * landline: an untyped number is very often somebody's only number, and it is the one we fall
 * back to before writing to something that certainly cannot be read.
 */
enum class PhoneKind {
    MOBILE,
    LANDLINE,
    OTHER
}

/** Choosing among the several numbers one person has. */
object ContactPhones {

    /**
     * The number to send a message to: the mobile, and only a landline when there is nothing else.
     *
     * Address-book order decides between two of a kind, because that is the order she sees and
     * the first one is the one that answers.
     *
     * A landline is still returned when it is all there is. It is also the number her button
     * dials, and a call to a landline is a perfectly good call — it is only the SMS that will
     * fail, and that failure is visible on the portal rather than silent.
     */
    fun bestForMessages(phones: List<ContactPhone>): String? {
        val usable = phones.filter { it.number.isNotBlank() }
        if (usable.isEmpty()) return null
        return (usable.firstOrNull { it.kind == PhoneKind.MOBILE }
            ?: usable.firstOrNull { it.kind == PhoneKind.OTHER }
            ?: usable.first()).number
    }
}
