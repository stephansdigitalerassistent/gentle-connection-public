package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.SendState
import ch.heuscher.gentleconnect.model.isSameMessageAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe local store for incoming normalized messages.
 * Includes automatic deduplication for incoming notifications.
 */
class LocalMessageStore {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /**
     * Add or update a message in the local store.
     * Deduplicates incoming notifications based on sender name, content, and time window (30s).
     */
    fun addOrUpdateMessage(incoming: Message) {
        _messages.update { currentList -> fold(currentList, listOf(incoming)) }
    }

    /**
     * The same, for a batch — an import rather than an arrival.
     *
     * One update instead of several hundred. Reading her SMS history calls this with everything at
     * once, and doing it a message at a time meant copying the whole list once per message and
     * publishing a new value to every screen each time: the list is scanned for duplicates, so
     * that is quadratic, and it was being paid on the main-thread-facing flow.
     */
    fun addOrUpdateMessages(incoming: List<Message>) {
        if (incoming.isEmpty()) return
        _messages.update { currentList -> fold(currentList, incoming) }
    }

    /**
     * [existing] with [incoming] merged in: a message already known is updated in place, and a new
     * one goes on the front.
     *
     * The duplicate rule is [isSameMessageAs], shared with the conversation that merges this store
     * with what has been recorded in the database — the same message must not be one thing here and
     * another thing there.
     */
    private fun fold(existing: List<Message>, incoming: List<Message>): List<Message> {
        val merged = existing.toMutableList()
        val added = ArrayList<Message>(incoming.size)

        incoming.forEach { message ->
            // The batch is checked against itself as well as against what is already held. A
            // history import can carry the same message twice on its own — the SMS provider keeps
            // a copy per recipient of a text sent to two people — and folding only against the
            // stored list would let both through.
            val inBatch = added.indexOfFirst { it.isSameMessageAs(message) }
            if (inBatch != -1) {
                added[inBatch] = message.mergedOnto(added[inBatch])
                return@forEach
            }

            val inStore = merged.indexOfFirst { it.isSameMessageAs(message) }
            if (inStore != -1) {
                merged[inStore] = message.mergedOnto(merged[inStore])
            } else {
                added += message
            }
        }

        return if (added.isEmpty()) merged else added + merged
    }

    /**
     * Keeps the id, the reaction and the fact of having been read; everything else is the newer
     * copy.
     *
     * **Seen once is seen for good.** The read flag used to come from whichever copy arrived last,
     * and the copy that arrives last is the SMS provider's — re-read on every launch and on every
     * text that arrives. So a message she had opened came back unread minutes later, and with it
     * the mark on the Message button, which is the whole of what that mark means. Pressing it
     * cleared nothing she could see, because what she had cleared was put straight back.
     *
     * Android's own read flag is written back too where the app is allowed to
     * ([rememberSmsRead]); this is what holds within a single run.
     */
    private fun Message.mergedOnto(known: Message): Message =
        copy(
            id = known.id,
            reaction = known.reaction ?: reaction,
            isRead = known.isRead || isRead
        )

    /**
     * Her text messages, as the phone itself holds them: what people sent her, and what she wrote
     * back.
     *
     * **Both halves.** This read the inbox alone until 2026-08-16, so every SMS conversation in the
     * app showed the other person's side and no evidence that she had ever answered — the exact
     * failure the WhatsApp import documents and avoids, arrived at independently on the one
     * transport where her replies were already sitting on the phone waiting to be read. For
     * somebody whose memory is fading, "did I answer?" is not a tidiness question; it is the
     * difference between writing again and leaving her daughter unanswered.
     *
     * **And more than a handful.** The limit was fifteen messages in total across every
     * conversation, which is a morning of a bank, a pharmacy and a parking app — enough to push
     * anything anybody actually wrote off the end of what the app could see. [SMS_IMPORT_LIMIT] is
     * about her whole history instead, capped only so that a phone with twenty years of texts on it
     * does not read all of them into memory on every launch.
     *
     * Drafts, outbox and failed messages are deliberately not read. A draft is something she never
     * sent, and showing it in the thread as though she had is worse than not showing it at all.
     */
    suspend fun loadSmsFromPhone(context: Context) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext
        }
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.TYPE} IN (?, ?)",
                arrayOf(
                    Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                    Telephony.Sms.MESSAGE_TYPE_SENT.toString()
                ),
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)

                // One address book lookup per person rather than per message. A thread of two
                // hundred texts with her sister is two hundred identical PhoneLookup queries
                // otherwise, and the import is already reading a cursor of everything she has.
                val contactsByAddress = HashMap<String, ResolvedContact?>()
                val imported = ArrayList<Message>(SMS_IMPORT_LIMIT)
                // Which of these she has already read, as this app remembers it — see
                // [rememberSmsRead]. The phone's own flag is the other half and is not always
                // writable.
                val alreadyRead = readTextMessages(context)
                val stillHere = HashSet<String>(alreadyRead.size)

                var count = 0
                while (c.moveToNext() && count < SMS_IMPORT_LIMIT) {
                    count++
                    val id = c.getString(idIdx)
                    val rawAddr = c.getString(addrIdx) ?: ""
                    val body = c.getString(bodyIdx) ?: ""
                    val date = c.getLong(dateIdx)
                    val outgoing = c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
                    val messageId = SMS_ID_PREFIX + id
                    val remembered = messageId in alreadyRead
                    if (remembered) stillHere += messageId
                    // Hers counts as read because she wrote it. Reading the column would be
                    // wrong here as well as pointless: the provider's READ flag on a sent
                    // message is not about her having seen it. And what this app remembers counts
                    // too, for the phone that will not let it write the flag back.
                    val isRead = outgoing || c.getInt(readIdx) == 1 || remembered

                    // Never "SMS". That is the channel, not a person, and it is the one word
                    // this app does not put on her screen — besides being untranslated English
                    // in the middle of a German list.
                    //
                    // For a message of hers the address is who she wrote *to*, so this names the
                    // counterpart either way and the two halves of a conversation file together.
                    // See ConversationKey, which keys on exactly this.
                    if (!contactsByAddress.containsKey(rawAddr)) {
                        // containsKey rather than getOrPut: a number that is *not* in her address
                        // book caches as null, and getOrPut treats null as a miss and would look
                        // it up again for every message. Banks and pharmacies are precisely the
                        // senders with no contact and the most messages.
                        contactsByAddress[rawAddr] = resolveContact(context, rawAddr)
                    }
                    val contact = contactsByAddress[rawAddr]
                    val resolvedName = contact?.name
                        ?: rawAddr.ifEmpty { context.getString(R.string.message_unknown_sender) }

                    imported += Message(
                        id = messageId,
                        senderName = resolvedName,
                        senderAddress = rawAddr,
                        direction = if (outgoing) {
                            MessageDirection.OUTGOING
                        } else {
                            MessageDirection.INCOMING
                        },
                        // It left the phone: the provider filed it as sent, which is the only
                        // thing that puts a message in this box.
                        sendState = if (outgoing) SendState.SENT else null,
                        // Carried so the screen can tell a person from a company without
                        // guessing: someone in her address book is a person, full stop.
                        contactId = contact?.id,
                        text = body,
                        timestamp = date,
                        sourcePackage = "com.google.android.apps.messaging",
                        isRead = isRead,
                        receivedAt = date
                    )
                }

                // Forget what is no longer here. The remembered set would otherwise grow for the
                // life of the phone, and a message that has fallen off the end of the import or
                // been deleted is not something she can read again.
                if (stillHere.size != alreadyRead.size) rememberReadTextMessages(context, stillHere)

                // One publication rather than several hundred: every screen reading this store
                // recomposes on each value, and an import that arrived a message at a time made
                // the whole list rebuild itself once per message.
                addOrUpdateMessages(imported)
            }
        } catch (e: Exception) {
            // Log or ignore safely
        }
    }

    /** Somebody in her address book: the name to show, and the id that proves she knows them. */
    private data class ResolvedContact(val name: String, val id: String?)

    private fun resolveContact(context: Context, phoneNumber: String): ResolvedContact? {
        if (phoneNumber.isEmpty()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.CONTACT_ID
                ),
                null,
                null,
                null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val idIdx = c.getColumnIndex(ContactsContract.PhoneLookup.CONTACT_ID)
                    val name = if (nameIdx != -1) c.getString(nameIdx) else null
                    if (name.isNullOrBlank()) {
                        null
                    } else {
                        ResolvedContact(
                            name = name,
                            id = if (idIdx != -1) c.getString(idIdx) else null
                        )
                    }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Write down that she has read a text message — here, and in the telephone's own record.
     *
     * The read flag on her screen lives in this store, which is built fresh from the SMS provider
     * every time the app starts and every time a text arrives. So with nothing written back, "read"
     * lasted until the next import and no longer: the mark on the Message button came back by
     * itself, and there was nothing she could do about it.
     *
     * Both records, because neither is enough alone. The phone's own flag is the right place for
     * this and is what her other apps read — but writing it is allowed only while this app holds
     * the messages role, and it is refused the moment something else does. This app's own list has
     * no such condition and is what holds when the write is refused; it is pruned back to what the
     * import still contains, so it cannot grow forever.
     */
    suspend fun rememberSmsRead(context: Context, messageId: String) = withContext(Dispatchers.IO) {
        if (!messageId.startsWith(SMS_ID_PREFIX)) return@withContext
        val rowId = messageId.removePrefix(SMS_ID_PREFIX).ifEmpty { return@withContext }

        // This app's own record first, because it always works. The write below is refused on a
        // phone where something else is the messages app, and on that phone this is the only thing
        // standing between her and a mark that comes back every time the launcher starts.
        rememberReadTextMessages(context, readTextMessages(context) + messageId)

        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId)
            )
        } catch (e: Exception) {
            // Not the messages app, or the row has since been deleted. Her screen is already
            // right, and so is the record above; this was the phone's copy of the same fact.
        }
        Unit
    }

    /** The text messages this app has been told she has seen. */
    private fun readTextMessages(context: Context): Set<String> = try {
        context.getSharedPreferences(READ_MESSAGES, Context.MODE_PRIVATE)
            .getStringSet(READ_MESSAGE_IDS, emptySet())
            .orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    private fun rememberReadTextMessages(context: Context, ids: Set<String>) {
        try {
            context.getSharedPreferences(READ_MESSAGES, Context.MODE_PRIVATE)
                .edit()
                // A copy, because the set handed to SharedPreferences must not be mutated
                // afterwards — the stored value and the one in hand are otherwise the same object.
                .putStringSet(READ_MESSAGE_IDS, HashSet(ids))
                .apply()
        } catch (e: Exception) {
            // Nothing to do about it, and nothing worth telling her.
        }
    }

    fun markAsRead(messageId: String) {
        _messages.update { currentList ->
            currentList.map { msg ->
                if (msg.id == messageId) msg.copy(isRead = true) else msg
            }
        }
    }

    /**
     * Adds a reaction to what is already on a message rather than replacing it.
     *
     * This is SMS, and every mark on this path is hers (keyed [Reactions.SELF]).
     * We keep it accumulating via [Reactions.appliedBy] rather than replacing: on SMS each tap
     * really did leave the phone as its own text message, so hiding the earlier one would
     * misreport what she sent.
     */
    fun addReaction(messageId: String, reactionEmoji: String) {
        _messages.update { currentList ->
            currentList.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(reaction = Reactions.appliedBy(msg.reaction, Reactions.SELF, reactionEmoji), isRead = true)
                } else msg
            }
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    /**
     * Removes a single message from this local in-memory store.
     *
     * On this phone only: does not delete from the network and does not touch the system SMS provider.
     */
    fun deleteMessage(messageId: String) {
        if (messageId.isBlank()) return
        _messages.update { currentList ->
            currentList.filterNot { it.id == messageId }
        }
    }

    /**
     * Removes all messages belonging to [conversationId] from this local store.
     *
     * On this phone only. Matches explicit [Message.conversationId] as well as keys derived
     * from contact IDs, phone numbers, Threema IDs, service addresses, and group identities.
     */
    fun deleteConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        _messages.update { currentList ->
            currentList.filterNot { msg -> matchesConversation(msg, conversationId) }
        }
    }

    /**
     * The ids of every message this store files under [conversationId].
     *
     * Read before a deletion, so that the database can be told the same set of messages the
     * screen just lost. Without it the two disagree: this store files a message by the whole of
     * [matchesConversation], while the `messages` table only knows the `conversationId` column it
     * was written with, so a message matched here on a telephone number stayed in the database
     * and came back the next time the app started.
     */
    fun conversationMessageIds(conversationId: String): List<String> {
        if (conversationId.isBlank()) return emptyList()
        return _messages.value.filter { matchesConversation(it, conversationId) }.map { it.id }
    }

    private fun matchesConversation(message: Message, conversationId: String): Boolean {
        if (message.conversationId == conversationId) return true
        return when {
            conversationId.startsWith(ConversationKey.CONTACT_PREFIX) -> {
                val contactId = conversationId.removePrefix(ConversationKey.CONTACT_PREFIX)
                message.contactId == contactId
            }
            conversationId.startsWith(ConversationKey.PHONE_PREFIX) -> {
                val raw = conversationId.removePrefix(ConversationKey.PHONE_PREFIX)
                val digits = raw.filter { it.isDigit() }
                val senderDigits = message.senderAddress?.filter { it.isDigit() }.orEmpty()
                senderDigits.isNotEmpty() && (senderDigits == digits || senderDigits.endsWith(digits) || digits.endsWith(senderDigits))
            }
            conversationId.startsWith(ConversationKey.THREEMA_PREFIX) -> {
                val threemaId = conversationId.removePrefix(ConversationKey.THREEMA_PREFIX)
                message.senderId.equals(threemaId, ignoreCase = true)
            }
            conversationId.startsWith(ConversationKey.SERVICE_PREFIX) -> {
                val service = conversationId.removePrefix(ConversationKey.SERVICE_PREFIX)
                message.senderAddress.equals(service, ignoreCase = true)
            }
            conversationId.startsWith(ConversationKey.GROUP_PREFIX) -> {
                val groupKey = conversationId.removePrefix(ConversationKey.GROUP_PREFIX)
                message.groupIdentity?.let { "${it.creatorIdentity}:${it.groupId}" } == groupKey
            }
            else -> false
        }
    }


    companion object {
        /**
         * How much of her text-message history is read into the app.
         *
         * A cap rather than a preference: this list is held in memory and re-read whenever the
         * launcher starts, so a phone carrying twenty years of texts must not turn every launch
         * into a scan of all of them. Two thousand is several years of an ordinary person's
         * messages and well past the point where the oldest is something anybody would look for.
         *
         * It was fifteen — not fifteen per conversation, fifteen altogether — which one morning of
         * a bank, a pharmacy and a parking app was enough to fill.
         */
        const val SMS_IMPORT_LIMIT = 2000

        /** What an imported text message's id starts with; the rest is the phone's own row id. */
        const val SMS_ID_PREFIX = "sms_"

        /** Where "she has seen this text message" is remembered, when the phone will not hold it. */
        private const val READ_MESSAGES = "gentle_connection_read_messages"
        private const val READ_MESSAGE_IDS = "read_sms_ids"
    }
}

