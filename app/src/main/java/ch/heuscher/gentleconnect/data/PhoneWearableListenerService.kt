package ch.heuscher.gentleconnect.data

import android.content.Intent
import android.util.Log
import ch.heuscher.gentleconnect.FindPhoneActivity
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * The phone's ear for the watch.
 *
 * Two things can be asked of the phone from her wrist, and they are the two things a watch is
 * good for:
 *
 * - **Ring somebody.** The watch names a person; the phone decides how to reach them, through
 *   [CallPlacer], exactly as it would if the same face had been pressed on the home screen.
 *   Whether the call goes over Threema depends on whether the phone is roaming, and the watch
 *   has no way to know that — so it does not decide.
 * - **Where is the phone?** She puts it down. See [FindPhoneActivity].
 *
 * Nothing here can answer, reject, mute or hang up a call. That needs the app to be the
 * telephone's dialler, which this app deliberately is not: it is a launcher that places calls
 * and lets the telephone's own dialler carry them. Simple Phone does the other thing, and its
 * watch app relays all of that; this one cannot and should not pretend to.
 *
 * Instantiated by Google Play Services when a message arrives, and killed again shortly after —
 * so nothing here may hold state, and nothing may take long.
 */
class PhoneWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            WearMessages.CALL_PATH -> ring(String(messageEvent.data, Charsets.UTF_8), messageEvent.sourceNodeId)
            WearMessages.FIND_PHONE_PATH -> findPhone()
        }
    }

    private fun ring(payload: String, sourceNodeId: String) {
        val (number, threemaId) = WearMessages.readCallPayload(payload)
        if (number == null && threemaId == null) {
            Log.w(TAG, "The watch asked for a call with nobody in it")
            notifyWatchCallFailed(sourceNodeId)
            return
        }

        CallPlacer.place(
            context = applicationContext,
            phoneNumber = number,
            threemaId = threemaId,
            // Nobody is looking at the phone — it is in her cardigan pocket or on the kitchen
            // table. Saying anything aloud or putting a message on its screen would be talking
            // to an empty room, so the phone stays quiet and sends CALL_FAILED_PATH to the watch
            // so her wrist vibrates twice.
            onFailed = { failure ->
                Log.w(TAG, "The watch asked for a call that could not be placed ($failure)")
                notifyWatchCallFailed(sourceNodeId)
            }
        )
    }

    private fun notifyWatchCallFailed(sourceNodeId: String) {
        try {
            val client = com.google.android.gms.wearable.Wearable.getMessageClient(this)
            client.sendMessage(sourceNodeId, WearMessages.CALL_FAILED_PATH, ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send call failed feedback to watch", e)
        }
    }

    private fun findPhone() {
        try {
            startActivity(
                Intent(this, FindPhoneActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not make the phone announce itself", e)
        }
    }

    private companion object {
        const val TAG = "WearListener"
    }
}
