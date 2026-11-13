package ch.heuscher.gentleconnect.data

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Adding somebody to the address book, and correcting somebody already in it.
 *
 * Handed to the telephone's own contacts app rather than written here. Two reasons, and the
 * second is the important one:
 *
 * - Android's editor is where the rest of the phone puts contacts, so a person saved from this
 *   app is the same person the address book, the messages and the call log already know about.
 *   An editor of this app's own would need `WRITE_CONTACTS`, a permission that lets an app
 *   quietly alter every person she has, in exchange for a screen the system already provides.
 * - This is not for her. Editing an address book is spelling, and deciding, and undoing — the
 *   three things this app exists to keep off her screen. It is here for whoever sets the phone
 *   up with her: her son at the kitchen table, saving the number the doctor rang from. So it
 *   deliberately lands in a screen that looks nothing like the rest of this app, because it is
 *   not part of the app she uses.
 *
 * Every entry point is quiet about failure and says so in its return value. A phone with no
 * contacts app is a phone where nothing happens when the button is pressed, which is a great deal
 * better than a crash out of her launcher.
 */
object ContactEditing {

    /**
     * Offer to save this number: as a new person, or onto somebody already in the book.
     *
     * `ACTION_INSERT_OR_EDIT` rather than `ACTION_INSERT` on purpose. The number the doctor's
     * surgery rang from is usually a second number for a person who is already there, and plain
     * insert has no way to say so — it makes a second "Dr. Meier" and the address book slowly
     * fills with halves of people.
     */
    fun saveNumber(context: Context, number: String, suggestedName: String? = null): Boolean {
        val cleaned = number.trim()
        if (cleaned.isEmpty()) return false

        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, cleaned)
            suggestedName?.takeIf { it.isNotBlank() }?.let {
                putExtra(ContactsContract.Intents.Insert.NAME, it)
            }
            // The launcher is not a task a contacts editor should be pushed onto: leaving it
            // there means her back button walks out of the editor into a half-finished contact
            // screen instead of home.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return start(context, intent)
    }

    /**
     * Open somebody who is already in the address book, in the telephone's editor.
     *
     * @param contactId Android's own id for the person, as [CallLogRepository] and
     *   [ContactsRepository] hand it over.
     */
    fun editContact(context: Context, contactId: String): Boolean {
        val id = contactId.trim().toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)

        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            // Some contacts apps refuse ACTION_EDIT without this, having been written when it
            // was still required. Harmless where it is not.
            putExtra("finishActivityOnSaveCompleted", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
