package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.PhoneKind

/**
 * One entry per person, from an address-book table that has one row per telephone number.
 *
 * Two different mistakes are possible here and they pull in opposite directions.
 *
 * Someone with a mobile, a landline and a work number arrives as three rows. Shown as three
 * entries she gets three identical faces side by side, each dialling a different number, with no
 * way to tell them apart and no reason to think they are the same woman.
 *
 * But keying on the number instead loses people. Her address book has fifty entries that share a
 * number with somebody else — colleagues on one office switchboard, `+41 31 555 01 12` reaching
 * both Gregor Albrecht and Sandro Bieri — and nine entries whose stored number is the string
 * "+41" and nothing more. Deduplicating by number deleted all fifty, silently, and a name missing
 * from her address book is not a thing she can notice is missing.
 *
 * So the person is the contact id. Numbers only collapse rows belonging to the same person.
 *
 * Which of a person's numbers her face dials is [bestNumberOf]'s question, not the address book's
 * row order. Taking the first row put Nono's home landline on her face although her mobile is the
 * number marked primary — the provider sorts only by name, and between rows of one name the order
 * is whatever it happens to be.
 *
 * The one case where two ids still collapse is when she could not possibly tell them apart: same
 * name and same number, which is what two unnamed entries for the same number look like on her
 * screen — the same line of digits, twice.
 */
object ContactDeduplicator {

    /** One row of the phone table, already read out of the cursor. */
    data class Row(
        val contactId: String,
        val name: String,
        val number: String,
        val photoUri: String? = null,
        val isFavorite: Boolean = false,
        val kind: PhoneKind = PhoneKind.OTHER,
        /** `IS_PRIMARY`: the number an account or the owner marked as this person's own. */
        val isPrimary: Boolean = false,
        /** `IS_SUPER_PRIMARY`: the default number chosen by hand in the contacts app. */
        val isSuperPrimary: Boolean = false
    )

    fun dedupe(rows: List<Row>): List<Contact> {
        // A row with no usable number tells her nothing and dials nothing.
        val usable = rows.filter { normalizeNumber(it.number).isNotBlank() }

        // Each person keeps their place in the list (their first row), but the number is chosen.
        val bestById = usable
            .filter { it.contactId.isNotBlank() }
            .groupBy { it.contactId }
            .mapValues { (_, own) -> bestNumberOf(own) }

        val people = mutableListOf<Contact>()
        val seenContactIds = mutableSetOf<String>()
        val seenIndistinguishable = mutableSetOf<String>()

        for (first in usable) {
            val row = if (first.contactId.isNotBlank()) {
                // Already have this person; this is another of their numbers.
                if (!seenContactIds.add(first.contactId)) continue
                bestById.getValue(first.contactId)
            } else {
                first
            }
            val number = normalizeNumber(row.number)

            // Same name, same number, different entry: two rows she would read as one.
            val asSheWouldSeeIt = "${row.name.trim().lowercase()}|$number"
            if (!seenIndistinguishable.add(asSheWouldSeeIt)) continue

            people.add(
                Contact(
                    id = row.contactId,
                    name = row.name,
                    phoneNumber = row.number,
                    photoUri = row.photoUri,
                    isFavorite = row.isFavorite
                )
            )
        }

        return people
    }

    /**
     * The number to dial among one person's rows: the default chosen by hand, then the primary,
     * then a mobile, then a number of no stated kind, and a landline last — she may be calling a
     * house nobody is in, while the mobile is in a pocket. Row order only breaks a tie.
     */
    fun bestNumberOf(rows: List<Row>): Row =
        rows.withIndex().minWith(
            compareBy<IndexedValue<Row>>(
                { if (it.value.isSuperPrimary) 0 else 1 },
                { if (it.value.isPrimary) 0 else 1 },
                {
                    when (it.value.kind) {
                        PhoneKind.MOBILE -> 0
                        PhoneKind.OTHER -> 1
                        PhoneKind.LANDLINE -> 2
                    }
                },
                { it.index }
            )
        ).value

    /**
     * Which of several address-book entries for the same person to show: the one with a face.
     *
     * A lookup by number or by e-mail address answers with a row per matching entry, and her
     * address book has people in it twice. Laura is there as "Laura Hartmann", imported from a
     * mail account with no picture, and as `Laura "Lala Lulu" Hartmann`, the entry the family
     * actually keeps — same telephone number, and the photograph is on the second one. Taking the
     * first row the provider happened to return got the empty one, so her daughter's messages
     * drew "LH" in a circle while the photograph sat in the address book under the very number
     * the message arrived from.
     *
     * Only a photograph decides it, and only against a row that has none. Everything else about
     * the first row is left alone: which of two names is the better name is not a question this
     * can answer, and a lookup that started reordering people by name would be a different and
     * much worse change than the one this is.
     */
    fun bestOf(candidates: List<Contact>): Contact? =
        candidates.firstOrNull { !it.photoUri.isNullOrBlank() } ?: candidates.firstOrNull()

    fun normalizeNumber(phone: String): String = phone.replace(Regex("[^0-9+]"), "")
}
