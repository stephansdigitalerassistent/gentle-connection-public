package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactPhone
import ch.heuscher.gentleconnect.model.PhoneKind
import ch.heuscher.gentleconnect.model.FavouriteArrangement
import java.io.File

/**
 * Repository for reading system contacts via ContactsContract when READ_CONTACTS permission is granted.
 */
class ContactsRepository(private val context: Context) {

    /**
     * Checks if READ_CONTACTS permission is granted.
     */
    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Everyone starred in the phone's own address book, picked out of a scan already taken.
     *
     * This is the answer to *who* her favourites are. Starring is a gesture a family member can
     * make in the app they already use, on a contact that already has the right spelling, the
     * right number and a photograph — none of which a name typed into a website has.
     *
     * It takes the scan rather than performing one on purpose. Reading the address book is the
     * most expensive thing this class does, and when this asked for its own read the home screen
     * paid for three of them — one here, one for the favourites and one for the phone book — on
     * the main thread, every time she pressed the home button.
     */
    fun starredContacts(systemContacts: List<Contact>): List<Contact> =
        systemContacts.filter { it.isFavorite }

    /**
     * Her favourites: starred on the phone, arranged by the caregiver, picked out of a scan
     * already taken.
     *
     * The priority used to run the other way — a caregiver-typed list won, and the phone's own
     * starred contacts were only consulted when that list was empty. That made the website the
     * place to *add a person*, which meant retyping somebody who was already in the address book,
     * with a photo supplied as a URL, into an entry that then drifted out of date in silence.
     *
     * Starred contacts win; when nothing at all is starred it falls back to the legacy
     * caregiver list, enriched with whatever details the address book holds for those people.
     *
     * Performs no I/O, and there is deliberately no overload that takes one. This runs inside a
     * `combine` that fires whenever anything it watches changes, and when it read the address
     * book itself that put a full contacts-provider scan on the main thread on every press of
     * the home button.
     *
     * @param systemContacts her address book as most recently read. See [querySystemContacts].
     * @param caregiverFavorites the old hand-typed list. Still honoured when nothing is starred
     *   at all, so a phone set up the previous way does not lose its home screen the day it
     *   updates — but nothing writes to it any more.
     * @param favouriteOrder contact ids in the caregiver's order. See [FavouriteArrangement].
     */
    fun resolveFavouritesFrom(
        systemContacts: List<Contact>,
        caregiverFavorites: List<Contact>,
        favouriteOrder: List<String> = emptyList()
    ): List<Contact> {
        val starred = starredContacts(systemContacts)
        if (starred.isNotEmpty()) {
            return FavouriteArrangement.arrange(starred, favouriteOrder)
        }
        return legacyCaregiverFavourites(caregiverFavorites, systemContacts)
    }

    /** The pre-starring behaviour, kept only for a phone that has nothing starred yet. */
    private fun legacyCaregiverFavourites(
        caregiverFavorites: List<Contact>,
        systemContacts: List<Contact>
    ): List<Contact> {
        // Distinct before anything else, including before the permission check. Nothing stops
        // the caregiver portal from listing the same person twice, and two faces that look the
        // same and do the same thing are not a choice she can make — they are a question she
        // cannot answer.
        val favorites = caregiverFavorites.distinctBy {
            val number = normalizePhoneNumber(it.phoneNumber)
            if (number.isNotBlank()) number else it.name.trim().lowercase()
        }

        if (!hasReadContactsPermission()) {
            return favorites
        }

        // 1. Show caregiver-selected favorites (enriched with system photoUri/numbers if available)
        if (favorites.isNotEmpty()) {
            return favorites.map { caregiverContact ->
                val match = findMatchingSystemContact(caregiverContact, systemContacts)
                if (match != null) {
                    caregiverContact.copy(
                        photoUri = caregiverContact.photoUri ?: match.photoUri,
                        phoneNumber = if (caregiverContact.phoneNumber.isBlank()) match.phoneNumber else caregiverContact.phoneNumber
                    )
                } else {
                    caregiverContact
                }
            }
        }

        // Starred contacts are handled by the caller now; reaching here means there are none.
        return emptyList()
    }

    /**
     * Queries system contacts with phone numbers and photos from ContactsContract.
     */
    fun querySystemContacts(): List<Contact> {
        if (!hasReadContactsPermission()) return emptyList()

        val rows = mutableListOf<ContactDeduplicator.Row>()
        val contentResolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )

        try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starredIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val primaryIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
                val superPrimaryIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)

                // Read the rows out, then let ContactDeduplicator decide who is a person. The
                // rule is subtle enough — and got it wrong badly enough — to be worth having
                // somewhere a test can reach it.
                while (c.moveToNext()) {
                    rows.add(
                        ContactDeduplicator.Row(
                            contactId = if (idIdx >= 0) c.getString(idIdx) ?: "" else "",
                            name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                            number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else "",
                            photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null,
                            isFavorite = if (starredIdx >= 0) c.getInt(starredIdx) == 1 else false,
                            kind = if (typeIdx >= 0) phoneKindOf(c.getInt(typeIdx)) else PhoneKind.OTHER,
                            isPrimary = primaryIdx >= 0 && c.getInt(primaryIdx) == 1,
                            isSuperPrimary = superPrimaryIdx >= 0 && c.getInt(superPrimaryIdx) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Handle permission or cursor errors gracefully
        }

        val contacts = ContactDeduplicator.dedupe(rows)
        val avatarIndex = buildAvatarIndex()
        return contacts.map { contact ->
            if (contact.photoUri.isNullOrBlank()) {
                val waPhoto = avatarIndex.forNumber(contact.phoneNumber)
                if (waPhoto != null) contact.copy(photoUri = waPhoto) else contact
            } else {
                contact
            }
        }
    }

    /**
     * Every email address belonging to someone in her address book, lowercased.
     *
     * This is what the mail sender filter matches against. It has to come from
     * `ContactsContract` because [Contact] carries a phone number and a Threema ID and no email
     * address at all — and matching on a sender's *display name* instead is worthless: that
     * header is written by whoever sent the mail, so anyone can put "Anna" in it.
     */
    /**
     * Every telephone number in her address book, paired with whose it is.
     *
     * [querySystemContacts] answers with one entry per person, because [Contact] holds one
     * number and the screens that show faces want one face. That is the wrong shape for deciding
     * *whose* a number is: somebody with a mobile and two office lines is reachable on three, and
     * on the two that did not become their [Contact.phoneNumber] they were nobody — while the SMS
     * import, which asks the system about the number itself, knew perfectly well who they were.
     * One message, two answers, and her thread with that person split in two on screen.
     *
     * Numbers are returned exactly as the address book writes them; normalising is the caller's,
     * so it matches however the caller normalises everywhere else.
     *
     * @return one [ContactPhone] per number rather than per person: the raw number as the
     *   address book writes it, whose it is, and what kind of line the address book says it is.
     */
    fun queryEveryNumber(): List<ContactPhone> {
        if (!hasReadContactsPermission()) return emptyList()

        val numbers = mutableListOf<ContactPhone>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                if (idIdx < 0 || numberIdx < 0) return emptyList()
                while (c.moveToNext()) {
                    val contactId = c.getString(idIdx)?.trim().orEmpty()
                    val number = c.getString(numberIdx)?.trim().orEmpty()
                    if (contactId.isEmpty() || number.isEmpty()) continue
                    val kind = if (typeIdx >= 0) phoneKindOf(c.getInt(typeIdx)) else PhoneKind.OTHER
                    numbers.add(ContactPhone(number = number, contactId = contactId, kind = kind))
                }
            }
        } catch (e: Exception) {
            // Same as everywhere else here: unreadable contacts are not worth a crash, and the
            // caller is no worse off than before this existed.
            return emptyList()
        }

        return numbers
    }

    /**
     * What the address book says a number is.
     *
     * Only the types that are certainly one or the other are claimed; everything else stays
     * [PhoneKind.OTHER], which is "not stated" rather than "landline" — see [PhoneKind]. Deciding
     * from the number itself was considered and rejected: mobile prefixes are a per-country
     * question and a wrong guess sends a help request to a telephone that cannot receive it.
     */
    private fun phoneKindOf(type: Int): PhoneKind = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> PhoneKind.MOBILE

        ContactsContract.CommonDataKinds.Phone.TYPE_HOME,
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN,
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME,
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX,
        ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> PhoneKind.LANDLINE

        else -> PhoneKind.OTHER
    }

    fun queryContactEmailAddresses(): Set<String> {
        if (!hasReadContactsPermission()) return emptySet()

        val addresses = mutableSetOf<String>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                null,
                null,
                null
            )

            cursor?.use { c ->
                val addressIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                if (addressIdx < 0) return@use
                while (c.moveToNext()) {
                    val address = c.getString(addressIdx)?.trim()?.lowercase()
                    if (!address.isNullOrBlank()) {
                        addresses.add(address)
                    }
                }
            }
        } catch (e: Exception) {
            // Same posture as querySystemContacts: a contacts read that fails yields nothing
            // rather than crashing her launcher.
        }

        return addresses
    }

    private fun findMatchingSystemContact(
        caregiverContact: Contact,
        systemContacts: List<Contact>
    ): Contact? {
        val targetNum = normalizePhoneNumber(caregiverContact.phoneNumber)
        if (targetNum.isNotBlank()) {
            val numMatch = systemContacts.firstOrNull {
                val sysNum = normalizePhoneNumber(it.phoneNumber)
                sysNum == targetNum || (targetNum.length >= 7 && sysNum.endsWith(targetNum.takeLast(7)))
            }
            if (numMatch != null) return numMatch
        }

        val targetName = caregiverContact.name.trim()
        if (targetName.isNotBlank()) {
            return systemContacts.firstOrNull {
                it.name.trim().equals(targetName, ignoreCase = true)
            }
        }

        return null
    }

    /**
     * Finds a system contact matching the given phone number.
     *
     * Uses PhoneLookup.CONTENT_FILTER_URI for fast indexed matching against the system address book.
     * If no system photo exists, falls back to any cached WhatsApp profile picture on disk.
     */
    fun findContactByPhoneNumber(phoneNumber: String): Contact? {
        if (!hasReadContactsPermission() || phoneNumber.isBlank()) return null
        val target = phoneNumber.trim()
        if (target.isBlank()) return null

        try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(target)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.NUMBER
            )
            context.contentResolver.query(
                lookupUri,
                projection,
                null,
                null,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.PhoneLookup._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val photoIdx = c.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                val numIdx = c.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)

                // Every match, not the first one. The same person is in her address book twice
                // and only one of the two entries carries the photograph — see
                // [ContactDeduplicator.bestOf], which is where the choice is made and proved.
                val candidates = mutableListOf<Contact>()
                while (c.moveToNext()) {
                    candidates.add(
                        Contact(
                            id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else "",
                            name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else "",
                            phoneNumber = if (numIdx >= 0) c.getString(numIdx).orEmpty() else target,
                            photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null
                        )
                    )
                }

                ContactDeduplicator.bestOf(candidates)?.let { best ->
                    return best.copy(photoUri = best.photoUri ?: findWhatsAppAvatar(target))
                }
            }
        } catch (e: Exception) {
            // Permission or cursor failure
        }

        // Check if there is a WhatsApp avatar on disk for this number even if not in system contacts
        val waPhoto = findWhatsAppAvatar(target)
        if (waPhoto != null) {
            return Contact(
                id = target,
                name = target,
                phoneNumber = target,
                photoUri = waPhoto
            )
        }
        return null
    }

    /**
     * Finds a contact in the address book matching the given email address.
     *
     * Used on the View screen (LookScreen) to show the sender's face on mail items.
     * Uses the indexed CONTENT_LOOKUP_URI path (with selection fallback) so it does
     * not perform a full table scan.
     */
    fun findContactByEmail(email: String): Contact? {
        if (!hasReadContactsPermission() || email.isBlank()) return null
        val target = email.trim()
        if (target.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.PHOTO_URI,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )

        try {
            // 1. Indexed lookup URI query
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI,
                Uri.encode(target)
            )

            context.contentResolver.query(
                lookupUri,
                projection,
                null,
                null,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.PHOTO_URI)

                // Every match, for the same reason as the lookup by number above.
                val candidates = mutableListOf<Contact>()
                while (c.moveToNext()) {
                    candidates.add(
                        Contact(
                            id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else "",
                            name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else "",
                            phoneNumber = "",
                            photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null
                        )
                    )
                }

                ContactDeduplicator.bestOf(candidates)?.let { best ->
                    // Her messenger avatar is worth a query against the phone table, but only
                    // when the address book has left us with nothing to draw.
                    if (!best.photoUri.isNullOrBlank()) return best

                    val phoneNum = if (best.id.isNotBlank()) {
                        findPhoneNumberForContactId(best.id).orEmpty()
                    } else ""
                    return best.copy(
                        phoneNumber = phoneNum,
                        photoUri = findWhatsAppAvatar(phoneNum)
                    )
                }
            }

            // 2. Direct selection query fallback if lookup URI returned no match
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Email.ADDRESS} = ?",
                arrayOf(target),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.PHOTO_URI)

                // Every match, for the same reason as the lookup by number above.
                val candidates = mutableListOf<Contact>()
                while (c.moveToNext()) {
                    candidates.add(
                        Contact(
                            id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else "",
                            name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else "",
                            phoneNumber = "",
                            photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null
                        )
                    )
                }

                ContactDeduplicator.bestOf(candidates)?.let { best ->
                    // Her messenger avatar is worth a query against the phone table, but only
                    // when the address book has left us with nothing to draw.
                    if (!best.photoUri.isNullOrBlank()) return best

                    val phoneNum = if (best.id.isNotBlank()) {
                        findPhoneNumberForContactId(best.id).orEmpty()
                    } else ""
                    return best.copy(
                        phoneNumber = phoneNum,
                        photoUri = findWhatsAppAvatar(phoneNum)
                    )
                }
            }
        } catch (e: Exception) {
            // Permission or cursor failure
        }
        return null
    }

    private fun findPhoneNumberForContactId(contactId: String): String? {
        if (contactId.isBlank()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx < 0) return null
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val primaryIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
                val superPrimaryIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
                val rows = mutableListOf<ContactDeduplicator.Row>()
                while (c.moveToNext()) {
                    val number = c.getString(numIdx).orEmpty()
                    if (ContactDeduplicator.normalizeNumber(number).isBlank()) continue
                    rows.add(
                        ContactDeduplicator.Row(
                            contactId = contactId,
                            name = "",
                            number = number,
                            kind = if (typeIdx >= 0) phoneKindOf(c.getInt(typeIdx)) else PhoneKind.OTHER,
                            isPrimary = primaryIdx >= 0 && c.getInt(primaryIdx) == 1,
                            isSuperPrimary = superPrimaryIdx >= 0 && c.getInt(superPrimaryIdx) == 1
                        )
                    )
                }
                // The same choice her faces make, so a sender found by address dials the same line.
                if (rows.isNotEmpty()) return ContactDeduplicator.bestNumberOf(rows).number
            }
        } catch (e: Exception) {
            // Quietly ignore
        }
        return null
    }

    /**
     * Finds a cached WhatsApp profile picture for the given phone number, if one exists on disk.
     *
     * Used only as a fallback when the address book contact has no photoUri.
     */
    fun findWhatsAppAvatar(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        val targetDigits = WhatsAppNumber.digitsOf(phoneNumber)
            ?: phoneNumber.filter { it.isDigit() }
        if (targetDigits.isEmpty()) return null

        val avatarDir = File(context.filesDir, "whatsapp/avatars")
        if (!avatarDir.exists() || !avatarDir.isDirectory) return null

        // The three names an avatar is actually stored under, asked for by name. This is the
        // path taken for somebody we have a picture of, and it stays three questions to the
        // filesystem rather than a reading of the whole directory: this is called once per
        // person on screen, and listing every avatar on the phone to answer "is there one for
        // her daughter" is how a message list with twelve people in it became slow.
        for (name in arrayOf(
            "$targetDigits.jpg",
            "$targetDigits@s.whatsapp.net.jpg",
            "$targetDigits.s.whatsapp.net.jpg"
        )) {
            val direct = File(avatarDir, name)
            if (direct.exists() && direct.length() > 0) {
                return "file://${direct.absolutePath}"
            }
        }

        // Only now, having failed to guess the name, is the directory worth reading.
        return buildAvatarIndex().matchBySubscriber(targetDigits)
    }

    /**
     * The avatar directory read once, so that a scan of her whole address book asks the
     * filesystem about it once rather than once per person without a photograph.
     */
    private class AvatarIndex(files: List<File>) {
        private val validFiles =
            files.filter { it.isFile && it.length() > 0L && it.name.endsWith(".jpg") }
        private val byName = validFiles.associateBy { it.name }

        fun forNumber(phoneNumber: String?): String? {
            if (phoneNumber.isNullOrBlank()) return null
            val targetDigits = WhatsAppNumber.digitsOf(phoneNumber)
                ?: phoneNumber.filter { it.isDigit() }
            if (targetDigits.isEmpty()) return null

            // 1. Direct file matches
            byName["$targetDigits.jpg"]?.let { return "file://${it.absolutePath}" }
            byName["$targetDigits@s.whatsapp.net.jpg"]?.let { return "file://${it.absolutePath}" }
            byName["$targetDigits.s.whatsapp.net.jpg"]?.let { return "file://${it.absolutePath}" }

            // 2. Match across avatar files using WhatsAppNumber.sameSubscriber
            return matchBySubscriber(targetDigits)
        }

        fun matchBySubscriber(targetDigits: String): String? {
            for (file in validFiles) {
                val base = file.name.removeSuffix(".jpg")
                    .removeSuffix(".s.whatsapp.net")
                    .removeSuffix("@s.whatsapp.net")
                    .removePrefix("avatar_")
                if (WhatsAppNumber.sameSubscriber(base, targetDigits)) {
                    return "file://${file.absolutePath}"
                }
            }
            return null
        }
    }

    private fun buildAvatarIndex(): AvatarIndex {
        val avatarDir = File(context.filesDir, "whatsapp/avatars")
        if (!avatarDir.exists() || !avatarDir.isDirectory) {
            return AvatarIndex(emptyList())
        }
        val files = avatarDir.listFiles()?.toList() ?: emptyList()
        return AvatarIndex(files)
    }

    private fun normalizePhoneNumber(phone: String): String =
        ContactDeduplicator.normalizeNumber(phone)
}
