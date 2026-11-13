package ch.heuscher.gentleconnect.voip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import ch.heuscher.gentleconnect.telecom.CallAudioRouting
import ch.heuscher.gentleconnect.telecom.toPlatformRoute

/**
 * [Connection] implementation bridging Android Telecom framework events to [VoipCallManager].
 */
class GentleCallConnection(
    private val callManager: VoipCallManager,
    val callId: Long,
    val peerIdentity: String
) : Connection() {

    private val audioRouting = CallAudioRouting(
        context = callManager.context,
        audioState = { callAudioState },
        setRoute = { setAudioRoute(it.toPlatformRoute()) }
    )

    /** See [beginAudioHandlingUnlessRinging]: a ringing call is left entirely alone. */
    private var audioHandlingStarted = false

    init {
        // Must match the SELF_MANAGED phone account registered in VoipCallManager. Telecom
        // rejects a connection whose properties disagree with its account, so leaving this
        // off would fail every incoming call at the point it should start ringing.
        connectionProperties = PROPERTY_SELF_MANAGED
        // No hold: her call screen has exactly two controls, so there is no way to resume a
        // held call and advertising it would let Telecom put one somewhere she cannot reach.
        audioModeIsVoip = true
    }

    /**
     * Start deciding where the sound goes, once this call is no longer ringing at her end.
     *
     * The same reasoning as the ordinary telephone's
     * [ch.heuscher.gentleconnect.telecom.GentlePhoneCallService], and it matters just as much
     * here: routing a ringing call can move the ringing itself onto a paired Bluetooth headset
     * that is lying in a drawer, and [ch.heuscher.gentleconnect.telecom.EarProximity]'s screen-off
     * wake lock would blank the call screen the full-screen intent just woke, because a phone face
     * down on a table reads as being against an ear.
     *
     * Safe to call repeatedly.
     */
    private fun beginAudioHandlingUnlessRinging(state: Int) {
        if (audioHandlingStarted) return
        if (state == Connection.STATE_INITIALIZING ||
            state == Connection.STATE_NEW ||
            state == Connection.STATE_RINGING
        ) {
            return
        }

        audioHandlingStarted = true
        audioRouting.start()
    }

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        if (state == null) return
        audioRouting.onAudioStateChanged(state)
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        beginAudioHandlingUnlessRinging(state)
    }

    internal fun cleanUp() {
        audioHandlingStarted = false
        audioRouting.stop()
        if (callManager.activeConnection === this) {
            callManager.activeConnection = null
        }
    }

    override fun onAnswer() {
        Log.i("GentleCallConnection", "Telecom onAnswer triggered for call $callId")
        setActive()
        beginAudioHandlingUnlessRinging(state)
        callManager.answerCall(callId)
    }

    override fun onReject() {
        Log.i("GentleCallConnection", "Telecom onReject triggered for call $callId")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        cleanUp()
        callManager.rejectCall(callId)
    }

    override fun onDisconnect() {
        Log.i("GentleCallConnection", "Telecom onDisconnect triggered for call $callId")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        cleanUp()
        callManager.hangupCall(callId)
    }

    override fun onAbort() {
        super.onAbort()
        cleanUp()
    }
}

/**
 * Android Telecom [ConnectionService] managing lock-screen ringing and incoming/outgoing call integration.
 */
class GentleConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "GentleConnectionService"
        var activeManager: VoipCallManager? = null

        fun reportIncomingCall(
            context: Context,
            handle: PhoneAccountHandle,
            callId: Long,
            peerIdentity: String,
            peerName: String? = null,
            peerPhotoUri: String? = null
        ) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle().apply {
                putLong("EXTRA_CALL_ID", callId)
                putString("EXTRA_PEER_IDENTITY", peerIdentity)
                peerName?.let { putString("EXTRA_PEER_NAME", it) }
                peerPhotoUri?.let { putString("EXTRA_PEER_PHOTO_URI", it) }
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("sip", peerIdentity, null))
            }
            try {
                telecomManager.addNewIncomingCall(handle, extras)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add new incoming call to TelecomManager", e)
            }
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callId = extras?.getLong("EXTRA_CALL_ID") ?: -1L
        val peerIdentity = extras?.getString("EXTRA_PEER_IDENTITY") ?: "Unknown"
        val peerName = extras?.getString("EXTRA_PEER_NAME")
        val peerPhotoUri = extras?.getString("EXTRA_PEER_PHOTO_URI")

        val manager = activeManager ?: return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR, "CallManager not initialized")
        )

        if (manager.stateMachine.activeCallId != callId || manager.stateMachine.state != CallState.INCOMING_RINGING) {
            Log.w(TAG, "Incoming call $callId already terminated or state mismatch (${manager.stateMachine.state})")
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.MISSED, "Call already ended")
            )
        }

        val connection = GentleCallConnection(manager, callId, peerIdentity)
        manager.activeConnection = connection
        connection.setRinging()

        // Launch call activity over lock screen
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_PEER_IDENTITY", peerIdentity)
            putExtra("EXTRA_PEER_NAME", peerName)
            putExtra("EXTRA_PEER_PHOTO_URI", peerPhotoUri)
            putExtra("EXTRA_IS_INCOMING", true)
        }
        startActivity(intent)

        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        // Either shape. Telecom is documented to carry an app's own extras for an outgoing call
        // inside EXTRA_OUTGOING_CALL_EXTRAS, and is observed on some versions to pass the whole
        // placeCall bundle through as well. Reading only one of the two would work on some
        // phones and fail every outgoing call on others, with an empty screen as the symptom;
        // this phone is the one that matters and it can only be checked on it.
        val requestExtras = request?.extras
        val extras = requestExtras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
            ?.takeIf { it.containsKey("EXTRA_CALL_ID") }
            ?: requestExtras
        val callId = extras?.getLong("EXTRA_CALL_ID") ?: -1L
        val peerIdentity = extras?.getString("EXTRA_PEER_IDENTITY") ?: "Unknown"
        val peerName = extras?.getString("EXTRA_PEER_NAME")
        val peerPhotoUri = extras?.getString("EXTRA_PEER_PHOTO_URI")

        val manager = activeManager ?: return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR, "CallManager not initialized")
        )

        if (manager.stateMachine.activeCallId != callId || manager.stateMachine.state != CallState.OUTGOING_RINGING) {
            Log.w(TAG, "Outgoing call $callId already terminated or state mismatch (${manager.stateMachine.state})")
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.CANCELED, "Call already ended")
            )
        }

        val connection = GentleCallConnection(manager, callId, peerIdentity)
        manager.activeConnection = connection
        connection.setDialing()

        // Launch call activity over lock screen
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_PEER_IDENTITY", peerIdentity)
            putExtra("EXTRA_PEER_NAME", peerName)
            putExtra("EXTRA_PEER_PHOTO_URI", peerPhotoUri)
            putExtra("EXTRA_IS_INCOMING", false)
        }
        startActivity(intent)

        return connection
    }
}
