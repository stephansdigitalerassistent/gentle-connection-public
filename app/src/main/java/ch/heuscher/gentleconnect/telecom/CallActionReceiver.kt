package ch.heuscher.gentleconnect.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.heuscher.gentleconnect.data.AppGraph

/**
 * Answer and hang up, from the notification rather than from the screen.
 *
 * The screen is the way she does it. This is the other way in: the two buttons Android puts on the
 * lock screen and in the shade for a call notification, which is where a caregiver on the other
 * side of the room ends up pressing, and where the phone itself falls back to if the full-screen
 * call display is ever suppressed.
 *
 * It does nothing but forward to [OngoingCall], so there is exactly one implementation of what
 * answering means — the same one the green circle uses.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> OngoingCall.answer()
            ACTION_HANG_UP -> OngoingCall.hangUp()
            ACTION_CALL_BACK -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                if (!number.isNullOrBlank()) {
                    ch.heuscher.gentleconnect.data.CallPlacer.place(
                        context = context,
                        phoneNumber = number,
                        threemaId = null,
                        onFailed = { failure ->
                            val resId = when (failure) {
                                ch.heuscher.gentleconnect.data.CallFailure.NOT_FROM_ABROAD ->
                                    ch.heuscher.gentleconnect.R.string.call_failed_not_from_abroad
                                ch.heuscher.gentleconnect.data.CallFailure.NO_CONNECTION ->
                                    ch.heuscher.gentleconnect.R.string.call_failed_no_connection
                                ch.heuscher.gentleconnect.data.CallFailure.NOTHING_TO_CALL ->
                                    ch.heuscher.gentleconnect.R.string.call_not_possible
                            }
                            ch.heuscher.gentleconnect.voip.CallFailedActivity.start(
                                context = context,
                                messageResId = resId
                            )
                        }
                    )
                }
                androidx.core.app.NotificationManagerCompat.from(context).cancel(MISSED_CALL_NOTIFICATION)
                rememberSheDealtWithIt(context, intent.getLongExtra(EXTRA_MISSED_AT, 0L))
            }
            ACTION_DISMISS_MISSED_CALL -> {
                androidx.core.app.NotificationManagerCompat.from(context).cancel(MISSED_CALL_NOTIFICATION)
            }
        }
    }

    /**
     * Record that the call missed at [callAt] has been dealt with.
     *
     * The card at the top of her home screen is taken away by the same memory the "Call back"
     * button inside the app writes — see [ch.heuscher.gentleconnect.model.MissedCallNotice]. It
     * was not written from here, so calling her daughter back from the notice left the card up
     * afterwards, still telling her about the call she had just returned: the exact behaviour that
     * rule was written to stop, surviving in the one path that goes through a receiver.
     *
     * Nothing to do when the moment is missing, which is every notice posted by a version of the
     * app older than this one. Zero would move nothing anyway; returning here says so out loud.
     *
     * A small shared-preferences write, which is why it is done in place rather than handed to a
     * coroutine — a receiver that finishes before its own background work has run is a receiver
     * whose process Android may stop in the middle of it.
     */
    private fun rememberSheDealtWithIt(context: Context, callAt: Long) {
        if (callAt <= 0L) return
        AppGraph.nudgeMemory(context).dealtWithMissedCall(callAt)
    }

    companion object {
        const val ACTION_ANSWER = "ch.heuscher.gentleconnect.ANSWER_CALL"
        const val ACTION_HANG_UP = "ch.heuscher.gentleconnect.HANG_UP_CALL"
        const val ACTION_CALL_BACK = "ch.heuscher.gentleconnect.CALL_BACK"
        const val ACTION_DISMISS_MISSED_CALL = "ch.heuscher.gentleconnect.DISMISS_MISSED_CALL"
        const val EXTRA_NUMBER = "number"

        /** When the call she is answering was missed, so the card about it can be taken away. */
        const val EXTRA_MISSED_AT = "missed_at"
        const val MISSED_CALL_NOTIFICATION = 4714
    }
}
