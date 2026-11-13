package ch.heuscher.gentleconnect.voip

import ch.heuscher.gentleconnect.ui.HiddenNavigationBar
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.support.SupportManager
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.telecom.CallAudio
import ch.heuscher.gentleconnect.ui.GentleCallScreen
import ch.heuscher.gentleconnect.ui.components.SupportConfirmDialog
import ch.heuscher.gentleconnect.ui.theme.GentleTheme
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * A Threema call, answerable from the lock screen without unlocking.
 *
 * It draws [GentleCallScreen], the same one an ordinary telephone call gets — see
 * `telecom.PhoneCallActivity`. To an elderly user, a call is a call. There is no distinction
 * between VoIP and cellular calls: both have the exact same interface, ringer behaviour,
 * mute/silence controls, and tactile feedback.
 */
class CallActivity : ComponentActivity() {

    private var callId: Long = -1L
    private var peerIdentity: String = ""
    private var peerName: String? = null
    private var peerPhotoUri: String? = null

    // No Android buttons under a call either — the same as everywhere else on her phone.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) HiddenNavigationBar.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnAndKeyguardOff()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        callId = intent.getLongExtra("EXTRA_CALL_ID", -1L)
        peerIdentity = intent.getStringExtra("EXTRA_PEER_IDENTITY") ?: ""
        peerName = intent.getStringExtra("EXTRA_PEER_NAME")?.ifBlank { null }
        peerPhotoUri = intent.getStringExtra("EXTRA_PEER_PHOTO_URI")?.ifBlank { null }

        setContent {
            GentleTheme {
                val manager = GentleConnectionService.activeManager
                if (manager == null) {
                    // Nothing is holding a call, so there is nothing to show and nothing that
                    // pressing anything could do.
                    finish()
                    return@GentleTheme
                }

                val supportManager = remember { SupportManager.getInstance(applicationContext) }
                val supportButtonState by supportManager.buttonState.collectAsState()
                val supportMembers by supportManager.members.collectAsState()
                var showSupportConfirm by remember { mutableStateOf(false) }

                val onSupportPressed = {
                    when (supportButtonState) {
                        is SupportButtonState.Idle -> {
                            UiTrace.event("support dialog shown")
                            showSupportConfirm = true
                        }
                        // The tile already shows the green tick confirming that help was requested.
                        // The status screen lives in MainActivity, and an active call must not be
                        // left or interrupted to view it.
                        is SupportButtonState.Sent, is SupportButtonState.Claimed -> {}
                        SupportButtonState.None -> {}
                    }
                }

                val state by manager.currentCallState.collectAsState()
                val audioRoute by CallAudio.current.collectAsState()
                val offersRouteChoice by CallAudio.offersChoice.collectAsState()

                LaunchedEffect(state) {
                    // Stay visible during CallState.ENDED while the 3.5s busy tone plays so she
                    // sees "Call ended", then finish when the call transitions back to IDLE.
                    if (state == CallState.IDLE) {
                        finish()
                    }
                }

                val isKnownContact = !peerName.isNullOrBlank()
                val displayName = if (isKnownContact) {
                    PersonName.forHer(peerName) ?: stringResource(R.string.person_unknown)
                } else {
                    stringResource(R.string.person_unknown)
                }

                GentleCallScreen(
                    who = displayName,
                    photoUri = peerPhotoUri,
                    status = when (state) {
                        CallState.INCOMING_RINGING -> stringResource(R.string.call_incoming)
                        CallState.OUTGOING_RINGING -> stringResource(R.string.call_outgoing)
                        CallState.CONNECTING -> stringResource(R.string.call_connecting)
                        CallState.CONNECTED -> stringResource(R.string.call_active)
                        CallState.ENDED -> stringResource(R.string.call_ended)
                        CallState.IDLE -> ""
                    },
                    canAnswer = state == CallState.INCOMING_RINGING,
                    onAnswer = { manager.answerCall(callId) },
                    onHangUp = { manager.hangupCall(callId) },
                    askBeforeHangingUp = state == CallState.CONNECTED,
                    audioRoute = audioRoute,
                    onChooseRoute = if (offersRouteChoice) CallAudio::choose else null,
                    isKnownContact = isKnownContact,
                    canSilence = state == CallState.INCOMING_RINGING,
                    onSilence = { manager.silenceIncomingRinging() },
                    support = supportButtonState,
                    onSupportPressed = onSupportPressed
                )

                if (showSupportConfirm) {
                    SupportConfirmDialog(
                        members = supportMembers,
                        onConfirm = {
                            UiTrace.event("support dialog confirmed")
                            showSupportConfirm = false
                            supportManager.triggerSupportRequest("Call")
                        },
                        onDismiss = {
                            UiTrace.event("support dialog dismissed")
                            showSupportConfirm = false
                        },
                        onTimeout = {
                            UiTrace.event("support dialog timed out")
                            showSupportConfirm = false
                            supportManager.logSupportOpenedNotConfirmed()
                        }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val manager = GentleConnectionService.activeManager
            if (manager?.currentCallState?.value == CallState.INCOMING_RINGING) {
                manager.silenceIncomingRinging()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun turnScreenOnAndKeyguardOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }
}
