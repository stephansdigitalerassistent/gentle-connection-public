package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import android.content.Context
import android.util.Log
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.data.threema.ThreemaIdentityRepository
import ch.heuscher.gentleconnect.data.whatsapp.CallState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppBridgeProtocol
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppClient
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppEvent
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppMessageMapper
import ch.heuscher.gentleconnect.model.Arrivals
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.CallCourtesy
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.DisplayNames
import ch.heuscher.gentleconnect.model.PictureFlood
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.ThreemaReceipt
import ch.heuscher.gentleconnect.model.arrivedAt
import ch.heuscher.gentleconnect.service.Attention
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Collects and persists incoming messages, reactions, media, group control and dropped payloads
 * from the messenger clients, and announces fresh arrivals out loud.
 *
 * Runs process-scoped under [ch.heuscher.gentleconnect.service.MessengerService] so that messages
 * arriving while no screen exists are stored and announced immediately.
 */
object ArrivalPipeline {

    private const val TAG = "ArrivalPipeline"
    private const val REACTION_ANNOUNCE_GAP_MS = 2 * 60 * 1000L

    /** How long a ringing WhatsApp call is remembered, and how many before it is worth looking. */
    private const val STALE_CALL_MS = 60 * 60 * 1000L
    private const val STALE_CALL_LIMIT = 16
    private const val DROPPED_PAYLOADS_REPORT_GAP_MS = 2 * 60 * 1000L

    private var scope: CoroutineScope? = null
    private var started = false

    private var conversationRepository: ConversationRepository? = null
    private var threemaGroupRepository: ThreemaGroupRepository? = null
    private var caregiverLogRepository: CaregiverLogRepository? = null
    private var mutedConversationsRepository: MutedConversationsRepository? = null
    private var threemaIdentityRepository: ThreemaIdentityRepository? = null
    private var settingsRepository: SettingsRepository? = null
    private var nudgeMemory: NudgeMemory? = null
    private var attention: Attention? = null
    private var threemaClient: ThreemaClient? = null
    private var whatsAppClient: WhatsAppClient? = null

    /** The newest thing she has already been told about out loud. See [Arrivals]. */
    private var announcedUpTo = 0L

    /**
     * Who the last spoken notice was about, and when it was made — the pair that collapses one
     * person's run of messages into the single arrival it is. See [Arrivals.worthAnnouncingAgain].
     *
     * Deliberately not kept on disk, unlike [announcedUpTo]. This exists to stop a dozen notices in
     * the same minute; after a restart there is nothing to collapse, and a phone that stays silent
     * because of something it remembered from yesterday is the worse failure of the two.
     */
    private var lastAnnouncedSender: String? = null
    private var lastAnnouncedAt = 0L

    /**
     * When a reaction was last announced.
     *
     * Reactions arrive in bursts — three people opening the same photograph within a minute — and
     * a notice for each is a telephone that will not stop. One is enough to bring her to the
     * conversation, where all of them are.
     */
    private var lastReactionAnnouncedAt = 0L

    /**
     * The WhatsApp calls that are ringing, by the id WhatsApp gave them.
     *
     * A call is two events at least — it starts ringing, and it ends — and only the pair of them
     * says whether it was missed. Kept in memory and nowhere else: a call that was ringing when
     * the phone restarted is a call nothing can be said about any more, and the honest thing to
     * do with it is forget it rather than report a missed call that may have been answered.
     *
     * Touched only from the WhatsApp collector, which is one coroutine collecting one flow, so
     * these three need no locking of their own.
     */
    private val ringingCalls = mutableMapOf<String, RingingCall>()

    /** When each caller was last told to use the telephone number. See [CallCourtesy.QUIET_MS]. */
    private val toldCallersAt = mutableMapOf<String, Long>()

    /** One ringing WhatsApp call. */
    private class RingingCall(val startedAt: Long, var answeredElsewhere: Boolean = false)

    /**
     * The sentence sent to somebody who rang on WhatsApp, held so that the copy of it coming back
     * as one of her own messages can be recognised. See [recordIncomingWhatsApp].
     */
    private var callNotice: String = ""

    /** The same sentence on a phone that can also be rung on Threema. See [callNoticeFor]. */
    private var callNoticeThreema: String = ""

    private var visibleMessagesFlow: StateFlow<List<Message>>? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = pipelineScope

        ConversationLinksSource.start(appContext)

        // The same instances the home screen uses — see [AppGraph]. A copy of its own here is how
        // a conversation muted on the screen went on being announced until the next restart.
        val convRepo = AppGraph.conversations(appContext)
        val groupRepo = AppGraph.threemaGroups(appContext)
        val logRepo = AppGraph.caregiverLog(appContext)
        val mutedRepo = AppGraph.muted(appContext)
        val identityRepo = Messengers.threemaIdentity(appContext)
        val setRepo = Messengers.settings(appContext)
        val memory = AppGraph.nudgeMemory(appContext)
        val att = Attention(appContext)
        val threema = Messengers.threema(appContext)
        val whatsapp = Messengers.whatsApp(appContext)

        callNotice = appContext.getString(R.string.whatsapp_call_notice)
        callNoticeThreema = appContext.getString(R.string.whatsapp_call_notice_threema)

        conversationRepository = convRepo
        threemaGroupRepository = groupRepo
        caregiverLogRepository = logRepo
        mutedConversationsRepository = mutedRepo
        threemaIdentityRepository = identityRepo
        settingsRepository = setRepo
        nudgeMemory = memory
        attention = att
        threemaClient = threema
        whatsAppClient = whatsapp

        announcedUpTo = memory.announcedUpTo.value

        // The same list the home card is drawn from — her texts, both messengers and the old
        // bridge, named the way her screen names them and with what a caregiver hid left out — see
        // [MessageCatalog.visibleMessages]. This loop used to build a cruder copy of its own, from
        // the database and the SMS store only, which could disagree with the card about what was
        // worth a word. Texts are in it because this app holds the SMS role: an arriving text is
        // filed in the telephone's own inbox by sms/SmsDeliverReceiver, which then reloads
        // AppStores.messages, a store that belongs to the process.
        val visibleFlow = AppGraph.catalog(appContext).visibleMessages
        visibleMessagesFlow = visibleFlow

        // Threema collectors
        pipelineScope.launch {
            // Nothing collected this flow until now, so every group setup, rename and leave
            // the app has ever received went on the floor. It is the only place membership and
            // group names come from — Threema publishes no roster and offers no lookup.
            threema.unknownMessages.collect { unknown ->
                if (unknown.messageType == ThreemaReceipt.MESSAGE_TYPE && unknown.groupId == null) {
                    ch.heuscher.gentleconnect.data.support.SupportManager
                        .onThreemaReceipt(unknown.sender, unknown.dataHex)
                }
                val control = GroupControlParser.parse(
                    sender = unknown.sender,
                    messageType = unknown.messageType,
                    dataHex = unknown.dataHex
                ) ?: return@collect
                // The kind only. A group id and a member list are her social graph, and
                // logcat is readable by anyone who has ever held the phone.
                Log.i(TAG, "Group control message applied: ${control::class.simpleName}")
                groupRepo.record(control, unknown.timestamp)
            }
        }

        pipelineScope.launch {
            // What somebody did about a message, arriving behind the message itself. Same
            // treatment as the WhatsApp side, and deliberately so: the two messengers must be
            // indistinguishable once something is on her screen.
            threema.reactionMessages.collect { reaction ->
                // A reaction to the help request is somebody taking it, not a reaction for her.
                if (!reaction.withdrawn &&
                    ch.heuscher.gentleconnect.data.support.SupportManager.interceptIncoming(
                        reaction.sender, reaction.emoji, reactionTargetId = reaction.targetMessageId
                    )
                ) return@collect
                // Her own messages are filed under an id the app invented and carry the
                // messenger's id alongside. A reaction only ever names the latter, and the
                // messages her family react to are overwhelmingly hers.
                val id = OnDeviceMessageMapper.ID_PREFIX + reaction.targetMessageId
                val wireId = reaction.targetMessageId
                when {
                    // One slot, overwritten by whatever the person sends next: an older
                    // client's acknowledge and decline are the same thumb turning over, never
                    // two marks on one message. See ThreemaReactionMessage.legacy.
                    reaction.legacy -> convRepo.setReaction(
                        messageId = id,
                        wireMessageId = wireId,
                        sender = reaction.sender,
                        emoji = reaction.emoji.takeIf { !reaction.withdrawn && it.isNotBlank() }
                    )

                    // A modern reaction names the emoji it takes back, and takes back only
                    // that one — the rest of what the person put on the message stays.
                    reaction.withdrawn -> convRepo.withdrawReaction(
                        messageId = id,
                        wireMessageId = wireId,
                        sender = reaction.sender,
                        emoji = reaction.emoji
                    )

                    else -> convRepo.applyReaction(
                        messageId = id,
                        wireMessageId = wireId,
                        sender = reaction.sender,
                        emoji = reaction.emoji
                    )
                }
                if (!reaction.withdrawn && reaction.emoji.isNotBlank()) {
                    announceReaction(id, wireId)
                }
            }
        }

        pipelineScope.launch {
            threema.mediaMessages.collect { media ->
                val id = OnDeviceMessageMapper.ID_PREFIX + media.messageId
                when (media.kind) {
                    WhatsAppBridgeProtocol.KIND_VOICE ->
                        convRepo.attachAudio(id, media.path)

                    WhatsAppBridgeProtocol.KIND_VIDEO ->
                        convRepo.attachVideo(id, media.path)

                    else -> convRepo.attachPhoto(id, media.path)
                }
            }
        }

        pipelineScope.launch {
            threema.incomingMessages.collect { incoming ->
                val now = System.currentTimeMillis()
                // "ok" to the help request is for the family and her status screen, not her
                // messages. Still acknowledged, so the sender sees it arrived.
                if (incoming.groupId == null &&
                    ch.heuscher.gentleconnect.data.support.SupportManager.interceptIncoming(incoming.sender, incoming.text)
                ) {
                    OnDeviceMessageMapper.fromThreema(incoming, receivedAt = now)
                        ?.let { sendThreemaReceipt(it, ThreemaReceipt.RECEIVED) }
                    return@collect
                }
                val message = OnDeviceMessageMapper.fromThreema(incoming, receivedAt = now) ?: return@collect
                // Tell whoever wrote that it arrived. Before this, they saw one grey tick for
                // ever: the message had reached Threema's server and, as far as their
                // telephone could tell, gone nowhere. For somebody who cannot reliably reply,
                // the ticks are what her family reads instead — and they were saying that
                // nothing had got through.
                sendThreemaReceipt(message, ThreemaReceipt.RECEIVED)
                // The primary key is what stops a redelivery becoming a second message.
                val conversationId = ConversationLinksSource.conversationIdOf(message)
                    ?: return@collect
                convRepo.record(message, conversationId)
            }
        }

        pipelineScope.launch {
            // A reconnect after hours offline delivers a flood of messages that can overflow
            // buffers multiple times within seconds. Recording an event for every dropped payload
            // would drown the caregiver log in duplicates of the same incident; one event per
            // run of drops is enough to inform caregivers that messages were lost during that reconnect.
            var lastDropReportedAt = 0L
            threema.droppedPayloads.collect { count ->
                if (count > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastDropReportedAt >= DROPPED_PAYLOADS_REPORT_GAP_MS) {
                        lastDropReportedAt = now
                        logRepo.record(
                            CaregiverEvent(
                                kind = CaregiverEventKind.MESSAGES_LOST_ON_ARRIVAL,
                                at = now
                            )
                        )
                    }
                }
            }
        }

        // WhatsApp collectors
        pipelineScope.launch {
            whatsapp.events.collect { event ->
                when (event) {
                    is WhatsAppEvent.Incoming -> recordIncomingWhatsApp(event)

                    // The photograph belonging to a message that is already there. Matching nothing is
                    // ordinary — the message may have been dropped as unreadable — so nothing is reported
                    // when the update touches no row.
                    // WhatsApp gives a person one reaction slot per message, and changing it arrives as
                    // the new emoji with no withdrawal of the old one. We key by sender so one person's
                    // reaction does not overwrite everybody else's, while her own is highlighted.
                    is WhatsAppEvent.Reaction -> {
                        // A 👍 on the help request is somebody taking it, exactly as on Threema.
                        // Until now WhatsApp reactions never reached the support side at all.
                        if (!event.fromMe && !event.withdrawn) {
                            val number = WhatsAppNumber.numberFromJid(event.senderJid)
                            if (number != null &&
                                ch.heuscher.gentleconnect.data.support.SupportManager.interceptIncoming(
                                    "+$number", event.emoji, reactionTargetId = event.targetMessageId
                                )
                            ) return@collect
                        }
                        val id = WhatsAppMessageMapper.ID_PREFIX + event.targetMessageId
                        val wireId = event.targetMessageId
                        // Hers is one bit, not a name — see [Reactions.SELF]. A reaction that names
                        // nobody is filed under [Reactions.UNKNOWN] rather than under the empty string,
                        // which would quietly make every anonymous reaction the same person's.
                        val sender = when {
                            event.fromMe -> Reactions.SELF
                            event.senderJid.isBlank() -> Reactions.UNKNOWN
                            else -> event.senderJid
                        }
                        if (event.withdrawn) {
                            convRepo.withdrawReaction(
                                messageId = id,
                                wireMessageId = wireId,
                                sender = sender,
                                emoji = null
                            )
                        } else {
                            convRepo.setReaction(
                                messageId = id,
                                wireMessageId = wireId,
                                sender = sender,
                                emoji = event.emoji.takeIf { it.isNotBlank() }
                            )
                            if (sender != Reactions.SELF && event.emoji.isNotBlank()) {
                                announceReaction(id, wireId)
                            }
                        }
                    }

                    is WhatsAppEvent.Call -> whatsAppCall(appContext, whatsapp, event)

                    is WhatsAppEvent.Media -> {
                        val id = WhatsAppMessageMapper.ID_PREFIX + event.messageId
                        when (event.kind) {
                            WhatsAppBridgeProtocol.KIND_VOICE ->
                                convRepo.attachAudio(id, event.path)

                            // Beside the still, never over it: both lines name the same message, and the
                            // picture is what she has been looking at while this was downloading.
                            WhatsAppBridgeProtocol.KIND_VIDEO ->
                                convRepo.attachVideo(id, event.path)

                            else -> convRepo.attachPhoto(id, event.path)
                        }
                    }

                    else -> Unit
                }
            }
        }

        // A film whose event was lost on the way is still on disk beside its still — see
        // [ConversationRepository.attachStrandedFilms]. Racing the collectors above is harmless:
        // attaching the same film twice writes the same path.
        pipelineScope.launch {
            val attached = convRepo.attachStrandedFilms()
            if (attached > 0) Log.i(TAG, "Attached $attached films whose event never arrived")
        }

        // Start announcing arrivals
        startAnnouncingArrivals(pipelineScope, visibleFlow)
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        started = false
        visibleMessagesFlow = null
    }

    /**
     * Watches for somebody writing, and lets the phone say so out loud.
     *
     * Driven off the same list the home screen's card comes from, so the two can never be about
     * different things: [Home.worthANudge] is people only and unread only, which means the phone
     * never lights up for a parking app or for a message she has already read on another device.
     *
     * Whether it actually makes a noise is decided in two other places, both deliberately away
     * from here: [Arrivals] for whether this is news at all and whether the house is asleep, and
     * [Attention] for whether she is on the telephone. This only notices.
     */
    private fun startAnnouncingArrivals(
        pipelineScope: CoroutineScope,
        visibleFlow: StateFlow<List<Message>>
    ) {
        pipelineScope.launch {
            visibleFlow.collect { messages ->
                val newest = Home.worthANudge(messages) ?: return@collect
                val now = System.currentTimeMillis()
                val arrivedAt = newest.arrivedAt()
                if (!Arrivals.worthAnnouncing(arrivedAt, announcedUpTo, now)) {
                    return@collect
                }
                // Marked before the quiet-hours check, not after: something that arrives at
                // midnight has been seen and dealt with by this loop, and must not be announced
                // when the next message finally makes the list re-emit at eight in the morning.
                announcedUpTo = arrivedAt
                nudgeMemory?.rememberAnnouncedUpTo(arrivedAt)
                // Muted by a caregiver, on this phone. Checked after `announcedUpTo` is moved
                // on, so that unmuting a conversation does not then announce everything that
                // arrived while it was muted — muting silences the arrival, it does not queue it.
                val mutedKey = ConversationLinksSource.conversationIdOf(newest)
                if (mutedKey != null && mutedConversationsRepository?.isMuted(mutedKey) == true) {
                    return@collect
                }
                // Read at the moment of the message rather than held somewhere, so that a
                // caregiver moving her bedtime on the website takes effect on the next message
                // and not on the next restart of the launcher.
                val settings = settingsRepository?.settings?.value
                if (settings != null && Arrivals.quietAt(
                        hourOfDay(now),
                        settings.quietFromHour,
                        settings.quietUntilHour
                    )
                ) {
                    return@collect
                }
                // One voice for one run. Everything above has already happened — the arrival is
                // marked as announced and the quiet hours have been respected — so this drops the
                // noise of a camera roll being emptied into a conversation and nothing else.
                // See [Arrivals.worthAnnouncingAgain].
                val sender = PictureFlood.senderOf(newest)
                if (!Arrivals.worthAnnouncingAgain(sender == lastAnnouncedSender, now - lastAnnouncedAt)) {
                    return@collect
                }
                lastAnnouncedSender = sender
                lastAnnouncedAt = now

                // The id travels with the notice so that pressing it opens this message rather
                // than the home screen — see MainActivity and [openConversationForMessage].
                //
                // The name is resolved here rather than taken off the message. What the screens
                // read has been through name resolution one layer up; what this loop reads comes
                // straight off the database, where a Threema message's sender is still the raw
                // identity it arrived as. Uncorrected, the phone would wake her in the evening to
                // say that ECHO1001 had written.
                attention?.call(
                    DisplayNames.forAnnouncement(newest, ConversationLinksSource.nameByIdentity.value),
                    newest.id,
                    // "Anna hat geschrieben" for a call she missed would send her looking for a
                    // message that was never written.
                    missedCall = newest.isMissedCall
                )
            }
        }
    }

    /**
     * Announces a reaction somebody added to a message.
     *
     * A reaction is the answer her family give most often — a heart on a photograph she sent is
     * frequently the whole of the reply — and until now it appeared on a screen she had no reason
     * to open. It is not a message, though, so it is deliberately quieter about what it says: the
     * notice names the conversation, never the emoji and never who reacted, which keeps the rule
     * the rest of this feature follows.
     *
     * Silent when there is nothing to point at. A reaction naming a message this phone has never
     * seen is ordinary — people react to things older than anything stored here — and announcing
     * one would be a nudge that opens onto nothing.
     *
     * The same three refusals as an arrival, for the same reasons: a muted conversation stays
     * muted, the quiet hours are the caregiver's to set, and a mark somebody put on something
     * last week must not be announced because the app restarted just now.
     */
    private suspend fun announceReaction(messageId: String, wireMessageId: String) {
        val message = visibleMessagesFlow?.value?.firstOrNull {
            it.id == messageId || (it.wireMessageId != null && it.wireMessageId == wireMessageId)
        } ?: return

        val now = System.currentTimeMillis()
        if (now - lastReactionAnnouncedAt < REACTION_ANNOUNCE_GAP_MS) return

        val mutedKey = ConversationLinksSource.conversationIdOf(message)
        if (mutedKey != null && mutedConversationsRepository?.isMuted(mutedKey) == true) return

        val settings = settingsRepository?.settings?.value
        if (settings != null && Arrivals.quietAt(
                hourOfDay(now),
                settings.quietFromHour,
                settings.quietUntilHour
            )
        ) {
            return
        }

        lastReactionAnnouncedAt = now
        // Resolved, for the reason given where an arrival is announced above.
        attention?.call(
            DisplayNames.forAnnouncement(message, ConversationLinksSource.nameByIdentity.value),
            message.id
        )
    }

    /**
     * Files one WhatsApp message, exactly as a Threema one is filed.
     *
     * Deliberately the same shape as the Threema collector below it, including the fallback for
     * a message that arrives before the database is attached: a message during startup is
     * ordinary, and dropping it would lose the first thing anybody wrote after a restart.
     */
    private suspend fun recordIncomingWhatsApp(event: WhatsAppEvent.Incoming) {
        // The notice this app sent, on her behalf, to somebody who rang her on WhatsApp — coming
        // back as one of her own messages, either echoed to the other linked devices now or in a
        // history sync months later. It is never shown: she did not write it, and a sentence in
        // her voice that she never said is the one thing this must not do. Recognised by its
        // words rather than by its id, because the id is forgotten when the app restarts and the
        // history sync is not.
        // Either wording: which one went out depended on whether Threema was set up at the time.
        if (event.outgoing && event.text.isNotEmpty() &&
            (event.text == callNotice || event.text == callNoticeThreema)
        ) return

        if (!event.outgoing && !event.isGroup && !event.historical) {
            val number = WhatsAppNumber.numberFromJid(event.senderJid)
            if (number != null &&
                ch.heuscher.gentleconnect.data.support.SupportManager.interceptIncoming(
                    "+$number", event.text, quotedMessageId = event.quotedId
                )
            ) return
        }

        val now = System.currentTimeMillis()
        // From the shared source rather than a fresh query: this runs on every WhatsApp message
        // that lands, and querying the provider each time is a scan of her whole address book to
        // put one name on one line.
        val contacts = ConversationLinksSource.contacts.value
        val message = WhatsAppMessageMapper.fromBridge(
            incoming = event,
            contacts = contacts,
            receivedAt = if (event.historical) 0L else now
        ) ?: run {
            // The kind of address it was, never the address: a message that goes missing between
            // the bridge and her screen leaves no other trace, and the last one took a phone, a
            // test message and an afternoon to explain. "lid" here means WhatsApp addressed the
            // chat by its hidden id and the bridge could not find the number behind it.
            Log.i(TAG, "WhatsApp message not shown, chat addressed by " + domainOf(event.chatJid))
            return
        }
        if (message.direction == MessageDirection.OUTGOING && isOwnEcho(event.messageId)) return

        val repository = conversationRepository ?: return
        val conversationId = ConversationLinksSource.conversationIdOf(message) ?: return
        repository.record(message, conversationId)
    }

    /**
     * One moment of a WhatsApp call.
     *
     * Nothing here answers anything, because nothing can: WhatsApp delivers the ringing to every
     * linked device and the audio to none of them but its own — see [CallCourtesy]. What is left
     * is to make the failure visible at both ends, and only once the call is over, because until
     * then it may yet be picked up in the WhatsApp app itself.
     *
     * A call with no remembered ringing is dropped rather than guessed at. That is an ending for
     * a call she placed, or one that was already ringing when the app started — neither of which
     * is a missed call, and both of which would become one if an ending alone were enough.
     */
    private suspend fun whatsAppCall(
        context: Context,
        whatsapp: WhatsAppClient,
        event: WhatsAppEvent.Call
    ) {
        // A call she made herself, in the WhatsApp app on this phone. Hers arrive here like
        // anybody else's; told she had missed one, she would ring back somebody she had just
        // rung.
        if (event.fromMe) return

        when (event.state) {
            CallState.RINGING -> {
                forgetStaleCalls(event.timestamp)
                // WhatsApp reports one call ringing more than once; only the first is acted on.
                val firstRing = event.callId !in ringingCalls
                ringingCalls.getOrPut(event.callId) { RingingCall(startedAt = event.timestamp) }
                // A caregiver has asked for WhatsApp calls not to ring on her phone — see
                // RemoteSettings.rejectWhatsAppCalls. The bridge reports a call it turned away as
                // ending with CallCourtesy.REJECTED_HERE, and the ENDED branch below then treats
                // it like any call nobody took: the card for her, the notice for the caller.
                if (firstRing && settingsRepository?.settings?.value?.rejectWhatsAppCalls == true) {
                    whatsapp.rejectCall(event.callId)
                }
            }

            // WhatsApp saying the call was picked up. The reliable half of [CallCourtesy.missed].
            CallState.ANSWERED -> ringingCalls[event.callId]?.answeredElsewhere = true

            CallState.ENDED -> {
                val ringing = ringingCalls.remove(event.callId) ?: return
                val rangFor = event.timestamp - ringing.startedAt
                if (CallCourtesy.missed(event.reason, ringing.answeredElsewhere, rangFor)) {
                    whatsAppCallMissed(context, whatsapp, event)
                }
            }
        }
    }

    /**
     * A WhatsApp call that nobody answered: tell her, tell whoever rang, and write the line a
     * caregiver can act on.
     *
     * The three are deliberately independent. A caller the address book cannot place is still
     * shown to her; a call from a hidden id that cannot be shown to her is still logged; and the
     * log line carries no name, because it is published to the caregiver website.
     */
    private suspend fun whatsAppCallMissed(
        context: Context,
        whatsapp: WhatsAppClient,
        event: WhatsAppEvent.Call
    ) {
        val now = System.currentTimeMillis()
        val contacts = ConversationLinksSource.contacts.value

        val message = WhatsAppMessageMapper.missedCall(
            call = event,
            contacts = contacts,
            text = context.getString(R.string.nudge_missed_call),
            receivedAt = now
        )
        if (message != null) {
            val repository = conversationRepository
            val conversationId = ConversationLinksSource.conversationIdOf(message)
            if (repository != null && conversationId != null) {
                repository.record(message, conversationId)
            }
        } else {
            // The kind of address it was, never the address — as for a message that cannot be
            // placed. A call from a hidden id is a card naming nobody, so she is shown nothing.
            Log.i(TAG, "WhatsApp call not shown, caller addressed by " + domainOf(event.callerJid))
        }

        caregiverLogRepository?.record(
            CaregiverEvent(kind = CaregiverEventKind.WHATSAPP_CALL_UNANSWERED, at = now)
        )

        val known = WhatsAppMessageMapper.contactFor(event.callerJid, contacts) != null
        if (!CallCourtesy.worthTelling(
                knownCaller = known,
                group = event.group,
                toldLastAt = toldCallersAt[event.callerJid] ?: 0L,
                now = now
            )
        ) {
            return
        }

        // Remembered only if it left. A notice that failed to send has told nobody anything, and
        // the next call from the same person is the right moment to try again.
        if (whatsapp.sendText(event.callerJid, callNoticeFor())) {
            toldCallersAt[event.callerJid] = now
        }
    }

    /**
     * The notice for somebody who rang on WhatsApp: the telephone number, and Threema as well where
     * this phone has an identity to be rung on. Asked at the time of sending rather than at start,
     * because a caregiver can set Threema up while the app is running.
     */
    private fun callNoticeFor(): String =
        if (threemaIdentityRepository?.hasIdentity() == true) callNoticeThreema else callNotice

    /**
     * Drops calls that never reported an ending.
     *
     * A ringing that is never closed is ordinary — the socket drops mid-call, the app is killed,
     * WhatsApp says nothing more about it — and without this the map grows for as long as the
     * phone is up. Everything older than an hour is past any use it could have had: an ending
     * arriving after that would compute a ring of an hour, which [CallCourtesy.missed] discards
     * anyway.
     */
    private fun forgetStaleCalls(now: Long) {
        if (ringingCalls.size < STALE_CALL_LIMIT) return
        ringingCalls.entries.removeAll { now - it.value.startedAt > STALE_CALL_MS }
    }

    /** The part of an address that says what kind it is — `s.whatsapp.net`, `lid`, `g.us`. */
    private fun domainOf(jid: String?): String =
        jid?.substringAfter('@', "")?.ifBlank { null } ?: "nothing"

    private suspend fun isOwnEcho(wireMessageId: String): Boolean {
        val repository = conversationRepository ?: return false
        return repository.hasWireMessage(wireMessageId, BridgeTransport.WHATSAPP)
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

    private fun hourOfDay(moment: Long): Int =
        Calendar.getInstance().apply { timeInMillis = moment }.get(Calendar.HOUR_OF_DAY)
}
