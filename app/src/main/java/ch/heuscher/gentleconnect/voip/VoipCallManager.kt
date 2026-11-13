package ch.heuscher.gentleconnect.voip

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.threema.ThreemaCallMessage
import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.telecom.CallStage
import ch.heuscher.gentleconnect.telecom.OngoingCall
import ch.heuscher.gentleconnect.voip.signalling.VoipCallAnswerData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallHangupData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallOfferData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallRingingData
import ch.heuscher.gentleconnect.voip.signalling.VoipICECandidatesData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.Date
import kotlin.random.Random

/**
 * Coordinates [ThreemaClient] signalling, [PeerConnectionClient] WebRTC connection, [AudioHandler],
 * [CallStateMachine], and [GentleConnectionService] Telecom integration for voice calls.
 *
 * Implements strict telephone-like behavior for cognitively impaired users:
 * - Immediate ringback tone on dialling, matching old telephone exchanges
 * - Standard European busy tone cadence on call end, rejection, or failure
 * - 4-second silent dropout grace period for momentary ICE disconnections during connected calls
 * - Automatic blocking and logging of unknown Threema callers
 * - Stale offer detection dropping offers older than 60 seconds
 * - Immediate busy rejection if an ordinary cellular call is in progress
 */
class VoipCallManager(
    val context: Context,
    private val threemaClient: ThreemaClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : PeerConnectionEvents {

    val stateMachine = CallStateMachine()
    val audioHandler = AudioHandler(context)
    var activeConnection: GentleCallConnection? = null

    private val _currentCallState = MutableStateFlow<CallState>(CallState.IDLE)
    val currentCallState: StateFlow<CallState> = _currentCallState.asStateFlow()

    /** Exposes live bridge connection state. */
    val threemaBridgeState: StateFlow<ThreemaConnectionState> get() = threemaClient.connectionState

    /** Whether the Threema bridge is currently fully connected and ready to place calls. */
    val isThreemaReachable: Boolean get() = threemaClient.connectionState.value is ThreemaConnectionState.Connected

    private var peerConnectionClient: PeerConnectionClient? = null
    private var cachedTurnCredentials: TurnCredentials? = null
    private var phoneAccountHandle: PhoneAccountHandle? = null
    private var pendingRemoteOffer: VoipCallOfferData? = null
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var signallingJob: Job? = null

    private val ringbackTone = RingbackTone()
    private val busyTone = BusyTone()
    private val incomingRinger by lazy { IncomingRinger(context) }

    private var unansweredTimeoutJob: Job? = null
    private var ringingTimeoutJob: Job? = null
    private var incomingTimeoutJob: Job? = null
    private var dropoutGraceJob: Job? = null
    private var dropoutStartedAt: Long = 0L

    private var callTapTimestamp: Long = 0L

    init {
        GentleConnectionService.activeManager = this
        registerPhoneAccount()
        listenToSignalling()
    }

    fun dispose() {
        signallingJob?.cancel()
        signallingJob = null
        unregisterPhoneAccount()
        if (GentleConnectionService.activeManager === this) {
            GentleConnectionService.activeManager = null
        }
        terminateCall()
    }

    fun stop() = dispose()

    /**
     * Silences incoming ringtone and vibration without hanging up the incoming call.
     */
    fun silenceIncomingRinging() {
        incomingRinger.silence()
    }

    private fun elapsedSinceTap(): Long =
        if (callTapTimestamp > 0L) SystemClock.elapsedRealtime() - callTapTimestamp else 0L

    private fun registerPhoneAccount() {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val componentName = ComponentName(context, GentleConnectionService::class.java)
            val handle = PhoneAccountHandle(componentName, "GentleConnectVoipAccount")
            phoneAccountHandle = handle

            // SELF_MANAGED, not CALL_PROVIDER. A CALL_PROVIDER account is registered in a
            // disabled state and someone has to go and switch it on in
            // Settings > Calling accounts before Telecom will accept a call. Nobody would
            // ever discover that: incoming calls would simply never ring, with no error
            // anywhere. SELF_MANAGED needs no such step, keeps this out of the system
            // dialer entirely, and is what MANAGE_OWN_CALLS in the manifest is actually for.
            val account = PhoneAccount.builder(handle, "Gentle Connect Voice")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneAccount", e)
        }
    }

    private fun unregisterPhoneAccount() {
        try {
            phoneAccountHandle?.let { handle ->
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.unregisterPhoneAccount(handle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister PhoneAccount", e)
        }
    }

    private fun listenToSignalling() {
        signallingJob?.cancel()
        signallingJob = scope.launch {
            launch {
                threemaClient.callMessages.collect { msg ->
                    handleIncomingSignallingMessage(msg, msg.parseSignallingData())
                }
            }
            launch {
                // Fetched as soon as the bridge is up, not when she presses a face. A request
                // made at dial time depends on the bridge being alive at that very moment, and on
                // the Fold 2026-09-16 it was not: the bridge restarted 17 ms after the tap, the
                // request died with it, and the call went out with no relay after a 3 s wait.
                threemaClient.connectionState.collect { state ->
                    if (state is ThreemaConnectionState.Connected) ensureTurnCredentials()
                }
            }
            launch {
                threemaClient.turnCredentialsMessages.collect { turnMsg ->
                    if (turnMsg.error == null) {
                        val expDate = Date(System.currentTimeMillis() + (turnMsg.expirationSeconds * 1000L))
                        cachedTurnCredentials = TurnCredentials(
                            turnUrls = turnMsg.turnUrls,
                            turnUrlsDualStack = turnMsg.turnUrlsDualStack,
                            turnUsername = turnMsg.turnUsername,
                            turnPassword = turnMsg.turnPassword,
                            expirationDate = expDate
                        )
                        Log.i(TAG, "Received new TURN credentials valid until $expDate")
                    } else {
                        Log.e(TAG, "Failed to fetch TURN credentials: ${turnMsg.error}")
                    }
                }
            }
        }
    }

    private fun handleIncomingSignallingMessage(msg: ThreemaCallMessage, data: Any) {
        val sender = msg.sender
        val messageType = msg.messageType
        val receivedCallId = (data as? VoipCallData<*>)?.callId
        Log.i(TAG, "[$sender] Received ${signallingName(messageType)} for call $receivedCallId (active ${stateMachine.activeCallId}, ${stateMachine.state}, elapsed=${elapsedSinceTap()}ms)")

        when (messageType) {
            0x60 -> { // Offer
                val offer = data as VoipCallOfferData
                val callId = offer.callId ?: return

                // Stale offer check (Section G.6)
                val timestampMs = if (msg.timestamp < 100_000_000_000L) msg.timestamp * 1000L else msg.timestamp
                val ageMs = System.currentTimeMillis() - timestampMs
                if (timestampMs > 0L && ageMs > STALE_OFFER_MAX_AGE_MS) {
                    Log.w(TAG, "[$sender] Stale call offer dropped (call $callId, age=${ageMs}ms > ${STALE_OFFER_MAX_AGE_MS}ms)")
                    return
                }

                // Telecom busy check: reject with BUSY if a telephone call is in progress (Section G.4)
                val ongoingCall = OngoingCall.current.value
                val isPhoneCallOngoing = ongoingCall != null && ongoingCall.stage != CallStage.OVER
                // The platform's own answer too, for a call this app is not tracking. Needs
                // READ_PHONE_STATE; without it, or on any error, only our own tracking counts —
                // never a guess that would turn every Threema call away as busy.
                val isTelephonyBusy = try {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                        PackageManager.PERMISSION_GRANTED &&
                        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.isInManagedCall == true
                } catch (e: Exception) {
                    false
                }
                if (isPhoneCallOngoing || isTelephonyBusy) {
                    Log.i(TAG, "[$sender] Incoming offer rejected: telephone call in progress (call $callId)")
                    val rejectAnswer = VoipCallAnswerData()
                        .setCallId(callId)
                        .setAction(VoipCallAnswerData.Action.REJECT)
                        .setRejectReason(VoipCallAnswerData.RejectReason.BUSY)
                    threemaClient.sendCallData(sender, 0x61, rejectAnswer)
                    return
                }

                val res = stateMachine.onIncomingOffer(callId, sender)
                if (res is StateTransitionResult.Rejected) {
                    Log.w(TAG, "[$sender] Rejecting incoming offer for call $callId: ${res.reason}")
                    val rejectAnswer = VoipCallAnswerData()
                        .setCallId(callId)
                        .setAction(VoipCallAnswerData.Action.REJECT)
                        .setRejectReason(VoipCallAnswerData.RejectReason.BUSY)
                    threemaClient.sendCallData(sender, 0x61, rejectAnswer)
                    return
                }

                _currentCallState.value = stateMachine.state
                pendingRemoteOffer = offer
                pendingRemoteIceCandidates.clear()

                // Resolve peer and check unknown caller blocking (Section G.5)
                scope.launch {
                    val resolved = withTimeoutOrNull(1500L) {
                        VoipPeerResolver.resolvePeer(context, sender)
                    } ?: ResolvedVoipPeer(displayName = sender, photoUri = null, isKnownContact = false)

                    val blockUnknown = try {
                        Messengers.settings(context).settings.value.blockUnknownThreemaCallers
                    } catch (e: Exception) {
                        true
                    }

                    if (blockUnknown && !resolved.isKnownContact) {
                        Log.i(TAG, "[$sender] Blocked call from unknown Threema caller (call $callId)")
                        val rejectAnswer = VoipCallAnswerData()
                            .setCallId(callId)
                            .setAction(VoipCallAnswerData.Action.REJECT)
                            .setRejectReason(VoipCallAnswerData.RejectReason.REJECTED)
                        threemaClient.sendCallData(sender, 0x61, rejectAnswer)
                        recordCaregiverEvent(CaregiverEventKind.THREEMA_CALL_FROM_STRANGER_ENDED, sender)
                        terminateCall(DisconnectCause.REJECTED)
                        return@launch
                    }

                    // Send ringing (0x64) back to caller promptly
                    val ringing = VoipCallRingingData().setCallId(callId)
                    threemaClient.sendCallData(sender, 0x64, ringing)

                    // Fetch TURN credentials
                    ensureTurnCredentials()

                    // Start incoming ringer (Section G.1)
                    incomingRinger.start(scope, sender)

                    // 45s incoming ringing timeout (Section G.2)
                    incomingTimeoutJob?.cancel()
                    incomingTimeoutJob = scope.launch {
                        delay(INCOMING_RINGING_TIMEOUT_MS)
                        if (stateMachine.activeCallId == callId && stateMachine.state == CallState.INCOMING_RINGING) {
                            Log.i(TAG, "[$sender] Incoming call $callId unanswered after 45s; rejecting with TIMEOUT")
                            val timeoutAnswer = VoipCallAnswerData()
                                .setCallId(callId)
                                .setAction(VoipCallAnswerData.Action.REJECT)
                                .setRejectReason(VoipCallAnswerData.RejectReason.TIMEOUT)
                            threemaClient.sendCallData(sender, 0x61, timeoutAnswer)
                            incomingRinger.stop()
                            terminateCall(DisconnectCause.MISSED)
                        }
                    }

                    phoneAccountHandle?.let { handle ->
                        GentleConnectionService.reportIncomingCall(
                            context = context,
                            handle = handle,
                            callId = callId,
                            peerIdentity = sender,
                            peerName = if (resolved.isKnownContact) resolved.displayName else null,
                            peerPhotoUri = resolved.photoUri
                        )
                    }
                }
            }
            0x61 -> { // Answer
                val answer = data as VoipCallAnswerData
                val callId = answer.callId ?: return
                // An answer about some other call — a late one, or one we already gave up on —
                // must neither stop this call's ringing nor end it.
                if (stateMachine.activeCallId != callId || stateMachine.state != CallState.OUTGOING_RINGING) {
                    Log.i(TAG, "[$sender] Ignoring answer for call $callId (active ${stateMachine.activeCallId}, ${stateMachine.state})")
                    return
                }
                if (answer.action == VoipCallAnswerData.Action.ACCEPT) {
                    ringingTimeoutJob?.cancel()
                    ringingTimeoutJob = null
                    stopWaitingForAnswer()
                    val res = stateMachine.onAnswer(callId)
                    if (res is StateTransitionResult.Success) {
                        _currentCallState.value = stateMachine.state
                        // Telecom has held this call as DIALING since it was placed, and its
                        // anomaly watchdog ends a call still dialling after two minutes. Without
                        // this, an answered call is cut off mid-conversation at 2:00 from the
                        // tap — seen on the Fold 2026-09-16, 1½ minutes into talking.
                        activeConnection?.let { conn ->
                            if (conn.state != android.telecom.Connection.STATE_ACTIVE) {
                                conn.setActive()
                            }
                        }
                        answer.answerData?.sdp?.let { sdpText ->
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpText)
                            peerConnectionClient?.setRemoteDescription(sdp)
                        }
                    }
                } else {
                    Log.i(TAG, "[$sender] Recipient rejected call $callId (action=${answer.action}, reason=${answer.rejectReason}, elapsed=${elapsedSinceTap()}ms)")
                    enterBusyEnding("recipient_rejected")
                }
            }
            0x62 -> { // ICE Candidates (Section G.3)
                val iceData = data as VoipICECandidatesData
                val callId = iceData.callId ?: return
                if (stateMachine.activeCallId == callId) {
                    iceData.candidates?.forEach { cand ->
                        // sdpMLineIndex is nullable on the wire (the sender may send JSON null).
                        // These calls are audio-only and BUNDLEd, so there is exactly one m-line
                        // and 0 is the only index it could refer to.
                        val iceCandidate = IceCandidate(
                            cand.sdpMid,
                            cand.sdpMLineIndex ?: 0,
                            cand.candidate
                        )
                        val pc = peerConnectionClient
                        if (pc != null) {
                            pc.addRemoteIceCandidate(iceCandidate)
                        } else {
                            synchronized(pendingRemoteIceCandidates) { pendingRemoteIceCandidates.add(iceCandidate) }
                        }
                    }
                }
            }
            0x63 -> { // Hangup
                val hangup = data as VoipCallHangupData
                val callId = hangup.callId ?: return
                if (stateMachine.activeCallId == callId) {
                    Log.i(TAG, "[$sender] Received remote hangup 0x63 for call $callId (elapsed=${elapsedSinceTap()}ms)")
                    if (stateMachine.state == CallState.INCOMING_RINGING) {
                        incomingRinger.stop()
                        incomingTimeoutJob?.cancel()
                        incomingTimeoutJob = null
                        terminateCall(DisconnectCause.MISSED)
                    } else {
                        enterBusyEnding("remote_hangup")
                    }
                }
            }
            0x64 -> { // Ringing
                val ringing = data as VoipCallRingingData
                val callId = ringing.callId ?: return
                Log.i(TAG, "[$sender] Remote ringing acknowledged (call $callId, elapsed=${elapsedSinceTap()}ms)")
                ringingTimeoutJob?.cancel()
                ringingTimeoutJob = null
                stateMachine.onRinging(callId)
                _currentCallState.value = stateMachine.state
            }
        }
    }

    /**
     * Ring somebody over Threema, from a face on her screen or on her watch.
     *
     * @return whether the call was started. False means nothing was rung and the caller should
     *   try another way — see [ch.heuscher.gentleconnect.data.CallPlacer].
     */
    fun startCall(recipient: String): Boolean {
        val cleanRecipient = recipient.trim().uppercase()
        if (cleanRecipient.isBlank()) return false

        // Before anything is sent. A call placed without a microphone connects and carries
        // silence, which from her side is the person she rang refusing to speak.
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            Log.e(TAG, "RECORD_AUDIO not granted; refusing to place a Threema call to $cleanRecipient")
            return false
        }

        val callId = Random.nextLong(1, MAX_CALL_ID)
        val res = stateMachine.onOutgoingOffer(callId, cleanRecipient)
        if (res is StateTransitionResult.Rejected) {
            Log.w(TAG, "Rejecting outgoing call to $cleanRecipient: ${res.reason}")
            return false
        }

        callTapTimestamp = SystemClock.elapsedRealtime()
        Log.i(TAG, "[$cleanRecipient] Call tap registered (call $callId, elapsed=0ms)")

        // Synchronous state transition and audio start
        _currentCallState.value = stateMachine.state
        audioHandler.startCallAudio()

        // Immediate ringback tone, exactly like an old telephone exchange
        ringbackTone.start()
        Log.i(TAG, "[$cleanRecipient] Ringback tone started immediately (elapsed=${elapsedSinceTap()}ms)")

        // Outgoing unanswered timeout watchdog
        unansweredTimeoutJob?.cancel()
        unansweredTimeoutJob = scope.launch {
            delay(UNANSWERED_TIMEOUT_MS)
            if (stateMachine.activeCallId == callId && stateMachine.state == CallState.OUTGOING_RINGING) {
                Log.i(TAG, "[$cleanRecipient] Call $callId unanswered after ${UNANSWERED_TIMEOUT_MS / 1000}s; sending hangup and busy ending")
                val hangupData = VoipCallHangupData().setCallId(callId)
                threemaClient.sendCallData(cleanRecipient, 0x63, hangupData)
                enterBusyEnding("unanswered_timeout")
            }
        }

        scope.launch {
            // Quick peer resolution (timeout 500ms so Telecom handoff is never delayed)
            val resolved = withTimeoutOrNull(500L) {
                VoipPeerResolver.resolvePeer(context, cleanRecipient)
            } ?: ResolvedVoipPeer(displayName = cleanRecipient, photoUri = null, isKnownContact = false)

            // Immediate Telecom handoff before waiting for TURN or network setup
            phoneAccountHandle?.let { handle ->
                try {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val callExtras = Bundle().apply {
                        putLong("EXTRA_CALL_ID", callId)
                        putString("EXTRA_PEER_IDENTITY", cleanRecipient)
                        if (resolved.isKnownContact) {
                            putString("EXTRA_PEER_NAME", resolved.displayName)
                        }
                        resolved.photoUri?.let { putString("EXTRA_PEER_PHOTO_URI", it) }
                    }
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
                    }
                    val uri = Uri.fromParts("sip", cleanRecipient, null)
                    telecomManager?.placeCall(uri, extras)
                    Log.i(TAG, "[$cleanRecipient] Handed call to Telecom (elapsed=${elapsedSinceTap()}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to place outgoing call via Telecom", e)
                    enterBusyEnding("telecom_place_call_failed")
                    return@launch
                }
            }

            // Bridge connection watchdog: if bridge not connected within 10s of tap, trigger busy ending
            val isBridgeConnected = withTimeoutOrNull(BRIDGE_CONNECT_TIMEOUT_MS) {
                if (threemaClient.connectionState.value is ThreemaConnectionState.Connected) {
                    true
                } else {
                    threemaClient.connectionState.first { it is ThreemaConnectionState.Connected }
                    true
                }
            } ?: false

            if (!isBridgeConnected) {
                Log.e(TAG, "[$cleanRecipient] Bridge not connected within 10s of tap; triggering busy ending (elapsed=${elapsedSinceTap()}ms)")
                enterBusyEnding("bridge_connect_timeout")
                return@launch
            }

            // Await TURN credentials
            val creds = awaitTurnCredentials()
            if (creds == null) {
                Log.w(TAG, "[$cleanRecipient] Placing call without TURN credentials (elapsed=${elapsedSinceTap()}ms)")
            }

            val pcClient = PeerConnectionClient(context, this@VoipCallManager)
            peerConnectionClient = pcClient
            pcClient.createPeerConnection(creds ?: cachedTurnCredentials, isInitiator = true)
            pcClient.createOffer()
        }

        return true
    }

    /**
     * The TURN credentials, waiting for them to arrive if they are not already held.
     */
    private suspend fun awaitTurnCredentials(timeoutMs: Long = TURN_WAIT_MS): TurnCredentials? {
        cachedTurnCredentials?.let { if (it.isValid()) return it }
        return withTimeoutOrNull(timeoutMs) {
            var needToAsk = true
            var askedAt = 0L
            var creds = cachedTurnCredentials
            while (creds == null || !creds.isValid()) {
                if (threemaClient.connectionState.value !is ThreemaConnectionState.Connected) {
                    needToAsk = true
                } else if (needToAsk || SystemClock.elapsedRealtime() - askedAt >= TURN_REASK_MS) {
                    needToAsk = !threemaClient.requestTurnCredentials("voip")
                    askedAt = SystemClock.elapsedRealtime()
                }
                delay(100)
                creds = cachedTurnCredentials
            }
            creds
        }
    }

    fun answerCall(callId: Long) {
        val offer = pendingRemoteOffer ?: return
        val sender = stateMachine.peerIdentity ?: return
        if (stateMachine.activeCallId != callId) return

        incomingRinger.stop()
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null

        // Check runtime RECORD_AUDIO permission before answering
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordAudio) {
            Log.e(TAG, "[$sender] RECORD_AUDIO permission missing on answerCall for $callId. Rejecting call.")
            rejectCall(callId)
            return
        }

        activeConnection?.let { conn ->
            if (conn.state != android.telecom.Connection.STATE_ACTIVE) {
                conn.setActive()
            }
        }

        ensureTurnCredentials()

        audioHandler.startCallAudio()

        val pcClient = PeerConnectionClient(context, this)
        peerConnectionClient = pcClient
        pcClient.createPeerConnection(cachedTurnCredentials, isInitiator = false)

        offer.offerData?.sdp?.let { sdpText ->
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpText)
            pcClient.setRemoteDescription(sdp)
        }

        // Apply any cached remote ICE candidates (Section G.3)
        synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.forEach { candidate ->
                pcClient.addRemoteIceCandidate(candidate)
            }
            pendingRemoteIceCandidates.clear()
        }

        pcClient.createAnswer()
    }

    fun rejectCall(callId: Long) {
        val sender = stateMachine.peerIdentity ?: return
        incomingRinger.stop()
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null
        val rejectAnswer = VoipCallAnswerData()
            .setCallId(callId)
            .setAction(VoipCallAnswerData.Action.REJECT)
            .setRejectReason(VoipCallAnswerData.RejectReason.REJECTED)
        threemaClient.sendCallData(sender, 0x61, rejectAnswer)
        terminateCall(DisconnectCause.REJECTED)
    }

    fun hangupCall(callId: Long) {
        Log.i(TAG, "Local hangup requested for call $callId")
        busyTone.stop() // Immediate cancel of busy tone on explicit hangup tap
        val sender = stateMachine.peerIdentity
        if (sender != null && stateMachine.activeCallId == callId) {
            val hangupData = VoipCallHangupData().setCallId(callId)
            threemaClient.sendCallData(sender, 0x63, hangupData)
        }
        terminateCall(DisconnectCause.LOCAL)
    }

    /**
     * Triggers European busy cadence (Besetztzeichen) for 3.5 seconds before tearing down the call.
     * Keeps screen displaying "Call ended" during tone playback so she receives clear acoustic
     * and visual confirmation that the line is terminated.
     */
    fun enterBusyEnding(reason: String) {
        Log.i(TAG, "Entering busy ending (reason=$reason, elapsed=${elapsedSinceTap()}ms)")
        ringbackTone.stop()
        incomingRinger.stop()
        stopWaitingForAnswer()
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        dropoutGraceJob?.cancel()
        dropoutGraceJob = null
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null

        audioHandler.stopCallAudio()
        peerConnectionClient?.close()
        peerConnectionClient = null
        pendingRemoteOffer = null
        pendingRemoteIceCandidates.clear()

        val callId = stateMachine.activeCallId
        if (callId != null) {
            stateMachine.onHangup(callId)
        }
        _currentCallState.value = CallState.ENDED

        busyTone.start(scope, BusyTone.DURATION_MS) {
            terminateCall()
        }
    }

    private fun terminateCall(cause: Int = DisconnectCause.LOCAL) {
        val callId = stateMachine.activeCallId
        val peer = stateMachine.peerIdentity
        if (peer != null) {
            if (cause == DisconnectCause.MISSED) {
                ch.heuscher.gentleconnect.data.support.SupportManager.onCallMissed(context, peer)
            } else {
                ch.heuscher.gentleconnect.data.support.SupportManager.onCallEnded(context, peer)
            }
        }
        Log.i(TAG, "Terminating call ${callId ?: -1} in state ${stateMachine.state}, cause $cause (elapsed=${elapsedSinceTap()}ms)")
        busyTone.stop()
        ringbackTone.stop()
        incomingRinger.stop()
        stopWaitingForAnswer()
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        dropoutGraceJob?.cancel()
        dropoutGraceJob = null
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null

        if (callId != null) {
            stateMachine.onHangup(callId)
        }
        _currentCallState.value = stateMachine.state

        audioHandler.stopCallAudio()
        peerConnectionClient?.close()
        peerConnectionClient = null
        pendingRemoteOffer = null
        pendingRemoteIceCandidates.clear()

        activeConnection?.let { conn ->
            if (conn.state != android.telecom.Connection.STATE_DISCONNECTED) {
                conn.setDisconnected(DisconnectCause(cause))
            }
            conn.destroy()
            conn.cleanUp()
        }
        activeConnection = null

        stateMachine.reset()
        _currentCallState.value = stateMachine.state
        callTapTimestamp = 0L
    }

    /** The ringing is over, one way or the other: no more tone, no more timeout. */
    private fun stopWaitingForAnswer() {
        ringbackTone.stop()
        unansweredTimeoutJob?.cancel()
        unansweredTimeoutJob = null
    }

    private fun recordCaregiverEvent(kind: CaregiverEventKind, subject: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val repo = CaregiverLogRepository(AppDatabase.getInstance(context).caregiverEventDao())
                repo.record(CaregiverEvent(kind = kind, at = System.currentTimeMillis(), subject = subject))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record caregiver event $kind", e)
            }
        }
    }

    private fun signallingName(messageType: Int): String = when (messageType) {
        0x60 -> "offer"
        0x61 -> "answer"
        0x62 -> "ICE candidates"
        0x63 -> "hangup"
        0x64 -> "ringing"
        else -> "type 0x${messageType.toString(16)}"
    }

    private fun ensureTurnCredentials() {
        val creds = cachedTurnCredentials
        // An hour's margin, so credentials fetched on connecting are not ones about to lapse
        // under a call placed later.
        if (creds == null || !creds.isValid(marginSeconds = 3600)) {
            threemaClient.requestTurnCredentials("voip")
        }
    }

    // PeerConnectionEvents Implementation
    override fun onLocalDescription(sdp: SessionDescription) {
        val callId = stateMachine.activeCallId ?: return
        val peer = stateMachine.peerIdentity ?: return

        if (sdp.type == SessionDescription.Type.ANSWER) {
            val answerData = VoipCallAnswerData.AnswerData()
                .setSdpType("answer")
                .setSdp(sdp.description)
            val callAnswer = VoipCallAnswerData()
                .setCallId(callId)
                .setAction(VoipCallAnswerData.Action.ACCEPT)
                .setAnswerData(answerData)
            threemaClient.sendCallData(peer, 0x61, callAnswer)
        } else if (sdp.type == SessionDescription.Type.OFFER) {
            val offerData = VoipCallOfferData.OfferData()
                .setSdpType("offer")
                .setSdp(sdp.description)
            val callOffer = VoipCallOfferData()
                .setCallId(callId)
                .setOfferData(offerData)
            val sent = threemaClient.sendCallData(peer, 0x60, callOffer)
            Log.i(TAG, "[$peer] Sent call offer 0x60 (success=$sent, elapsed=${elapsedSinceTap()}ms)")
            if (!sent) {
                enterBusyEnding("offer_send_failed")
                return
            }

            // 20-second ringing timer: if 0x64 not received within 20s of offer, send 0x63 and trigger busy ending
            ringingTimeoutJob?.cancel()
            ringingTimeoutJob = scope.launch {
                delay(RINGING_TIMEOUT_MS)
                if (stateMachine.activeCallId == callId && stateMachine.state == CallState.OUTGOING_RINGING) {
                    Log.w(TAG, "[$peer] No ringing received within 20s of offer; sending hangup and busy ending (elapsed=${elapsedSinceTap()}ms)")
                    val hangupData = VoipCallHangupData().setCallId(callId)
                    threemaClient.sendCallData(peer, 0x63, hangupData)
                    enterBusyEnding("ringing_timeout_20s")
                }
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        val callId = stateMachine.activeCallId ?: return
        val peer = stateMachine.peerIdentity ?: return
        val cand = VoipICECandidatesData.Candidate(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            ufrag = null
        )
        val iceData = VoipICECandidatesData()
            .setCallId(callId)
            .setCandidates(arrayOf(cand))
        threemaClient.sendCallData(peer, 0x62, iceData)
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

    override fun onIceConnected() {
        val callId = stateMachine.activeCallId ?: return
        if (dropoutGraceJob != null) {
            val recoveredMs = SystemClock.elapsedRealtime() - dropoutStartedAt
            Log.i(TAG, "ICE reconnected within grace period: ${recoveredMs}ms (call $callId)")
            dropoutGraceJob?.cancel()
            dropoutGraceJob = null
            dropoutStartedAt = 0L
            return
        }

        stateMachine.onConnected(callId)
        _currentCallState.value = stateMachine.state
        stopWaitingForAnswer()
        activeConnection?.let { conn ->
            if (conn.state != android.telecom.Connection.STATE_ACTIVE) {
                conn.setActive()
            }
        }
        stateMachine.peerIdentity?.let { peer ->
            ch.heuscher.gentleconnect.data.support.SupportManager.onCallConnected(context, peer)
        }
        Log.i(TAG, "ICE Connected successfully for call $callId (elapsed=${elapsedSinceTap()}ms)")
    }

    /**
     * 4-second silent dropout grace (Section D):
     * Cellular and Wi-Fi handovers frequently cause momentary ICE disconnections lasting 1-3s.
     * Rather than abruptly terminating the call, we wait 4s. If ICE recovers, conversation
     * resumes seamlessly. If the 4s expires without recovery, busy ending is triggered.
     */
    override fun onIceDisconnected() {
        Log.w(TAG, "ICE Disconnected (state=${stateMachine.state})")
        if (stateMachine.state == CallState.CONNECTED) {
            if (dropoutGraceJob == null) {
                dropoutStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Starting 4s dropout grace timer for ICE disconnect")
                dropoutGraceJob = scope.launch {
                    delay(DROPOUT_GRACE_MS)
                    Log.w(TAG, "4s dropout grace expired without ICE recovery; ending call with busy tone")
                    dropoutGraceJob = null
                    enterBusyEnding("ice_dropout_grace_expired")
                }
            }
        }
    }

    override fun onPeerConnectionClosed() {}

    override fun onPeerConnectionError(description: String) {
        Log.e(TAG, "PeerConnection Error: $description (elapsed=${elapsedSinceTap()}ms)")
        enterBusyEnding("peer_connection_error")
    }

    companion object {
        private const val TAG = "VoipCallManager"

        /**
         * One past the largest call id the protocol can carry.
         *
         * A call id is an unsigned 32-bit integer on the wire, and
         * [ch.heuscher.gentleconnect.voip.signalling.VoipCallData.setCallId] does not round a
         * larger one down — it throws. Drawing from the whole of `Long` would therefore throw on
         * essentially every outgoing call, at the point the offer is built, inside the coroutine
         * that was placing it: no call, no screen, and an exception nobody is watching for.
         */
        private const val MAX_CALL_ID = 1L shl 32

        /**
         * How long an outgoing call rings before we give up on it: 1:45. It must stay under
         * Telecom's two minutes, after which its watchdog ends the call itself, as an error and
         * without telling the far end.
         */
        private const val UNANSWERED_TIMEOUT_MS = 105_000L

        /** How long placing a call waits for relay credentials, across a bridge restart. */
        private const val TURN_WAIT_MS = 6_000L

        /** Ask again if a request for credentials has gone this long unanswered. */
        private const val TURN_REASK_MS = 2_000L

        /** Watchdog timeout waiting for bridge to become connected after call tap. */
        private const val BRIDGE_CONNECT_TIMEOUT_MS = 10_000L

        /** 20-second ringing timer: if 0x64 is not received within 20s of offer. */
        private const val RINGING_TIMEOUT_MS = 20_000L

        /** 4-second silent dropout grace period on ICE disconnect during an active call. */
        private const val DROPOUT_GRACE_MS = 4_000L

        /** Max age of incoming offer before it is dropped as stale. */
        private const val STALE_OFFER_MAX_AGE_MS = 60_000L

        /** Incoming ringing timeout before rejecting with TIMEOUT. */
        private const val INCOMING_RINGING_TIMEOUT_MS = 45_000L
    }
}
