package ch.heuscher.gentleconnect.data.support

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.CallRouter
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.ContactsRepository
import ch.heuscher.gentleconnect.data.ConversationLinksSource
import ch.heuscher.gentleconnect.data.DeviceIdProvider
import ch.heuscher.gentleconnect.data.FirebaseAuthGate
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.PhoneLocationSource
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppEvent
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppLinkState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.CaregiverAlerts
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.model.SupportChannel
import ch.heuscher.gentleconnect.model.SupportChannelPolicy
import ch.heuscher.gentleconnect.model.SupportClaimPolicy
import ch.heuscher.gentleconnect.model.SupportClaimVia
import ch.heuscher.gentleconnect.model.SupportGroupResolver
import ch.heuscher.gentleconnect.model.SupportNames
import ch.heuscher.gentleconnect.model.SupportDelivery
import ch.heuscher.gentleconnect.model.SupportLocation
import ch.heuscher.gentleconnect.model.SupportMember
import ch.heuscher.gentleconnect.model.SupportMessageText
import ch.heuscher.gentleconnect.model.SupportPhoneStatus
import ch.heuscher.gentleconnect.model.SupportRequest
import ch.heuscher.gentleconnect.model.SupportStatus
import ch.heuscher.gentleconnect.model.ThreemaReceipt
import ch.heuscher.gentleconnect.model.ThreemaWireId
import ch.heuscher.gentleconnect.reply.SmsSender
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Everything behind the support button: the request, who was told on what, and who took it.
 *
 * See `docs/support-button.md` for the agreed behaviour. The decisions live in the pure policies
 * in `model/` — this class only gathers what they need from the phone and carries out what they
 * say.
 *
 * The request lives in memory. The launcher's process is kept alive by its foreground service,
 * and the one thing that would be lost to a process death — two hours of ring-through for the
 * family — is a convenience, not the alert itself, which has already gone by then.
 */
class SupportManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val firestore by lazy { Firebase.firestore }

    private val _currentRequest = MutableStateFlow<SupportRequest?>(null)
    val currentRequest: StateFlow<SupportRequest?> = _currentRequest.asStateFlow()

    private val _buttonState = MutableStateFlow<SupportButtonState>(SupportButtonState.None)
    val buttonState: StateFlow<SupportButtonState> = _buttonState.asStateFlow()

    /**
     * Her settings with [RemoteSettings.supportGroup] already resolved against her address book, so
     * every use below sees members with a number, a photograph and their messenger addresses.
     */
    @Volatile
    private var settings = RemoteSettings()

    private val _members = MutableStateFlow<List<SupportMember>>(emptyList())

    /** The support group as resolved on this phone — what her dialog and status screen show. */
    val members: StateFlow<List<SupportMember>> = _members.asStateFlow()
    private var firestoreListener: ListenerRegistration? = null

    /** The wire id of the alert each member was sent, so a reaction or quote can be matched to it. */
    private val alertMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Members for whom the messenger said the alert arrived — they need no SMS on top. */
    private val confirmedMembers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        ConversationLinksSource.start(context)
        val contactsRepository = ContactsRepository(context)
        scope.launch {
            combine(
                Messengers.settings(context).settings,
                ConversationLinksSource.contactsWithIdentities,
                ConversationLinksSource.phonesByContactId,
                ConversationLinksSource.threemaIdByNumber
            ) { latest, contacts, phones, threemaIds ->
                val group = withContext(Dispatchers.IO) {
                    SupportGroupResolver.resolve(
                        configured = latest.supportGroup,
                        contacts = contacts,
                        // Every number they have, not the one the contacts provider happened to
                        // return first — see ContactPhone for the son that cost.
                        phonesOf = { contact -> phones[contact.id].orEmpty() },
                        threemaIdFor = { number ->
                            ConversationLinksSource.normalizedNumber(number)?.let { threemaIds[it] }
                        },
                        // WhatsApp only for somebody the bridge has already seen there: a cached
                        // profile picture is that proof. Sending to a number that is not on
                        // WhatsApp could still count as sent and hold back the SMS.
                        whatsAppNumberFor = { number ->
                            number.takeIf { contactsRepository.findWhatsAppAvatar(it) != null }
                        }
                    )
                }
                latest.copy(supportGroup = group)
            }.collect { resolved ->
                settings = resolved
                _members.value = resolved.supportGroup
                recomputeButtonState()
            }
        }

        // A WhatsApp send is only known to have left when the bridge says so.
        scope.launch {
            Messengers.whatsApp(context).events.collect { event ->
                if (event is WhatsAppEvent.Sent) {
                    val member = alertMessageIds.entries.firstOrNull { it.value == event.messageId }?.key
                    if (member != null) confirmedMembers.add(member)
                }
            }
        }

        // The button changes by itself as time passes: a claim stops showing after an hour, a
        // request stops existing after two.
        scope.launch {
            while (true) {
                delay(10_000L)
                val req = _currentRequest.value
                if (req != null && SupportClaimPolicy.isRequestExpired(req.createdAt, System.currentTimeMillis())) {
                    _currentRequest.value = null
                    stopFirestoreListener()
                }
                recomputeButtonState()
            }
        }
    }

    private fun recomputeButtonState() {
        val group = settings.supportGroup
        if (group.isEmpty()) {
            _buttonState.value = SupportButtonState.None
            return
        }
        val req = _currentRequest.value
        val now = System.currentTimeMillis()
        _buttonState.value = when {
            req == null || SupportClaimPolicy.isRequestExpired(req.createdAt, now) ->
                SupportButtonState.Idle(group.first())
            req.status == SupportStatus.OPEN -> SupportButtonState.Sent(group.first(), req.id)
            req.status == SupportStatus.CLAIMED &&
                SupportClaimPolicy.isClaimStateActive(req.createdAt, req.claimedAt, req.claimerCallEndedAt, now) ->
                SupportButtonState.Claimed(memberById(req.claimedBy) ?: group.first(), req.id)
            else -> SupportButtonState.Idle(group.first())
        }
    }

    private fun memberById(id: String?): SupportMember? =
        if (id == null) null else settings.supportGroup.firstOrNull { it.id == id }

    fun isRingThroughActiveForPhone(rawNumber: String): Boolean {
        val req = _currentRequest.value ?: return false
        if (!SupportClaimPolicy.isRingThroughActive(req.createdAt, System.currentTimeMillis())) return false
        return settings.supportGroup.any { member ->
            samePhone(member.phoneNumber, rawNumber) ||
                member.otherNumbers.any { samePhone(it, rawNumber) }
        }
    }

    fun isRingThroughActiveForThreema(threemaId: String): Boolean {
        val req = _currentRequest.value ?: return false
        if (!SupportClaimPolicy.isRingThroughActive(req.createdAt, System.currentTimeMillis())) return false
        return settings.supportGroup.any { it.threemaId.equals(threemaId, ignoreCase = true) }
    }

    /** The question went unanswered. Written to her log, quietly: nobody is interrupted. */
    fun logSupportOpenedNotConfirmed() {
        scope.launch(Dispatchers.IO) {
            try {
                CaregiverLogRepository(AppDatabase.getInstance(context).caregiverEventDao()).record(
                    CaregiverEvent(
                        kind = CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED,
                        at = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unconfirmed support opening not logged", e)
            }
        }
    }

    /**
     * She said yes. Alerts everyone; with [isReAlert] and a request still running, alerts
     * everyone again under the same request, which is then open again whoever had taken it.
     */
    fun triggerSupportRequest(screen: String, isReAlert: Boolean = false) {
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = _currentRequest.value?.takeIf {
                isReAlert && !SupportClaimPolicy.isRequestExpired(it.createdAt, now)
            }
            val shareLocation = settings.supportShareLocation

            // Shown on her status screen straight away, before anything is known: the screen she
            // lands on after "Ja" must never be an empty one.
            val draft = SupportRequest(
                id = existing?.id ?: UUID.randomUUID().toString(),
                createdAt = existing?.createdAt ?: now,
                screen = existing?.screen ?: screenLabel(screen),
                status = SupportStatus.OPEN,
                location = existing?.location,
                repeats = if (existing != null) existing.repeats + now else emptyList()
            )
            alertMessageIds.clear()
            confirmedMembers.clear()
            _currentRequest.value = draft
            recomputeButtonState()

            // The first answer the phone already has, without waiting for radios: the alert should
            // not sit behind a GPS fix. A better one follows on the messengers — see below.
            val location = if (shareLocation) {
                withTimeoutOrNull(QUICK_FIX_MS) { PhoneLocationSource(context).current() }
                    ?.let { SupportLocation(it.latitude, it.longitude, it.accuracyMetres, it.at) }
            } else null

            val request = draft.copy(
                location = location ?: draft.location,
                phoneStatus = capturePhoneStatus()
            )
            // Onto whatever the request has become meanwhile — somebody may already have taken it.
            _currentRequest.update {
                if (it?.id == request.id) it.copy(location = request.location, phoneStatus = request.phoneStatus) else it
            }

            syncRequestToFirestore(request, withStatus = true)
            startFirestoreListener(request.id)
            dispatchAlerts(request)

            if (shareLocation) followUpWithBetterLocation(request.id, location)
        }
    }

    private suspend fun capturePhoneStatus(): SupportPhoneStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        // From the resources, because this word is read out again inside the message the family
        // receives — it was the last German left hardcoded on that path.
        val network = context.getString(
            when {
                !CallRouter.hasInternet(context) -> R.string.support_network_none
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.string.support_network_wifi
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> R.string.support_network_mobile
                else -> R.string.support_network_other
            }
        )

        val threemaUp = Messengers.threema(context).connectionState.value == ThreemaConnectionState.Connected
        val whatsAppUp = Messengers.whatsApp(context).linkState.value == WhatsAppLinkState.LIVE

        // How many faults her log would have reported today — named in words on the portal, which
        // has the room for it. Here only the number, as a reason to go and look.
        val faults = try {
            val now = System.currentTimeMillis()
            val events = CaregiverLogRepository(AppDatabase.getInstance(context).caregiverEventDao())
                .events.first()
                .filter { now - it.at <= DAY_MS }
            CaregiverAlerts.toAlerts(events, now).map { it.kind.name }
        } catch (e: Exception) {
            emptyList()
        }

        val appVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        } catch (e: Exception) {
            0L
        }

        return SupportPhoneStatus(
            battery = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = plugged == BatteryManager.BATTERY_STATUS_CHARGING ||
                plugged == BatteryManager.BATTERY_STATUS_FULL,
            network = network,
            roaming = CallRouter.isRoaming(context),
            voiceService = CallRouter.hasCellularVoiceService(context),
            threema = if (threemaUp) "verbunden" else "getrennt",
            whatsapp = if (whatsAppUp) "verbunden" else "getrennt",
            faults = faults,
            appVersion = appVersion
        )
    }

    private fun followUpWithBetterLocation(requestId: String, first: SupportLocation?) {
        scope.launch {
            val fresh = withTimeoutOrNull(FULL_FIX_MS) { PhoneLocationSource(context).current() } ?: return@launch
            val freshRadius = fresh.accuracyMetres ?: return@launch
            val firstRadius = first?.accuracyMetres
            val better = firstRadius == null || freshRadius + BETTER_BY_METRES < firstRadius
            if (!better) return@launch
            val req = _currentRequest.value?.takeIf { it.id == requestId } ?: return@launch

            val location = SupportLocation(fresh.latitude, fresh.longitude, fresh.accuracyMetres, fresh.at)
            val updated = req.copy(location = location)
            _currentRequest.value = updated
            syncRequestToFirestore(updated)

            // On the messengers only: a second SMS for a few metres is not worth what it costs.
            val text = SupportMessageText.buildFollowUpLocation(supportTextsOf(context), location)
            settings.supportGroup.forEach { member ->
                when (SupportChannelPolicy.selectInitialChannel(member, hasInternet = true)) {
                    SupportChannel.THREEMA -> Messengers.threema(context).sendTextMessage(member.threemaId!!, text)
                    SupportChannel.WHATSAPP -> sendWhatsApp(member.whatsAppNumber!!, text)
                    SupportChannel.SMS -> Unit
                }
            }
        }
    }

    private suspend fun dispatchAlerts(request: SupportRequest) {
        val hasInternet = CallRouter.hasInternet(context)
        val texts = supportTextsOf(context)
        val messengerText = SupportMessageText.formatMessengerAlert(
            texts = texts,
            groupName = settings.supportGroupName,
            alertTime = request.repeats.lastOrNull() ?: request.createdAt,
            location = request.location,
            phoneStatus = request.phoneStatus,
            shareLocation = settings.supportShareLocation,
            recipientNames = settings.supportGroup.map { SupportNames.firstName(it.name) }
        )
        val smsText = SupportMessageText.formatSmsAlert(
            texts = texts,
            alertTime = request.repeats.lastOrNull() ?: request.createdAt,
            location = request.location,
            phoneStatus = request.phoneStatus,
            shareLocation = settings.supportShareLocation
        )

        settings.supportGroup.forEach { member ->
            // Roaming is deliberately not an input here. `threemaOnlyWhenRoaming` is about what
            // a call costs abroad; a help request is worth an SMS wherever she is.
            when (SupportChannelPolicy.selectInitialChannel(member, hasInternet)) {
                SupportChannel.THREEMA -> {
                    val id = ThreemaWireId.random()
                    alertMessageIds[member.id] = id
                    val handed = Messengers.threema(context).sendTextMessage(member.threemaId!!, messengerText, id)
                    recordDelivery(request.id, member, "threema", if (handed) "sent" else "failed")
                    smsIfUnconfirmed(request.id, member, smsText, immediately = !handed)
                }
                SupportChannel.WHATSAPP -> {
                    val id = WHATSAPP_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
                    alertMessageIds[member.id] = id
                    val handed = sendWhatsApp(member.whatsAppNumber!!, messengerText, id)
                    recordDelivery(request.id, member, "whatsapp", if (handed) "sent" else "failed")
                    smsIfUnconfirmed(request.id, member, smsText, immediately = !handed)
                }
                SupportChannel.SMS -> sendSms(request.id, member, smsText)
            }
        }
    }

    /**
     * The SMS backup for one member.
     *
     * A messenger that has not confirmed within a minute gets an SMS on top. WhatsApp confirms with
     * the bridge's `Sent`; Threema has no send confirmation on this phone, only the family's own
     * receipts, which arrive only if they have receipts switched on — so a Threema member may get
     * both. Twice is the right way round to be wrong for a help request.
     */
    private fun smsIfUnconfirmed(requestId: String, member: SupportMember, smsText: String, immediately: Boolean) {
        scope.launch {
            if (!immediately) delay(SupportChannelPolicy.MESSENGER_CONFIRMATION_TIMEOUT_MS)
            val req = _currentRequest.value?.takeIf { it.id == requestId } ?: return@launch
            if (req.status != SupportStatus.OPEN) return@launch
            if (member.id in confirmedMembers) return@launch
            sendSms(requestId, member, smsText)
        }
    }

    private suspend fun sendSms(requestId: String, member: SupportMember, text: String) {
        val sent = try {
            SmsSender(context).send(member.phoneNumber, text)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to send the support SMS", e)
            false
        }
        recordDelivery(requestId, member, "sms", if (sent) "sent" else "failed")
        if (!sent) retryLater(requestId, member, text)
    }

    /** Nothing reached this member at all: keep trying for as long as the request lives. */
    private fun retryLater(requestId: String, member: SupportMember, smsText: String) {
        scope.launch {
            val req = _currentRequest.value?.takeIf { it.id == requestId } ?: return@launch
            if (req.deliveries.any { it.memberId == member.id && it.state != "failed" }) return@launch
            val interval = SupportChannelPolicy.nextRetryIntervalMs(System.currentTimeMillis() - req.createdAt)
                ?: return@launch
            delay(interval)
            val still = _currentRequest.value?.takeIf { it.id == requestId && it.status == SupportStatus.OPEN }
                ?: return@launch
            if (still.deliveries.any { it.memberId == member.id && it.state != "failed" }) return@launch
            sendSms(requestId, member, smsText)
        }
    }

    private fun recordDelivery(requestId: String, member: SupportMember, channel: String, state: String) {
        var updated: SupportRequest? = null
        _currentRequest.update { req ->
            if (req == null || req.id != requestId) return@update req
            val others = req.deliveries.filterNot { it.memberId == member.id && it.channel == channel }
            req.copy(deliveries = others + SupportDelivery(member.id, channel, state, System.currentTimeMillis()))
                .also { updated = it }
        }
        updated?.let { syncRequestToFirestore(it) }
    }

    private fun sendWhatsApp(number: String, text: String, messageId: String? = null): Boolean {
        val jid = WhatsAppNumber.jidForNumber(number) ?: return false
        return Messengers.whatsApp(context).sendText(jid, text, messageId)
    }

    /**
     * An incoming 1:1 message or reaction, before it is filed for her.
     *
     * @return true when it was a claim (or a late claim) and must not appear among her messages.
     *   Anything else a family member writes goes through untouched, even during a request.
     */
    fun handleIncomingMessage(
        sender: String,
        text: String?,
        quotedMessageId: String? = null,
        reactionTargetId: String? = null
    ): Boolean {
        val req = _currentRequest.value ?: return false
        val member = memberFor(sender) ?: return false
        val now = System.currentTimeMillis()
        val alertId = alertMessageIds[member.id]
        val quoted = alertId != null && quotedMessageId == alertId
        val isReaction = reactionTargetId != null
        val reacted = alertId != null && reactionTargetId == alertId
        // Before, a 👍 on any message of hers while a request was claimed counted as a second
        // claim — and was swallowed, so she never saw it. Reactions count only on the alert.
        val saysYes = SupportClaimPolicy.isClaimSignal(quoted, reacted, text, isReaction)

        if (SupportClaimPolicy.isSecondClaim(req, member.id, now)) {
            if (!saysYes) return false
            val first = (memberById(req.claimedBy)?.name ?: req.claimedByName)?.let { SupportNames.firstName(it) } ?: return true
            sendToMember(member, SupportMessageText.secondClaimText(supportTextsOf(context), first, req.claimedAt ?: now))
            return true
        }

        if (!saysYes || !SupportClaimPolicy.canClaim(req, now)) return false
        applyClaim(member, SupportClaimVia.REPLY, now)
        return true
    }

    private fun applyClaim(member: SupportMember, via: SupportClaimVia, at: Long) {
        var claimed: SupportRequest? = null
        _currentRequest.update { req ->
            if (req == null || req.status != SupportStatus.OPEN) return@update req
            req.copy(
                status = SupportStatus.CLAIMED,
                claimedBy = member.id,
                claimedByName = member.name,
                claimedVia = via,
                claimedAt = at
            ).also { claimed = it }
        }
        val req = claimed ?: return
        recomputeButtonState()
        // Not echoed back to the portal when the portal is where the claim came from.
        if (via != SupportClaimVia.PORTAL) syncRequestToFirestore(req, withStatus = true)

        val notice = when (via) {
            // First names in the family's messages, not the address book's "Johann "Papa Opa"
            // Hartmann" — see SupportNames.
            SupportClaimVia.CALL -> SupportMessageText.callConnectedNoticeText(
                texts = supportTextsOf(context),
                claimerName = SupportNames.firstName(member.name),
                alertTime = req.createdAt,
                callTime = at
            )
            else -> SupportMessageText.claimNoticeText(
                texts = supportTextsOf(context),
                claimerName = SupportNames.firstName(member.name),
                alertTime = req.createdAt
            )
        }
        settings.supportGroup.filter { it.id != member.id }.forEach { sendToMember(it, notice) }
    }

    /** Tells one member something, on the channel their alert most likely reached them on. */
    private fun sendToMember(member: SupportMember, message: String) {
        scope.launch {
            val deliveredBySms = _currentRequest.value?.deliveries
                ?.any { it.memberId == member.id && it.channel == "sms" && it.state == "sent" } == true
            val channel = SupportChannelPolicy.selectInitialChannel(member, CallRouter.hasInternet(context))
            if (channel == SupportChannel.THREEMA) {
                Messengers.threema(context).sendTextMessage(member.threemaId!!, message)
            } else if (channel == SupportChannel.WHATSAPP) {
                sendWhatsApp(member.whatsAppNumber!!, message)
            }
            if (channel == SupportChannel.SMS || deliveredBySms) {
                try {
                    SmsSender(context).send(member.phoneNumber, SupportMessageText.stripEmojiForSms(message))
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission to send the support notice by SMS", e)
                }
            }
        }
    }

    /** A call with somebody reached the talking stage — telephone, Threema or WhatsApp. */
    fun onCallConnected(peer: String) {
        val member = memberFor(peer) ?: return
        val req = _currentRequest.value ?: return
        if (SupportClaimPolicy.isCallClaim(true, true, req, System.currentTimeMillis())) {
            applyClaim(member, SupportClaimVia.CALL, System.currentTimeMillis())
        }
    }

    fun onCallEnded(peer: String) {
        val member = memberFor(peer) ?: return
        _currentRequest.update { req ->
            if (req != null && req.claimedBy == member.id) req.copy(claimerCallEndedAt = System.currentTimeMillis())
            else req
        }
        recomputeButtonState()
    }

    fun onCallMissed(peer: String) {
        val member = memberFor(peer) ?: return
        _currentRequest.update { req -> req?.copy(missedCallMemberName = member.name) }
    }

    /**
     * A Threema delivery or read receipt: the body is the receipt type followed by one or more
     * message ids, each in wire (little-endian) order — the reverse of how ids are printed. See
     * [ThreemaReceipt.body].
     */
    fun onThreemaReceipt(sender: String, dataHex: String) {
        val member = settings.supportGroup.firstOrNull { it.threemaId.equals(sender, ignoreCase = true) } ?: return
        val alertId = alertMessageIds[member.id] ?: return
        val hex = dataHex.lowercase()
        if (hex.length < 2 + 16) return
        val type = hex.substring(0, 2).toIntOrNull(16) ?: return
        val ids = hex.substring(2).chunked(16).filter { it.length == 16 }
            .map { it.chunked(2).asReversed().joinToString("") }
        if (alertId.lowercase() !in ids) return

        confirmedMembers.add(member.id)
        if (type == ThreemaReceipt.READ) {
            val reqId = _currentRequest.value?.id ?: return
            recordDelivery(reqId, member, "threema", "read")
        }
    }

    private fun memberFor(identifier: String): SupportMember? {
        if (identifier.isBlank()) return null
        return settings.supportGroup.firstOrNull { m ->
            m.threemaId.equals(identifier, ignoreCase = true) ||
                samePhone(m.phoneNumber, identifier) ||
                (m.whatsAppNumber != null && samePhone(m.whatsAppNumber, identifier)) ||
                // He was alerted on his mobile and rang back from the office — still him.
                m.otherNumbers.any { samePhone(it, identifier) }
        }
    }

    private fun samePhone(a: String, b: String): Boolean {
        val x = SmsSender.normalizeAddress(a).filter { it.isDigit() }
        val y = SmsSender.normalizeAddress(b).filter { it.isDigit() }
        if (x.length < MIN_PHONE_DIGITS || y.length < MIN_PHONE_DIGITS) return false
        // National and international forms of one number share their last nine digits.
        return x.takeLast(MIN_PHONE_DIGITS) == y.takeLast(MIN_PHONE_DIGITS)
    }

    /**
     * Writes the request for the portal.
     *
     * Merged, and the status only when the phone is the one changing it ([withStatus]): a claim
     * made on the portal must not be put back to "open" by the phone reporting a delivery a moment
     * later.
     */
    private fun syncRequestToFirestore(request: SupportRequest, withStatus: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!FirebaseAuthGate.awaitSignedIn()) return@launch
                val doc = mutableMapOf<String, Any?>(
                    "createdAt" to request.createdAt,
                    "screen" to request.screen,
                    "repeats" to request.repeats,
                    "location" to request.location?.let {
                        mapOf("lat" to it.lat, "lon" to it.lon, "accuracyMetres" to it.accuracyMetres, "at" to it.at)
                    },
                    "phoneStatus" to request.phoneStatus?.let {
                        mapOf(
                            "battery" to it.battery,
                            "charging" to it.charging,
                            "network" to it.network,
                            "roaming" to it.roaming,
                            "threema" to it.threema,
                            "whatsapp" to it.whatsapp,
                            "faults" to it.faults,
                            "appVersion" to it.appVersion
                        )
                    },
                    "deliveries" to request.deliveries.map {
                        mapOf(
                            "memberId" to it.memberId,
                            "name" to memberById(it.memberId)?.name,
                            "channel" to it.channel,
                            "state" to it.state,
                            "at" to it.at
                        )
                    }
                )
                if (withStatus) {
                    doc["status"] = request.status.name.lowercase()
                    doc["claimedBy"] = request.claimedByName
                    doc["claimedVia"] = request.claimedVia?.name?.lowercase()
                    doc["claimedAt"] = request.claimedAt
                }
                firestore.collection("devices").document(DeviceIdProvider.getDeviceId(context))
                    .collection("supportRequests").document(request.id)
                    .set(doc, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Support request not synced", e)
            }
        }
    }

    /** The portal's "Ich kümmere mich" and "erledigt", arriving on the phone. */
    private fun startFirestoreListener(requestId: String) {
        stopFirestoreListener()
        firestoreListener = try {
            firestore.collection("devices").document(DeviceIdProvider.getDeviceId(context))
                .collection("supportRequests").document(requestId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    when (snapshot.getString("status")) {
                        "claimed" -> {
                            val via = snapshot.getString("claimedVia")
                            if (via != "portal") return@addSnapshotListener
                            val name = snapshot.getString("claimedBy") ?: return@addSnapshotListener
                            val member = settings.supportGroup.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            if (member != null) {
                                applyClaim(member, SupportClaimVia.PORTAL, System.currentTimeMillis())
                            } else {
                                _currentRequest.update { req ->
                                    if (req == null || req.id != requestId || req.status != SupportStatus.OPEN) req
                                    else req.copy(
                                        status = SupportStatus.CLAIMED,
                                        claimedByName = name,
                                        claimedVia = SupportClaimVia.PORTAL,
                                        claimedAt = System.currentTimeMillis()
                                    )
                                }
                                recomputeButtonState()
                            }
                        }
                        "closed" -> {
                            _currentRequest.update { req -> if (req?.id == requestId) null else req }
                            recomputeButtonState()
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Support request listener not started", e)
            null
        }
    }

    private fun stopFirestoreListener() {
        firestoreListener?.remove()
        firestoreListener = null
    }

    companion object {
        private const val TAG = "SupportManager"
        private const val QUICK_FIX_MS = 3_000L
        private const val FULL_FIX_MS = 20_000L
        private const val BETTER_BY_METRES = 15
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MIN_PHONE_DIGITS = 9
        private const val WHATSAPP_ID_PREFIX = "3EB0"

        @Volatile
        private var instance: SupportManager? = null

        fun getInstance(context: Context): SupportManager =
            instance ?: synchronized(this) {
                instance ?: SupportManager(context.applicationContext).also { instance = it }
            }

        /** For the call paths, which must not be the thing that starts this up. */
        fun isRingThroughActiveForPhone(context: Context, rawNumber: String): Boolean =
            instance?.isRingThroughActiveForPhone(rawNumber) ?: false

        fun isRingThroughActiveForThreema(context: Context, threemaId: String): Boolean =
            instance?.isRingThroughActiveForThreema(threemaId) ?: false

        fun onCallConnected(context: Context, peer: String) {
            instance?.onCallConnected(peer)
        }

        fun onCallEnded(context: Context, peer: String) {
            instance?.onCallEnded(peer)
        }

        fun onCallMissed(context: Context, peer: String) {
            instance?.onCallMissed(peer)
        }

        /** An incoming message, before it is filed for her. See [handleIncomingMessage]. */
        fun interceptIncoming(
            sender: String,
            text: String?,
            quotedMessageId: String? = null,
            reactionTargetId: String? = null
        ): Boolean = instance?.handleIncomingMessage(sender, text, quotedMessageId, reactionTargetId) ?: false

        fun onThreemaReceipt(sender: String, dataHex: String) {
            instance?.onThreemaReceipt(sender, dataHex)
        }

        /** Her screens by the names the family knows them by. */
        fun screenLabel(screen: String): String = when (screen) {
            "Home" -> "Startseite"
            "PhoneBook" -> "Telefonbuch"
            "Keypad" -> "Tastatur"
            "RecentCalls" -> "Anrufliste"
            "Messages" -> "Nachrichten"
            "Conversation" -> "Unterhaltung"
            "Look" -> "Post"
            "Appointments" -> "Termine"
            "SupportStatus" -> "Hilfe"
            else -> screen
        }
    }
}
