package ch.heuscher.gentleconnect.data

import android.content.Context
import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.data.threema.ThreemaFileMessage
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppBridgeProtocol
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppClient
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppLinkState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppMessageMapper
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.Attachment
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Partner
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.SendState
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.model.ThreemaReceipt
import ch.heuscher.gentleconnect.model.ThreemaWireId
import ch.heuscher.gentleconnect.reply.MessageReplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything she sends, and the read marks and receipts that go with opening a message.
 *
 * Moved out of the home screen's view model, where a send ran in whatever scope the screen asked
 * from: a message still on its way when she pressed the home button, or when the activity was
 * rebuilt for a new font size, was cancelled halfway — after its row had been written as pending
 * and before anybody had been asked to carry it. Every send now runs in [scope], which outlives
 * any screen, and the caller only waits for the answer. A caller that goes away stops waiting; the
 * message still goes and its row still gets the true state.
 *
 * One per process, from [AppGraph.outbox]. A view model built without one — in tests — builds its
 * own over whatever it has, which may be nothing: then nothing is recorded and every send is
 * reported as not sent, which is what the app did before it had any of this.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val messageStore: LocalMessageStore,
    private val catalog: MessageCatalog,
    private val dependencies: Dependencies
) {

    /**
     * What sending reaches for, asked at the moment of sending. Functions rather than values
     * because a view model without a context only learns these later; the process's own outbox
     * answers from [AppGraph] and [Messengers] every time.
     */
    class Dependencies(
        val conversations: () -> ConversationRepository? = { null },
        val bridgeMessages: () -> BridgeMessageRepository? = { null },
        val threema: () -> ThreemaClient? = { null },
        val whatsApp: () -> WhatsAppClient? = { null },
        val replier: () -> MessageReplier? = { null },
        val context: () -> Context? = { null },
        /** Writes a fault down for the caregiver. Never waited for, never allowed to fail a send. */
        val recordFault: (CaregiverEvent) -> Unit = {}
    )

    private val conversationRepository: ConversationRepository? get() = dependencies.conversations()
    private val bridgeMessageRepository: BridgeMessageRepository? get() = dependencies.bridgeMessages()
    private val threemaClient: ThreemaClient? get() = dependencies.threema()
    private val whatsAppClient: WhatsAppClient? get() = dependencies.whatsApp()
    private val messageReplier: MessageReplier? get() = dependencies.replier()
    private val phone: Context? get() = dependencies.context()

    private val conversationLinks get() = catalog.conversationLinks
    private val onDeviceMessages get() = catalog.onDeviceMessages
    private val messages get() = catalog.messages

    /** Keeps two messages sent in the same millisecond from being one row that overwrote another. */
    private val outgoingSequence = AtomicLong(0L)

    private fun conversationIdOf(message: Message, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(message, links)

    private fun conversationIdOf(recipient: Recipient, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(recipient, links)

    private fun replyDecisionFor(group: GroupIdentity): GroupReplyPolicy.Decision =
        catalog.replyDecisionFor(group)

    /**
     * Runs a send in [scope] and waits for its answer. If the caller is cancelled — the screen
     * went away — only the waiting stops; the send finishes and records its own outcome.
     */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    suspend fun resend(message: Message): Boolean = detached { resendNow(message) }
    suspend fun sendReaction(message: Message, emoji: String): Boolean = detached { sendReactionNow(message, emoji) }
    suspend fun sendTextReply(message: Message, text: String): Boolean = detached { sendTextReplyNow(message, text) }
    suspend fun sendNewMessage(recipient: Recipient, text: String): Boolean = detached { sendNewMessageNow(recipient, text) }
    suspend fun forwardMedia(message: Message, recipient: Recipient): Boolean = detached { forwardMediaNow(message, recipient) }
    suspend fun sendPhoto(recipient: Recipient, path: String): Boolean = detached { sendPhotoNow(recipient, path) }

    /**
     * Answers several people at once, or refuses to.
     *
     * The refusal is the important half. Every one-to-one reply route targets `senderId`, so a
     * group message answered that way would go privately to whoever happened to write while the
     * rest of the group saw nothing — and she could not tell. [GroupReplyPolicy] decides, and it
     * refuses whenever the membership is anything less than certain, because a fan-out to a
     * membership we guessed at reaches some of the family and looks exactly like reaching all of
     * them.
     *
     * Marked read either way: she has seen it. Reported as not sent when nothing was.
     */
    private suspend fun sendToGroup(message: Message, text: String): Boolean {
        val group = message.groupIdentity ?: return false
        markMessageAsRead(message)
        return sendToGroup(
            group = group,
            groupName = message.displayName,
            conversationId = conversationIdOf(message, conversationLinks.value),
            text = text,
            transport = message.transport,
            channel = message.channel
        )
    }

    /**
     * The half of answering a group that has nothing to do with the message being answered.
     *
     * Shared with writing to a group first, from the picker. Both need the same permission from
     * [GroupReplyPolicy], the same fan-out and the same refusal reported to the same place, and
     * the one thing that must never happen is the two paths disagreeing about who is in the group.
     */
    private suspend fun sendToGroup(
        group: GroupIdentity,
        groupName: String,
        conversationId: String?,
        text: String,
        transport: BridgeTransport,
        channel: MessageChannel
    ): Boolean {
        // WhatsApp first, because for WhatsApp none of what follows applies. A WhatsApp group has
        // a real address: one message reaches everybody, including whoever joined this morning.
        // There is no membership to look up, no fan-out, and no "reached four of six" to report —
        // all of which exist below because a Threema group is eight separate encrypted messages.
        val whatsAppGroupJid = WhatsAppGroups.jidOf(group)
        if (transport == BridgeTransport.WHATSAPP && whatsAppGroupJid != null) {
            return recordOutgoing(
                conversationId = conversationId,
                counterpartId = whatsAppGroupJid,
                counterpartName = groupName,
                text = text,
                transport = transport,
                channel = channel,
                groupIdentity = group
            ) { wireId -> sendOverWhatsApp(whatsAppGroupJid, text, wireId) }
        }

        val recipients = groupRecipientsOrRefuse(group, groupName) ?: return false
        return recordOutgoing(
            conversationId = conversationId,
            counterpartId = null,
            counterpartName = groupName,
            text = text,
            transport = transport,
            channel = channel,
            groupIdentity = group
        ) { wireId ->
            fanOutToGroup(group, recipients, text, groupName, wireId)
        }
    }

    /**
     * Who a Threema group message goes to, or null — with the reason logged — when
     * [GroupReplyPolicy] says it may not go at all. Words and photographs ask the same question.
     */
    private suspend fun groupRecipientsOrRefuse(group: GroupIdentity, groupName: String): List<String>? {
        val decision = replyDecisionFor(group)
        if (decision is GroupReplyPolicy.Decision.Refused) {
            // The reason goes to the caregiver, never to her: she is told nothing, and the reply
            // field is simply absent. The group is named the way she sees it named — never by its
            // identity, and never with who is in it.
            recordCaregiverEvent(
                kind = when (decision.reason) {
                    GroupReplyPolicy.Reason.MEMBERSHIP_UNKNOWN ->
                        CaregiverEventKind.GROUP_MEMBERSHIP_UNKNOWN
                    GroupReplyPolicy.Reason.NO_LONGER_A_MEMBER ->
                        CaregiverEventKind.GROUP_NO_LONGER_MEMBER
                    GroupReplyPolicy.Reason.NOBODY_LEFT_TO_REACH ->
                        CaregiverEventKind.GROUP_NOBODY_LEFT
                },
                subject = groupName
            )
            return null
        }
        return (decision as GroupReplyPolicy.Decision.SendTo).recipients
    }

    /**
     * Sending words to a group, without recording anything.
     *
     * The half a resend needs: the message is already in the conversation and already has an id,
     * so it must not be written again. It asks the same [GroupReplyPolicy] the first attempt did
     * — who is in the group can have changed since, and a resend that skipped the question could
     * write to somebody who has left.
     */
    private suspend fun sendExistingToGroup(
        group: GroupIdentity,
        groupName: String,
        text: String,
        wireMessageId: String? = null
    ): Boolean {
        val whatsAppGroupJid = WhatsAppGroups.jidOf(group)
        if (whatsAppGroupJid != null) {
            return sendOverWhatsApp(whatsAppGroupJid, text, wireMessageId)
        }
        val recipients = groupRecipientsOrRefuse(group, groupName) ?: return false
        return fanOutToGroup(group, recipients, text, groupName, wireMessageId)
    }

    /**
     * Sends one of her messages again, after she has held it.
     *
     * The message keeps its place and its id: this is the same message having another go, not a
     * second copy of it appearing underneath the first. It goes back to `PENDING` while the
     * transport is asked, so the dashes disappear the moment she lets go and come back only if it
     * fails again — which is the honest picture of what is happening, and the only feedback the
     * gesture gets.
     *
     * Guarded by [SendRetryPolicy.mayResend] here as well as in the screen. The screen decides
     * whether to offer the gesture; this decides whether to act on it, and the two must not be
     * the same decision made once.
     */
    private suspend fun resendNow(message: Message): Boolean {
        if (!SendRetryPolicy.mayResend(
                isOutgoing = message.isOutgoing,
                state = message.sendState,
                sentAt = message.timestamp,
                now = System.currentTimeMillis()
            )
        ) {
            return false
        }

        val repository = conversationRepository
        repository?.updateSendState(message.id, SendState.PENDING)

        // The id it went out under the first time, so the second attempt is the same message
        // rather than a new one wearing its text — the row keeps its id here for the same reason
        // it keeps its place. A row written before this column existed has none, and a fresh one
        // is the only honest answer: better a message whose reactions cannot be matched than no
        // second attempt at all.
        val wireMessageId = message.wireMessageId ?: ThreemaWireId.random()

        val accepted = sendAndReport(message.senderName) {
            deliverAgain(message, wireMessageId)
        }

        repository?.updateSendState(message.id, SendStatePolicy.afterHandoff(accepted))
        return accepted
    }

    /**
     * Sends the text of a message of hers again, the way that message went out the first time.
     *
     * The same fork [sendTextReply] makes, without the half that writes a row: this is one
     * message being attempted twice, not two messages. Nothing here marks anything read or
     * records anything — the row already exists, and only its state changes.
     *
     * Every channel, which is the point. The first version of this could only reach Threema, so
     * the gesture had to be hidden everywhere else, and a text message that failed — the case
     * most worth a second attempt — was the one she could do nothing about.
     */
    private suspend fun deliverAgain(message: Message, wireMessageId: String): Boolean {
        val photoPath = message.photoPath
        if (photoPath != null) {
            val group = message.groupIdentity
            if (group != null) {
                val whatsAppGroupJid = WhatsAppGroups.jidOf(group)
                if (whatsAppGroupJid != null) {
                    return dispatchToWhatsApp(whatsAppGroupJid, MEDIA_SEND_TIMEOUT_MS) { client, ref ->
                        client.sendMedia(
                            toJid = whatsAppGroupJid,
                            path = photoPath,
                            mime = ThreemaFileMessage.mimeOf(photoPath),
                            messageId = wireMessageId,
                            ref = ref
                        )
                    }
                }
                val recipients = groupRecipientsOrRefuse(group, message.senderName) ?: return false
                if (!awaitTransportReady()) return false
                val fileHex = threemaClient?.prepareFileMessage(photoPath) ?: return false
                return fanOutToGroup(group, recipients, Attachment.PHOTO.token, message.senderName, wireMessageId, fileHex)
            }

            return when (message.transport) {
                BridgeTransport.WHATSAPP -> {
                    val target = WhatsAppMessageMapper.replyTarget(message)
                        ?: message.senderId
                        ?: message.senderAddress
                    if (target == null) false
                    else dispatchToWhatsApp(target, MEDIA_SEND_TIMEOUT_MS) { client, ref ->
                        client.sendMedia(
                            toJid = target,
                            path = photoPath,
                            mime = ThreemaFileMessage.mimeOf(photoPath),
                            messageId = wireMessageId,
                            ref = ref
                        )
                    }
                }
                else -> {
                    val target = message.senderId?.trim()?.ifEmpty { null }
                        ?: message.senderAddress?.trim()?.ifEmpty { null }
                    if (target == null) false
                    else {
                        if (!awaitTransportReady()) return false
                        threemaClient?.sendFile(
                            recipient = target,
                            path = photoPath,
                            thumbnailPath = null
                        ) ?: false
                    }
                }
            }
        }

        val group = message.groupIdentity
        if (group != null) return sendExistingToGroup(group, message.senderName, message.text, wireMessageId)

        return when (message.channel) {
            // Threema or WhatsApp, decided by the transport rather than assumed — sending a
            // WhatsApp message over Threema is what assuming it got us.
            // A message the old server bridge filed is still from somebody on Threema or WhatsApp,
            // and the clients on this phone are her own accounts there — see [sendTextReply].
            MessageChannel.ON_DEVICE,
            MessageChannel.BRIDGE -> sendOnDevice(message, message.text, wireMessageId)
            // Straight to the number this went to. Not `replyWithText`, which answers a message
            // that arrived: there is no such message here, only her own and the address on it.
            MessageChannel.DEVICE -> {
                val target = message.senderAddress.orEmpty()
                if (!MessageRouter.SMS_ENABLED || target.isBlank()) false
                else messageReplier?.sendDirectText(target, message.text) ?: false
            }
        }
    }

    /**
     * Sends one message to every member, and waits to hear how each copy went.
     *
     * **All of them or none.** A fan-out that reached three of five is reported here as failure,
     * so her message stays visibly unfinished rather than sitting in the conversation looking
     * exactly like one that arrived. "Sent to 3 of 5" would be a fact she can do nothing with —
     * she cannot tell which three, cannot resend to the other two, and cannot ask.
     *
     * Timing out counts as failure for the same reason. The bridge may have to ask the directory
     * server about a member it has never seen, so this is generous; past that, saying nothing is
     * saying something false.
     */
    private suspend fun fanOutToGroup(
        group: GroupIdentity,
        recipients: List<String>,
        text: String,
        /** How the group is named on her screen, for the caregiver's log. Never its identity. */
        groupName: String,
        wireMessageId: String? = null,
        /** A photograph in place of [text]: the file message from [ThreemaClient.prepareFileMessage]. */
        fileHex: String? = null
    ): Boolean {
        val client = threemaClient ?: return false
        // Before the thirty-second wait for an answer, the wait for somebody to ask. Both of the
        // failures that prompted this were sends into a bridge that was still connecting.
        if (!awaitTransportReady()) return false
        return withTimeoutOrNull(GROUP_SEND_TIMEOUT_MS) {
            coroutineScope {
                // Subscribed before the request goes out, or a fast bridge answers into nothing
                // and a message that went to everybody is recorded as having failed.
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    client.groupSendResults.first {
                        it.creatorIdentity.equals(group.creatorIdentity, ignoreCase = true) &&
                            it.groupId.equals(group.groupId, ignoreCase = true) &&
                            (wireMessageId == null || it.messageId == null || it.messageId.equals(wireMessageId, ignoreCase = true))
                    }
                }
                val asked = if (fileHex != null) {
                    client.sendGroupFileMessage(
                        creatorIdentity = group.creatorIdentity,
                        groupId = group.groupId,
                        members = recipients,
                        fileHex = fileHex,
                        messageId = wireMessageId
                    )
                } else {
                    client.sendGroupTextMessage(
                        creatorIdentity = group.creatorIdentity,
                        groupId = group.groupId,
                        members = recipients,
                        text = text,
                        messageId = wireMessageId
                    )
                }
                if (!asked) {
                    outcome.cancel()
                    return@coroutineScope false
                }
                val result = outcome.await()
                if (result.failed.isNotEmpty()) {
                    // The one report worth more than "not sent": it says the message did reach
                    // part of the family, which changes what the caregiver should do about it.
                    recordCaregiverEvent(
                        kind = CaregiverEventKind.GROUP_MESSAGE_PART_SENT,
                        subject = groupName,
                        count = result.delivered.size,
                        total = recipients.size
                    )
                }
                GroupSendOutcome.succeeded(recipients, result.delivered)
            }
        } ?: false
    }

    /**
     * Answers a message that arrived on this device, over whichever messenger brought it.
     *
     * A reply goes back the way the message came — that is [MessageRouter]'s rule and it is not
     * this method's to revisit. All this does is pick which of the two on-device clients is the
     * way it came, which used to be a question with only one answer.
     */
    private suspend fun sendOnDevice(message: Message, text: String, wireMessageId: String): Boolean =
        when (message.transport) {
            BridgeTransport.WHATSAPP -> {
                // Never message.senderId in a group: the reply belongs to the group, and sending
                // it to whoever happened to write turns an answer to eight people into a private
                // message to one of them. See WhatsAppMessageMapper.replyTarget.
                val target = WhatsAppMessageMapper.replyTarget(message)
                if (target == null) false else sendOverWhatsApp(target, text, wireMessageId)
            }
            else -> sendOverThreema(message, text, wireMessageId)
        }

    /**
     * Sends over the embedded WhatsApp bridge, and waits for the bridge to say what happened.
     *
     * Unlike Threema's, this send is not answered by the call itself: the bridge acknowledges on
     * its event stream. Waiting for that acknowledgement is what makes "sent" mean sent — without
     * it, this would return true for having written a line into a pipe, which is the same class
     * of lie as writing into a queue nobody drains.
     *
     * A timeout counts as not sent. That is the safe direction: a message she is told did not go,
     * which did, costs her a duplicate; the other way costs her a message nobody ever receives
     * and no way to find out.
     */
    private suspend fun sendOverWhatsApp(
        toJid: String,
        text: String,
        wireMessageId: String? = null
    ): Boolean {
        if (text.isBlank()) return false
        return dispatchToWhatsApp(toJid) { client, ref -> client.sendText(toJid, text, wireMessageId, ref) }
    }

    /**
     * Reacts to a WhatsApp message with a real reaction rather than an emoji as a message.
     *
     * WhatsApp multi-device has reactions, and a reaction is what she means when she taps a
     * heart — see `docs/bridge-contract.md`. Threema's path sends the emoji as its own message
     * because the client there has no reaction to send; this one does, so the person receiving
     * it sees a heart on their message instead of a message containing a heart.
     *
     * Falls back to sending it as a message when the original cannot be addressed, because a
     * heart that arrives oddly is worth more to her than one that does not arrive.
     */
    private suspend fun sendWhatsAppReaction(message: Message, emoji: String): Boolean {
        val target = WhatsAppMessageMapper.replyTarget(message) ?: return false
        val originalId = WhatsAppMessageMapper.bridgeMessageId(message)
            ?: return sendOverWhatsApp(target, emoji)
        val participant = WhatsAppMessageMapper.reactionParticipant(message)
        if (message.groupIdentity != null && participant == null) {
            return sendOverWhatsApp(target, emoji)
        }

        return dispatchToWhatsApp(target) { client, ref ->
            client.sendReaction(
                toJid = target,
                emoji = emoji,
                messageId = originalId,
                // Reacting to something somebody sent her, never to something of her own: the
                // key would name a message that does not exist and the reaction would vanish.
                fromMe = false,
                participant = participant,
                ref = ref
            )
        }
    }

    /**
     * Hands one thing to the bridge and waits for it to say what happened.
     *
     * The waiting is the point. This send is not answered by the call itself — the bridge
     * acknowledges on its event stream — so without this, "sent" would mean "written into a
     * pipe", which is the same class of lie as writing into a queue nobody drains.
     *
     * A timeout counts as not sent. That is the safe direction: a message she is told did not
     * go, which did, costs her a duplicate; the other way costs her a message nobody receives
     * and no way to find out.
     */
    private suspend fun dispatchToWhatsApp(
        toJid: String,
        /**
         * How long the answer is waited for. The default is a message, which is instant; an
         * attachment has to be encrypted and uploaded first, which on a mobile connection is
         * minutes for a film — see [MEDIA_SEND_TIMEOUT_MS].
         */
        timeoutMs: Long = SendRetryPolicy.WAIT_FOR_TRANSPORT_MS,
        /** Hands the request to the bridge, carrying [ref] so the answer can be told apart. */
        send: (client: WhatsAppClient, ref: String) -> Boolean
    ): Boolean {
        val client = whatsAppClient ?: return false
        if (!awaitWhatsAppReady(client)) return false

        // This request's own name. Waiting for "the next answer about this person" took the
        // answer to a photo or a reaction sent to them a moment earlier for this one.
        val ref = java.util.UUID.randomUUID().toString()
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                // Subscribed before the request goes out, or a fast bridge answers into nothing
                // and a message that arrived is recorded as having failed.
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    client.events.first { WhatsAppBridgeProtocol.answers(it, ref) != null }
                }
                if (!send(client, ref)) {
                    outcome.cancel()
                    return@coroutineScope false
                }
                WhatsAppBridgeProtocol.answers(outcome.await(), ref) == true
            }
        } ?: false
    }

    /**
     * Waits for the WhatsApp session to be live, rather than asking a transport that is not there.
     *
     * Same reasoning as [awaitTransportReady], and the same failure behind it: a send attempted
     * seconds after the app starts hits a bridge that has not finished its handshake, and
     * reporting failure then is correct about this instant and wrong about the next one.
     *
     * Returns false when the phone is not linked at all — no amount of waiting fixes that, and
     * somebody has to be at the phone.
     */
    private suspend fun awaitWhatsAppReady(client: WhatsAppClient): Boolean {
        if (client.linkState.value == WhatsAppLinkState.LIVE) return true
        if (client.linkState.value == WhatsAppLinkState.NOT_LINKED) return false

        client.start()
        return withTimeoutOrNull(SendRetryPolicy.WAIT_FOR_TRANSPORT_MS) {
            client.linkState.first { it == WhatsAppLinkState.LIVE }
            true
        } ?: false
    }

    /**
     * Answers a message that arrived over the embedded Threema client, back the way it came.
     *
     * @return false when it did not leave the phone — the client not being up is the ordinary
     *   reason, and [ThreemaClient.sendTextMessage] reports that honestly rather than queueing.
     */
    private suspend fun sendOverThreema(message: Message, text: String, wireMessageId: String): Boolean {
        val target = message.senderId?.trim()?.ifEmpty { null } ?: return false
        if (text.isBlank()) return false
        if (!awaitTransportReady()) return false
        return threemaClient?.sendTextMessage(target, text, wireMessageId) ?: false
    }

    /**
     * Waits for the messenger to be up, rather than asking a transport that is not there yet.
     *
     * Found on the phone: two messages to her family were recorded as failed within seconds of
     * the app restarting, because the bridge subprocess had not finished its handshake. Asking
     * once and reporting failure is correct about this instant and wrong about the next one —
     * the connection was moments away, and she had no way to ask again.
     *
     * Returns false only when the transport really is not coming, which is when "not sent" is
     * the true answer. See [SendRetryPolicy.WAIT_FOR_TRANSPORT_MS].
     */
    private suspend fun awaitTransportReady(): Boolean {
        val client = threemaClient ?: return false
        if (client.connectionState.value is ThreemaConnectionState.Connected) return true

        // Idempotent: start() returns immediately if the supervisor is already running. This is
        // for the case where nothing has woken the client up yet.
        client.start()

        return withTimeoutOrNull(SendRetryPolicy.WAIT_FOR_TRANSPORT_MS) {
            client.connectionState.first { it is ThreemaConnectionState.Connected }
            true
        } ?: false
    }

    private fun markOnDeviceMessageRead(id: String) {
        val repository = conversationRepository
        if (repository != null) {
            scope.launch { repository.markAsRead(id) }
            return
        }
        onDeviceMessages.value = onDeviceMessages.value.map { existing ->
            if (existing.id == id) existing.copy(isRead = true) else existing
        }
    }

    /**
     * Keeps one of her messages, and keeps it honest about how far it got.
     *
     * The message is stored before the transport is asked, so that it appears in the conversation
     * the moment she presses send rather than only once something succeeds. It is stored as
     * [SendState.PENDING] and updated the instant the transport answers — and what the transport
     * answers is only whether it accepted the message, never whether anybody received it. See
     * [SendStatePolicy].
     *
     * With no store to write to — before the database is attached, and in tests — this is just the
     * send, which is what the whole app did until now.
     */
    private suspend fun recordOutgoing(
        conversationId: String?,
        counterpartId: String?,
        counterpartName: String,
        text: String,
        transport: BridgeTransport,
        channel: MessageChannel,
        /** The several people this went to, so it is stored as theirs and not as one person's. */
        groupIdentity: GroupIdentity? = null,
        /**
         * The attachment that went with it, where one did — the same file that is already on this
         * phone. Her copy points at it too, so a photograph she sent on is a photograph in her
         * conversation with the person she sent it to, rather than the word "[Foto]" and nothing
         * to look at.
         */
        photoPath: String? = null,
        audioPath: String? = null,
        videoPath: String? = null,
        send: suspend (wireMessageId: String) -> Boolean
    ): Boolean {
        // Chosen here, before anything is sent, and stored on the row. It is what a reaction to
        // this message will name — the app used to let the transport invent one and never learn
        // it, so every heart her family put on something she wrote was dropped.
        val wireMessageId = ThreemaWireId.random()

        val repository = conversationRepository
        if (repository == null || conversationId == null) {
            // No conversation to put it in, but a send that can still fail — and a fault nobody
            // hears about is the thing this whole log exists to stop.
            return sendAndReport(counterpartName) { send(wireMessageId) }
        }

        val message = Message(
            id = "out_${System.currentTimeMillis()}_${outgoingSequence.getAndIncrement()}",
            wireMessageId = wireMessageId,
            senderName = counterpartName,
            senderId = counterpartId,
            senderAddress = counterpartId,
            text = text,
            timestamp = System.currentTimeMillis(),
            sourcePackage = "",
            // Nothing to have read: she wrote it.
            isRead = true,
            transport = transport,
            channel = channel,
            direction = MessageDirection.OUTGOING,
            sendState = SendState.PENDING,
            conversationId = conversationId,
            groupIdentity = groupIdentity,
            photoPath = photoPath,
            audioPath = audioPath,
            videoPath = videoPath
        )
        repository.record(message, conversationId)

        // The same id that was just stored on the row, so the message on her screen and the
        // message on the wire are the one thing — which is what lets a reaction to it find it.
        val accepted = sendAndReport(counterpartName) { send(wireMessageId) }
        repository.updateSendState(message.id, SendStatePolicy.afterHandoff(accepted))
        return accepted
    }

    /**
     * Sends, and tells the caregiver if it did not go.
     *
     * Every send in the app comes through here, so this one place covers SMS, the bridge, the
     * embedded messenger and a fan-out alike — and cannot be forgotten by whoever adds the next
     * transport. This is the one exception to "her screen says nothing": she does see the message
     * sitting unfinished. What she does not see is why, and this is where the why goes.
     */
    private suspend fun sendAndReport(counterpartName: String, send: suspend () -> Boolean): Boolean {
        // No wire id here on purpose. This wrapper is about reporting a failure, and it covers
        // transports that have no such id at all (SMS, the outbox). Whoever needs one has already
        // closed over it — see recordOutgoing, which chooses it before anything is sent.
        val accepted = send()
        if (!accepted) {
            recordCaregiverEvent(
                kind = CaregiverEventKind.MESSAGE_NOT_SENT,
                subject = counterpartName
            )
        }
        return accepted
    }

    /** An answer belongs in the same conversation as the message it answers. */
    private suspend fun recordReply(
        message: Message,
        text: String,
        send: suspend (wireMessageId: String) -> Boolean
    ): Boolean = recordOutgoing(
        conversationId = conversationIdOf(message, conversationLinks.value),
        counterpartId = message.senderId ?: message.senderAddress,
        counterpartName = message.senderName,
        text = text,
        transport = message.transport,
        channel = message.channel,
        send = send
    )

    /**
     * Reacts to a 1:1 Threema message with a real reaction, withdrawing any previous reaction first.
     *
     * WhatsApp echoes her own reactions back to this device and that echo is what lights up her
     * highlight. Threema sends her nothing back, so on a successful reaction her own mark has to
     * be applied directly to the repository under [Reactions.SELF].
     *
     * Falls back to sending the emoji as its own message when there is no wire id.
     */
    /**
     * The name Threema knows a message by, which is not always the one on [Message.wireMessageId].
     *
     * That field is set on messages *she* sent, so that a reaction arriving for one can be matched
     * to it. A message somebody sent *her* carries its Threema id in [Message.id] behind
     * [OnDeviceMessageMapper.ID_PREFIX] and nothing else — and those are precisely the messages
     * she reacts to. Reading only the wire id here meant every Threema reaction fell back to
     * sending the emoji as a message, which is the behaviour this was meant to replace.
     *
     * Null when there is no id to name, which the caller must treat as "no real reaction is
     * possible" rather than reacting to something arbitrary.
     */
    private fun threemaReactionTarget(message: Message): String? {
        message.wireMessageId?.takeIf { it.isNotBlank() }?.let { return it }
        if (!message.id.startsWith(OnDeviceMessageMapper.ID_PREFIX)) return null
        return message.id.removePrefix(OnDeviceMessageMapper.ID_PREFIX).takeIf { it.isNotBlank() }
    }

    /**
     * Puts her mark on the message she reacted to.
     *
     * Where the reaction is real, this is the only trace of it on her screen — and it has to be,
     * because a reaction recorded as a message of hers reads back as something she said. On
     * WhatsApp the same mark also arrives as an echo a moment later and sets the identical
     * value; doing it here as well is what stops the mark appearing a second after her tap.
     */
    private suspend fun markOwnReaction(message: Message, emoji: String) {
        val wireId = when (message.transport) {
            BridgeTransport.WHATSAPP -> WhatsAppMessageMapper.bridgeMessageId(message)
            else -> threemaReactionTarget(message)
        } ?: return
        conversationRepository?.setReaction(message.id, wireId, Reactions.SELF, emoji)
    }

    private suspend fun sendThreemaReaction(message: Message, emoji: String): Boolean {
        // The caller has already established there is a message to name — where there is not,
        // the emoji is sent as its own message and recorded as one, which is a different thing
        // and belongs in [sendReaction].
        val wireMessageId = threemaReactionTarget(message) ?: return false
        val target = message.senderId?.trim()?.ifEmpty { null } ?: return false
        if (!awaitTransportReady()) return false
        val client = threemaClient ?: return false

        val previousEmoji = Reactions.ownOf(message.reaction)
        if (previousEmoji != null && previousEmoji != emoji) {
            client.sendReaction(
                recipient = target,
                targetMessageId = wireMessageId,
                emoji = previousEmoji,
                withdraw = true
            )
        }

        val delivered = client.sendReaction(
            recipient = target,
            targetMessageId = wireMessageId,
            emoji = emoji,
            withdraw = false
        )
        if (delivered) {
            // Threema sends her nothing back — no echo of her own reaction the way WhatsApp's
            // companion link gives one — so the mark is put on the message here or it never
            // appears at all. Both names, because the row may be known by either.
            conversationRepository?.setReaction(message.id, wireMessageId, Reactions.SELF, emoji)
            return true
        }
        return false
    }

    /**
     * Reacts to a group Threema message with a real reaction, withdrawing any previous reaction first.
     *
     * At least one member reached counts as sent. Unlike text messages, a partial fan-out is not
     * withheld and does not record a caregiver event: a heart that reached four of six people is
     * a heart that reached four people, and nobody is worse off.
     */
    private suspend fun sendThreemaGroupReaction(
        message: Message,
        group: GroupIdentity,
        emoji: String
    ): Boolean {
        val wireMessageId = threemaReactionTarget(message) ?: return false
        val decision = replyDecisionFor(group)
        if (decision !is GroupReplyPolicy.Decision.SendTo) return false
        val recipients = decision.recipients
        if (recipients.isEmpty()) return false
        if (!awaitTransportReady()) return false
        val client = threemaClient ?: return false

        val previousEmoji = Reactions.ownOf(message.reaction)
        if (previousEmoji != null && previousEmoji != emoji) {
            client.sendGroupReaction(
                creator = group.creatorIdentity,
                groupId = group.groupId,
                members = recipients,
                targetMessageId = wireMessageId,
                emoji = previousEmoji,
                withdraw = true
            )
        }

        val delivered = client.sendGroupReaction(
            creator = group.creatorIdentity,
            groupId = group.groupId,
            members = recipients,
            targetMessageId = wireMessageId,
            emoji = emoji,
            withdraw = false
        )
        if (delivered) {
            // Threema sends her nothing back — no echo of her own reaction the way WhatsApp's
            // companion link gives one — so the mark is put on the message here or it never
            // appears at all. Both names, because the row may be known by either.
            conversationRepository?.setReaction(message.id, wireMessageId, Reactions.SELF, emoji)
            return true
        }
        return false
    }

    /**
     * React to a message with a big emoji (primary keyboard-free reply).
     */
    private suspend fun sendReactionNow(message: Message, emoji: String): Boolean {
        if (message.isGroupMessage) {
            val group = message.groupIdentity
            val whatsAppGroupJid = WhatsAppGroups.jidOf(group)
            if (message.transport == BridgeTransport.WHATSAPP &&
                message.channel == MessageChannel.ON_DEVICE &&
                group != null &&
                whatsAppGroupJid != null
            ) {
                // Whether this can be a real reaction is settled before anything is sent, so
                // that the two outcomes are never confused: a reaction leaves no row, while the
                // fallback really is a message to the group and is recorded as one.
                if (WhatsAppMessageMapper.bridgeMessageId(message) == null ||
                    WhatsAppMessageMapper.reactionParticipant(message) == null
                ) {
                    return sendToGroup(message, emoji)
                }
                markMessageAsRead(message)
                // Nothing is written into the conversation. A reaction is not a message, and
                // recording it as one put a row saying "❤️" under her own name in the group —
                // three taps read back as three things she had said. What she did belongs on the
                // message she did it to, which is where [markOwnReaction] puts it.
                return sendWhatsAppReaction(message, emoji).also { sent ->
                    if (sent) markOwnReaction(message, emoji)
                }
            }
            if (message.transport == BridgeTransport.THREEMA &&
                message.channel == MessageChannel.ON_DEVICE &&
                group != null
            ) {
                // No id to name means no reaction is possible, and the emoji goes to the
                // group as its own message exactly as it always did.
                if (threemaReactionTarget(message) == null) {
                    return sendToGroup(message, emoji)
                }
                markMessageAsRead(message)
                // Not written into the conversation either — see the WhatsApp branch above.
                return sendThreemaGroupReaction(message, group, emoji)
            }
            return sendToGroup(message, emoji)
        }
        return if (message.channel == MessageChannel.ON_DEVICE) {
            markOnDeviceMessageRead(message.id)
            // Whether this can be a real reaction is decided here rather than inside the send,
            // because it decides something she can see: a real reaction is a mark on their
            // message and is never written into the conversation, while the fallback genuinely
            // is a message of hers and has to be recorded like one — with a state, and something
            // to press when it does not go.
            val asReaction = when (message.transport) {
                BridgeTransport.WHATSAPP ->
                    WhatsAppMessageMapper.replyTarget(message) != null &&
                        WhatsAppMessageMapper.bridgeMessageId(message) != null &&
                        (message.groupIdentity == null ||
                            WhatsAppMessageMapper.reactionParticipant(message) != null)
                BridgeTransport.THREEMA -> threemaReactionTarget(message) != null
                else -> false
            }
            if (asReaction) {
                when (message.transport) {
                    BridgeTransport.WHATSAPP -> sendWhatsAppReaction(message, emoji).also { sent ->
                        if (sent) markOwnReaction(message, emoji)
                    }
                    // Threema puts the mark on itself: it echoes nothing back, so
                    // sendThreemaReaction applies it the moment the send is answered.
                    else -> sendThreemaReaction(message, emoji)
                }
            } else {
                recordReply(message, emoji) { wireId ->
                    if (message.transport == BridgeTransport.WHATSAPP) {
                        sendOverWhatsApp(
                            WhatsAppMessageMapper.replyTarget(message) ?: return@recordReply false,
                            emoji,
                            wireId
                        )
                    } else {
                        sendOnDevice(message, emoji, wireId)
                    }
                }
            }
        } else if (message.channel == MessageChannel.BRIDGE) {
            bridgeMessageRepository?.updateReaction(message.id, emoji)
            // Through her own client on this phone, as an emoji message — see [sendTextReply].
            recordReply(message, emoji) { wireId -> sendOnDevice(message, emoji, wireId) }
        } else {
            if (!MessageRouter.SMS_ENABLED) {
                // No way back. Marking it read and reporting failure is the honest pair: she saw
                // it, and nothing was sent.
                messageStore.markAsRead(message.id)
                return false
            }
            messageStore.addReaction(message.id, emoji)
            // Recorded as a message of hers, like every other transport, rather than only sent.
            // A text message carries no reactions, so a 👍 leaves the phone as its own message —
            // and if it is not written down as one, the thing she just did is nowhere on her
            // screen the moment she leaves the conversation.
            recordReply(message, emoji) { _ -> messageReplier?.replyWithEmoji(message, emoji) ?: false }
        }
    }

    /**
     * Send an optional short text reply.
     */
    private suspend fun sendTextReplyNow(message: Message, text: String): Boolean {
        if (message.isGroupMessage) return sendToGroup(message, text)
        return if (message.channel == MessageChannel.ON_DEVICE) {
            markOnDeviceMessageRead(message.id)
            recordReply(message, text) { wireId -> sendOnDevice(message, text, wireId) }
        } else if (message.channel == MessageChannel.BRIDGE) {
            bridgeMessageRepository?.markAsRead(message.id)
            // A message the old server bridge filed in Firestore is answered through her own
            // client on this phone, never through the Firestore outbox. Nothing drains that outbox
            // (see SendDispatch): a write to it was reported as sent online and never finished
            // offline, and either way nobody received the reply.
            recordReply(message, text) { wireId -> sendOnDevice(message, text, wireId) }
        } else {
            messageStore.markAsRead(message.id)
            if (!MessageRouter.SMS_ENABLED) return false
            // Written down before it is sent, like the bridge and the messenger above, so that it
            // appears in the conversation immediately and shows as unfinished if the radio refuses
            // it. (Holding it to send again is Threema's alone — see SendRetryPolicy.) Her copy
            // also reaches the telephone's sent box through SmsSender, and the two are folded back
            // into one where the conversation is built; see [ConversationList.joined].
            recordReply(message, text) { _ -> messageReplier?.replyWithText(message, text) ?: false }
        }
    }

    /**
     * Write to someone first, over whichever route reaches them.
     *
     * Her own Threema and WhatsApp contacts are reached through the clients on this phone; SMS
     * leaves the phone directly. The transport is decided by [MessageRouter] and is never
     * shown to her — she wrote to a person, not to a service.
     *
     * @return false when nothing left the phone, so the caller can say so rather than let her
     *   believe the message is on its way.
     */
    private suspend fun sendNewMessageNow(recipient: Recipient, text: String): Boolean {
        if (text.isBlank()) return false

        // Several people go out the way several people always have — one copy each, all or
        // nothing — rather than through the router, which only knows how to reach one address.
        recipient.groupIdentity?.let { group ->
            return sendToGroup(
                group = group,
                groupName = recipient.name,
                conversationId = conversationIdOf(recipient, conversationLinks.value),
                text = text,
                transport = BridgeTransport.THREEMA,
                channel = MessageChannel.ON_DEVICE
            )
        }

        val decision = MessageRouter.decide(
            threemaId = recipient.threemaId,
            whatsappId = recipient.whatsappId,
            phoneNumber = recipient.phoneNumber
        ) ?: return false

        val conversationId = conversationIdOf(recipient, conversationLinks.value)

        return when (decision.channel) {
            SendChannel.SMS -> recordOutgoing(
                conversationId = conversationId,
                counterpartId = decision.target,
                counterpartName = recipient.name,
                text = text,
                transport = BridgeTransport.UNKNOWN,
                channel = MessageChannel.DEVICE
            ) { _ -> messageReplier?.sendDirectText(decision.target, text) ?: false }
            else -> {
                val transport = MessageRouter.transportFor(decision.channel) ?: return false
                val mechanism = SendDispatch.forTransport(transport)
                recordOutgoing(
                    conversationId = conversationId,
                    counterpartId = decision.target,
                    counterpartName = recipient.name,
                    text = text,
                    transport = transport,
                    channel = when (mechanism) {
                        SendMechanism.ON_DEVICE_THREEMA,
                        SendMechanism.ON_DEVICE_WHATSAPP -> MessageChannel.ON_DEVICE
                        else -> MessageChannel.BRIDGE
                    }
                ) { wireId ->
                    when (mechanism) {
                        SendMechanism.ON_DEVICE_THREEMA ->
                            threemaClient?.sendTextMessage(decision.target, text, wireId) ?: false
                        // The target here is whatever the partner record holds, which for
                        // WhatsApp is a telephone number rather than a JID — so it is turned
                        // into one, and a number too broken to make a JID from sends nothing
                        // rather than sending to a malformed address that WhatsApp accepts and
                        // delivers nowhere.
                        SendMechanism.ON_DEVICE_WHATSAPP -> {
                            val jid = WhatsAppNumber.jidForNumber(decision.target)
                            if (jid == null) false
                            else sendOverWhatsApp(jid, text, wireId)
                        }
                        // Never the outbox: nothing drains it. See SendDispatch.
                        SendMechanism.BRIDGE_OUTBOX,
                        SendMechanism.NONE -> false
                    }
                }
            }
        }
    }

    /**
     * Sends a photograph, film or voice message she was sent on to somebody else.
     *
     * Nothing is copied: the file is already on this phone, and it is that file which goes up.
     * Her own copy of the message points at the same one, so the conversation with the person she
     * sent it to shows the picture rather than the word for it.
     *
     * @return false when it did not go, so that she can be told so — this is the one send in the
     *   app she is asked to confirm first, and a "yes" that quietly does nothing would be worse
     *   here than anywhere else.
     */
    private suspend fun forwardMediaNow(message: Message, recipient: Recipient): Boolean {
        val media = Forwarding.mediaOf(message) ?: return false
        if (!Forwarding.canCarryMedia(recipient)) return false

        // A film goes with the still that stands in for it while it downloads — the same one that
        // is already on her screen. Everything else has no still and needs none.
        val thumbnail = if (media.kind == ForwardableMedia.Kind.VIDEO) {
            message.photoPath?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return dispatchMedia(
            recipient = recipient,
            filePath = media.path,
            kind = media.kind,
            thumbnail = thumbnail,
            photoPath = message.photoPath,
            audioPath = message.audioPath,
            videoPath = message.videoPath
        )
    }

    /**
     * Sends a photo she took with the camera to one person or to a group.
     *
     * Recipients that cannot carry media (e.g. SMS-only contacts) are rejected.
     */
    private suspend fun sendPhotoNow(recipient: Recipient, path: String): Boolean {
        recipient.groupIdentity?.let { group -> return sendPhotoToGroup(group, recipient.name, path) }
        if (!Forwarding.canCarryMedia(recipient)) return false

        return dispatchMedia(
            recipient = recipient,
            filePath = path,
            kind = ForwardableMedia.Kind.PHOTO,
            thumbnail = null,
            photoPath = path,
            audioPath = null,
            videoPath = null
        )
    }

    /**
     * A photograph to several people, the way words to them go: one send to a WhatsApp group's
     * address, or — for a Threema group — the blobs up once and a group file message to every
     * member [GroupReplyPolicy] is certain of, refused outright when it is certain of nobody.
     */
    private suspend fun sendPhotoToGroup(group: GroupIdentity, groupName: String, path: String): Boolean {
        // A status is filed as a group, but sending to its address posts the photograph as her
        // own status, to everybody in her address book. Nothing she presses should ever get here
        // with one; this is the last place that can make sure.
        if (StatusBroadcast.isStatus(group)) return false
        val conversationId = conversationIdOf(
            Recipient(name = groupName, groupIdentity = group),
            conversationLinks.value
        )
        val whatsAppGroupJid = WhatsAppGroups.jidOf(group)
        if (whatsAppGroupJid != null) {
            return recordOutgoing(
                conversationId = conversationId,
                counterpartId = whatsAppGroupJid,
                counterpartName = groupName,
                text = Attachment.PHOTO.token,
                transport = BridgeTransport.WHATSAPP,
                channel = MessageChannel.ON_DEVICE,
                groupIdentity = group,
                photoPath = path
            ) { wireId ->
                dispatchToWhatsApp(whatsAppGroupJid, MEDIA_SEND_TIMEOUT_MS) { client, ref ->
                    client.sendMedia(
                        toJid = whatsAppGroupJid,
                        path = path,
                        mime = ThreemaFileMessage.mimeOf(path),
                        messageId = wireId,
                        ref = ref
                    )
                }
            }
        }

        val recipients = groupRecipientsOrRefuse(group, groupName) ?: return false
        return recordOutgoing(
            conversationId = conversationId,
            counterpartId = null,
            counterpartName = groupName,
            text = Attachment.PHOTO.token,
            transport = BridgeTransport.THREEMA,
            channel = MessageChannel.ON_DEVICE,
            groupIdentity = group,
            photoPath = path
        ) { wireId ->
            // Uploaded once for everybody; nothing goes to anybody if it did not finish.
            if (!awaitTransportReady()) return@recordOutgoing false
            val fileHex = threemaClient?.prepareFileMessage(path)
            fileHex != null &&
                fanOutToGroup(group, recipients, Attachment.PHOTO.token, groupName, wireId, fileHex)
        }
    }

    private suspend fun dispatchMedia(
        recipient: Recipient,
        filePath: String,
        kind: ForwardableMedia.Kind,
        thumbnail: String?,
        photoPath: String?,
        audioPath: String?,
        videoPath: String?
    ): Boolean {
        // Threema where she has a Threema ID for them, WhatsApp otherwise. The same order the
        // router uses for words, and for the same reason: it is the messenger this app carries
        // her conversation with that person on.
        val threemaTarget = recipient.threemaId?.takeIf { it.isNotBlank() }
        val whatsAppTarget = recipient.whatsappId?.takeIf { it.isNotBlank() }
            ?.let { WhatsAppNumber.jidForNumber(it) }

        val transport = when {
            threemaTarget != null && threemaClient != null -> BridgeTransport.THREEMA
            whatsAppTarget != null -> BridgeTransport.WHATSAPP
            else -> return false
        }
        val target = if (transport == BridgeTransport.THREEMA) threemaTarget!! else whatsAppTarget!!

        return recordOutgoing(
            conversationId = conversationIdOf(recipient, conversationLinks.value),
            counterpartId = target,
            counterpartName = recipient.name,
            text = when (kind) {
                ForwardableMedia.Kind.PHOTO -> Attachment.PHOTO.token
                ForwardableMedia.Kind.VIDEO -> Attachment.VIDEO.token
                ForwardableMedia.Kind.VOICE -> Attachment.VOICE.token
            },
            transport = transport,
            channel = MessageChannel.ON_DEVICE,
            photoPath = photoPath,
            audioPath = audioPath,
            videoPath = videoPath
        ) { wireId ->
            when (transport) {
                // No wire id on the Threema side: a file message goes out through the raw path,
                // which lets the bridge name it. Nothing here depends on knowing that name — a
                // reaction to a photograph she forwarded lands on the copy in her conversation.
                BridgeTransport.THREEMA -> threemaClient?.sendFile(
                    recipient = target,
                    path = filePath,
                    thumbnailPath = thumbnail
                ) ?: false
                else -> dispatchToWhatsApp(target, MEDIA_SEND_TIMEOUT_MS) { client, ref ->
                    client.sendMedia(
                        toJid = target,
                        path = filePath,
                        mime = ThreemaFileMessage.mimeOf(filePath),
                        messageId = wireId,
                        ref = ref
                    )
                }
            }
        }
    }

    /**
     * Send a heart reaction directly to a partner from the initiate section.
     *
     * Through [sendNewMessage], because a heart is a message and there is one way messages go.
     * This used to be a bare `outboxRepository.sendReaction` and nothing else — the single send
     * in the app that never asked [SendDispatch] anything. Whatever the partner was reachable
     * on, the heart went to `devices/{deviceId}/outbox`; the write succeeded, `true` came back,
     * and she was told it had gone. Nothing drains that queue — see [SendDispatch], which exists
     * to keep the outbox from being reachable by exactly this accident — so every heart sent
     * from the Menschen section stopped in a collection nobody reads.
     *
     * Going through [sendNewMessage] also writes it down. The old call did not, which left the
     * one message she can send without a keyboard as the one message that vanished off her
     * screen the moment she left the row she sent it from.
     */
    suspend fun sendHeartToPartner(partner: Partner): Boolean {
        // The same merge [writableRecipients] does, for one person: a partner knows the single
        // address it was built from, and the router wants every route there is.
        val recipient = Recipient(
            name = partner.displayName,
            photoUri = partner.photoUrl,
            threemaId = partner.id.takeIf { partner.transport == BridgeTransport.THREEMA },
            whatsappId = partner.id.takeIf { partner.transport == BridgeTransport.WHATSAPP },
            phoneNumber = partner.phoneNumber
        )
        return sendNewMessage(recipient, "❤️")
    }

    fun markMessageAsRead(message: Message) {
        when (message.channel) {
            MessageChannel.BRIDGE -> bridgeMessageRepository?.markAsRead(message.id)
            MessageChannel.ON_DEVICE -> {
                markOnDeviceMessageRead(message.id)
                // Only here, and only from this function, which is called when she opens the
                // conversation and the message is in front of her. A read receipt sent because
                // a background sync touched the row would be a lie told to her daughter, and it
                // is the kind of lie that stops anybody trusting the ticks at all.
                sendThreemaReceipt(message, ThreemaReceipt.READ)
            }
            MessageChannel.DEVICE -> {
                messageStore.markAsRead(message.id)
                // And written down, so that reading it survives the next import. Without this the
                // mark on the Message button came back by itself — see
                // [LocalMessageStore.rememberSmsRead].
                phone?.let { context ->
                    scope.launch { messageStore.rememberSmsRead(context, message.id) }
                }
            }
        }
    }

    /**
     * Sends one delivery receipt, if this is a message a receipt can be sent for.
     *
     * One-to-one Threema only. Groups have their own receipt type that Threema's own clients do
     * not send for delivery, and a message of hers has nobody to acknowledge it to. Everything
     * that is not one of those is dropped silently — a receipt is a courtesy, and a failed one
     * must never become something on her screen.
     */
    private fun sendThreemaReceipt(message: Message, receipt: Int) =
        ThreemaReceipts.send(threemaClient, message, receipt)

    fun markMessageAsReadById(messageId: String) {
        val found = messages.value.find { it.id == messageId }
        if (found != null) {
            markMessageAsRead(found)
        } else {
            messageStore.markAsRead(messageId)
        }
    }

    /**
     * Writes a fault down where somebody who can act on it will see it. The subject is anonymised
     * on the way in: a Threema identity that reached the log would sit in it until it rolled over.
     */
    private fun recordCaregiverEvent(
        kind: CaregiverEventKind,
        subject: String? = null,
        count: Int = 0,
        total: Int = 0
    ) {
        dependencies.recordFault(
            CaregiverEvent(
                kind = kind,
                at = System.currentTimeMillis(),
                subject = CaregiverLog.anonymise(subject),
                count = count,
                total = total
            )
        )
    }

    private companion object {
        /**
         * How long an attachment is given to leave the phone.
         *
         * Far longer than a message, because the message is the easy half: the file has to be
         * encrypted and uploaded first, and on a mobile connection a film is minutes rather than
         * milliseconds. Telling her it failed while it is still working would be a lie she then
         * acts on by sending the whole thing again.
         */
        const val MEDIA_SEND_TIMEOUT_MS = 180_000L

        /**
         * How long a fan-out to several people may take before it is treated as unfinished.
         *
         * Generous, because the bridge may have to ask the directory server about a member it has
         * never encountered before sending to them. Bounded, because a send nobody ever answers
         * for would otherwise leave her message drawn as though it had gone.
         */
        const val GROUP_SEND_TIMEOUT_MS = 30_000L
    }
}
