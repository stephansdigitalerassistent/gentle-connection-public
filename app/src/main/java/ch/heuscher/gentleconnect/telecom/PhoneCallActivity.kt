package ch.heuscher.gentleconnect.telecom

import ch.heuscher.gentleconnect.ui.HiddenNavigationBar
import android.os.Build
import android.os.Bundle
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
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.GentleCallScreen
import ch.heuscher.gentleconnect.ui.components.SupportConfirmDialog
import ch.heuscher.gentleconnect.ui.theme.GentleTheme
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * The screen for an ordinary telephone call, now that this app is the phone app.
 *
 * Shown over the lock screen and it turns the screen on itself, because the whole event is one she
 * is not expecting: the phone is on a table, face down as often as not, and a call she has to
 * unlock the phone to answer is a call she will not answer. That is the same reasoning as
 * `FindPhoneActivity`, and the same reasoning as the Threema call screen.
 *
 * It draws [GentleCallScreen] and nothing else. Everything about how a call *looks* lives there,
 * so that a call over the telephone network and a call over Threema are one thing to learn.
 */
class PhoneCallActivity : ComponentActivity() {

    // No Android buttons under a call either — the same as everywhere else on her phone.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) HiddenNavigationBar.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverTheLockScreen()

        // Back does nothing here, and that is not a nicety: on a phone held to an ear the back
        // gesture is an edge swipe that happens by accident, and dismissing this screen
        // mid-conversation would leave her talking to somebody with no visible way to hang up.
        // The red circle is the only way out.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            GentleTheme {
                val call by OngoingCall.current.collectAsState()
                val audioRoute by CallAudio.current.collectAsState()
                val offersRouteChoice by CallAudio.offersChoice.collectAsState()

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

                // The call being gone is the only way off this screen. Pressing the red circle
                // does not finish the activity itself — it tells Telecom to end the call, which
                // comes back through here as null. One route out, so a screen can never be left
                // over a call that is still running, nor a call left running behind a screen that
                // has gone.
                LaunchedEffect(call) {
                    if (call == null) finish()
                }

                val here = call

                // The ringing screen waits until the address book has been asked. A caller she
                // knows gets an enlarged green circle and a grey Stumm one where an unknown
                // caller gets the ordinary red Auflegen, and the lookup settles a moment after
                // the call arrives. Drawing first and rearranging afterwards would change the
                // size, the colour and the meaning of the circle she is already reaching for —
                // the one moment in this app where a button must not move. The service settles
                // the question on a deadline, so this wait always ends.
                //
                // Only for an incoming call that is still ringing: an outgoing call has no such
                // pair of layouts to choose between, and holding that screen back would be a
                // delay bought for nothing.
                val waitingToKnowWho = here != null &&
                    here.isIncoming &&
                    here.stage == CallStage.RINGING &&
                    !here.identified

                if (here != null && !waitingToKnowWho) {
                    GentleCallScreen(
                        who = here.whoToShow(stringResource(R.string.call_unknown_caller)),
                        photoUri = here.photoUri,
                        status = stringResource(
                            when (here.stage) {
                                CallStage.RINGING ->
                                    if (here.isIncoming) R.string.call_incoming else R.string.call_outgoing
                                CallStage.DIALLING -> R.string.call_outgoing
                                CallStage.TALKING -> R.string.call_active
                                CallStage.OVER -> R.string.call_ended
                            }
                        ),
                        canAnswer = here.canAnswer,
                        onAnswer = { OngoingCall.answer() },
                        onHangUp = { OngoingCall.hangUp() },
                        askBeforeHangingUp = here.stage == CallStage.TALKING,
                        audioRoute = audioRoute,
                        onChooseRoute = if (offersRouteChoice) CallAudio::choose else null,
                        isKnownContact = here.name != null,
                        onSilence = { OngoingCall.silence() },
                        support = supportButtonState,
                        onSupportPressed = onSupportPressed
                    )
                }

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

    private fun showOverTheLockScreen() {
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

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val call = OngoingCall.current.value
        if (call != null && call.isIncoming && call.stage == CallStage.RINGING) {
            OngoingCall.silence()
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val call = OngoingCall.current.value
            if (call != null && call.isIncoming && call.stage == CallStage.RINGING) {
                OngoingCall.silence()
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
