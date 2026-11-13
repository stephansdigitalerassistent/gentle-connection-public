package ch.heuscher.gentleconnect.data.whatsapp

import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SendState

/**
 * Turns what the WhatsApp bridge hands up into the one message shape her screen knows.
 *
 * The counterpart to [ch.heuscher.gentleconnect.data.OnDeviceMessageMapper], and pure for the
 * same reason: this is where a real message from a real person either becomes visible or
 * silently does not.
 *
 * Unlike the Threema mapper, this one resolves the name here rather than a layer up. Threema
 * hands over an identity that only a directory lookup can turn into a person; WhatsApp hands
 * over a telephone number, and a telephone number is something her address book already knows
 * how to answer. Doing it here means the message is filed under the right person from the first
 * moment it exists, instead of appearing as a number and being corrected later.
 */
object WhatsAppMessageMapper {

    /** Keeps ids distinct from Threema, bridge and SMS ids, which are numbered independently. */
    const val ID_PREFIX = "whatsapp_"

    /**
     * The creator half of a WhatsApp group's identity.
     *
     * [GroupIdentity] was built for Threema, where a group is a creator plus an id. WhatsApp
     * groups have no separate creator in what the bridge reports — the chat JID is the whole
     * identity — so this constant fills the slot.
     *
     * It has to be something that cannot be mistaken for a Threema identity, which is exactly
     * eight characters of A–Z and 0–9. The first version of this was the word "whatsapp", which
     * is exactly eight characters of A–Z; a test caught it. Fourteen characters with a hyphen
     * cannot be confused with one.
     */
    const val GROUP_CREATOR = "whatsapp-group"

    /**
     * One message, or nothing.
     *
     * Nothing in two cases, each deliberate:
     *
     *  - **No id, or no words.** An id is what stops a redelivery becoming a second copy on her
     *    screen; without text there is nothing to show but an empty card.
     *  - **A sender that is not a person.** Status broadcasts and system JIDs are not somebody
     *    writing to her.
     *
     * Her own messages are **not** one of those cases, and used to be. WhatsApp echoes back
     * everything she sends from the native app or another companion, and dropping the echo meant
     * that a message written in the native WhatsApp app on this very phone existed nowhere on her
     * screen — the conversation showed everyone else's words with no evidence that she ever
     * answered. What must not be shown twice is an echo of a message *this app* sent, which it
     * already has its own record of; that one is recognised by its id and dropped a layer up, in
     * `ArrivalPipeline.isOwnEcho`, because it takes the database to know. Nothing here can tell the
     * two apart.
     *
     * (`docs/bridge-contract.md` still says the device ignores `direction: "out"`. That is about
     * the Firestore documents a server-side bridge writes, where the app's own sends and the
     * bridge's echoes are two separate writers with no shared id — see `BridgeMessageRepository`.)
     */
    fun fromBridge(
        incoming: WhatsAppEvent.Incoming,
        contacts: List<Contact>,
        receivedAt: Long = 0L
    ): Message? {
        if (incoming.messageId.isBlank()) return null
        if (incoming.text.isBlank()) return null

        // For a message of hers the "sender" is who she wrote to — see recordOutgoing, which
        // shapes an outgoing message the same way: the counterpart's name and address, not hers.
        val group = groupIdentityOf(incoming)
        val number = WhatsAppNumber.numberFromJid(incoming.senderJid)
        if (number == null && group == null) return null

        val contact = contactFor(incoming.senderJid, contacts)
        // Her address book first, then the name they chose for themselves, then the number. The
        // order matters: "Mama" is what her daughter calls herself on WhatsApp, and it is not
        // what her daughter is called on her mother's phone.
        //
        // The push name on a message of hers is *hers*, though, and this name is the counterpart's
        // — so it is skipped there. Without that, everything she wrote in the native app would be
        // filed under her own name instead of under the person she was writing to.
        val chosenName = if (incoming.outgoing) null else incoming.pushName.trim().ifBlank { null }
        val name = contact?.name
            ?: chosenName
            ?: number
            ?: incoming.chatJid

        val quotedId = incoming.quotedId?.takeIf { it.isNotBlank() }?.let { ID_PREFIX + it }
        val quotedSenderName = incoming.quotedSender?.takeIf { it.isNotBlank() }?.let { jid ->
            contactFor(jid, contacts)?.name
                ?: WhatsAppNumber.numberFromJid(jid)
                ?: jid
        }

        return Message(
            id = ID_PREFIX + incoming.messageId,
            // The name WhatsApp knows it by, carried alongside the app's own. It is what a
            // reaction to this message names, and what tells an echo of a message this app sent
            // apart from one she wrote in the native app — see ArrivalPipeline.isOwnEcho.
            wireMessageId = incoming.messageId,
            senderName = name,
            senderId = number ?: incoming.chatJid,
            // The E.164 form, so that everything downstream which files messages by telephone
            // number — conversation ids, contact links, the merge with SMS — sees a WhatsApp
            // message as being from the same person as everything else from that number.
            senderAddress = number?.let { "+$it" },
            contactId = contact?.id,
            text = incoming.text,
            timestamp = incoming.timestamp,
            // Backfilled history is shown, never announced: it is how a phone that was linked
            // this morning has anything on it at all, and none of it is news. Anything of hers
            // counts as read for the reason it does everywhere else — she wrote it — which is
            // also what keeps a message she just sent from the native app out of the unread
            // count and off the nudge at the top of her home screen.
            isRead = incoming.historical || incoming.outgoing,
            direction = if (incoming.outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            // It left the phone: this message exists because WhatsApp delivered it.
            sendState = if (incoming.outgoing) SendState.SENT else null,
            sourcePackage = "",
            transport = BridgeTransport.WHATSAPP,
            channel = MessageChannel.ON_DEVICE,
            // Carried for the same reason as Threema's: without it, an answer to a group of
            // eight goes privately to whichever one of them happened to write. Nothing yet knows
            // who is in a WhatsApp group, so a group message is shown and cannot be answered —
            // which is the honest state, and the same one a Threema group with unknown
            // membership is already in.
            groupIdentity = group,
            quotedId = quotedId,
            quotedSenderName = quotedSenderName,
            quotedText = incoming.quotedText,
            receivedAt = if (incoming.historical) 0L else receivedAt
        )
    }

    /**
     * A call nobody could answer, as the one thing on her screen that says so.
     *
     * A WhatsApp call rings in the WhatsApp app, where she cannot answer it, and arrives here as
     * signalling the bridge cannot carry — see [ch.heuscher.gentleconnect.model.CallCourtesy].
     * All that is left to do with it is put it where she looks: a missed call, filed in the
     * conversation with whoever rang, which her home screen turns into a card with a green button
     * that rings them back on the telephone.
     *
     * Nothing when the caller cannot be turned into a telephone number. WhatsApp addresses some
     * people by a hidden id with no number in it, and a missed call from one of those is a card
     * naming nobody, whose button rings nobody — worse than silence, because it is something she
     * would press.
     *
     * [text] is passed in rather than written here because this object is pure and the sentence
     * is hers, which means it lives in `strings.xml`.
     */
    fun missedCall(
        call: WhatsAppEvent.Call,
        contacts: List<Contact>,
        text: String,
        receivedAt: Long = 0L
    ): Message? {
        if (call.callId.isBlank()) return null
        val number = WhatsAppNumber.numberFromJid(call.callerJid) ?: return null
        val contact = contactFor(call.callerJid, contacts)

        return Message(
            // Named after the call rather than a message, so that the ringing and the ending of
            // one call cannot become two missed calls on her screen.
            id = ID_PREFIX + "call_" + call.callId,
            senderName = contact?.name ?: number,
            senderId = number,
            senderAddress = "+$number",
            contactId = contact?.id,
            text = text,
            timestamp = call.timestamp,
            isMissedCall = true,
            sourcePackage = "",
            transport = BridgeTransport.WHATSAPP,
            channel = MessageChannel.ON_DEVICE,
            receivedAt = receivedAt
        )
    }

    /**
     * The person in her address book this JID belongs to, if any.
     *
     * Matched on the end of the number rather than the whole of it — see
     * [WhatsAppNumber.sameSubscriber]. Her address book holds `079 123 45 67` and WhatsApp says
     * `41791234567`, and neither can be turned into the other without knowing which country she
     * was in when she typed it.
     */
    fun contactFor(jid: String?, contacts: List<Contact>): Contact? {
        val number = WhatsAppNumber.numberFromJid(jid) ?: return null
        return contacts.firstOrNull { WhatsAppNumber.sameSubscriber(it.phoneNumber, number) }
    }

    /**
     * The id WhatsApp knows this message by, with the prefix this app added taken back off.
     *
     * Needed for a real reaction, which is addressed by the original message's key. A key built
     * from the prefixed id names a message that does not exist, so WhatsApp accepts the reaction
     * and it lands nowhere — the same silent failure as a malformed recipient.
     *
     * Null for a message that did not come from here, which the caller must treat as "no real
     * reaction is possible" rather than reacting to something arbitrary.
     */
    fun bridgeMessageId(message: Message): String? {
        if (!message.id.startsWith(ID_PREFIX)) return null
        return message.id.removePrefix(ID_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun groupIdentityOf(incoming: WhatsAppEvent.Incoming): GroupIdentity? {
        if (!incoming.isGroup && !WhatsAppNumber.isGroup(incoming.chatJid)) return null
        return GroupIdentity.of(GROUP_CREATOR, incoming.chatJid)
    }

    /**
     * Where a reply to this message has to go.
     *
     * Not the sender: in a group it is the group, and sending to the person who happened to
     * write turns a reply to eight people into a private message to one of them. Null when there
     * is nowhere safe to send, which the caller must report as "it did not go" rather than
     * guessing.
     */
    fun replyTarget(message: Message): String? {
        val group = message.groupIdentity
        if (group != null) {
            // The group id here is the chat JID, lower-cased by GroupIdentity.of. WhatsApp JIDs
            // are case-insensitive in their domain and numeric in their user part, so this
            // round-trips — but only for groups, which is why it is not done for people.
            return group.groupId.takeIf { WhatsAppNumber.isGroup(it) }
        }
        return WhatsAppNumber.jidForNumber(message.senderId ?: message.senderAddress)
    }

    /**
     * The JID of the sender of a message being reacted to.
     *
     * In a 1:1 chat, WhatsApp keys a reaction by the remote chat JID and the message ID alone,
     * so this returns null. In a group, the message key must also name whose message it was;
     * without that participant JID the bridge refuses to send a reaction that would attach to
     * nothing.
     *
     * Returns null when no real reaction is possible (a 1:1 message, or a group message whose
     * author could not be resolved to a personal JID). The caller must treat null as a signal to
     * fall back to sending the emoji as a message.
     */
    fun reactionParticipant(message: Message): String? {
        if (message.groupIdentity == null) return null
        val raw = message.senderId ?: message.senderAddress
        if (raw.isNullOrBlank() || WhatsAppNumber.isGroup(raw)) return null
        val jid = WhatsAppNumber.jidForNumber(raw)
        if (jid.isNullOrBlank() || WhatsAppNumber.isGroup(jid)) return null
        return jid
    }
}
