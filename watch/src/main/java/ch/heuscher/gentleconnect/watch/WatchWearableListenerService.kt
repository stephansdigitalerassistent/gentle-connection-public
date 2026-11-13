package ch.heuscher.gentleconnect.watch

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives events from the phone on the watch.
 *
 * When a call tap initiated from the watch fails on the phone (such as being abroad without
 * Threema, having no cellular or data connection, or missing a valid contact identity), the
 * phone reports the failure back to the watch over [WearMessages.CALL_FAILED_PATH].
 *
 * Rather than playing sounds or speaking aloud from a phone that might be sitting in another room,
 * the watch buzzes twice distinctly on her wrist. This haptic feedback is gentle, private, and
 * directly informs her that the call could not be placed without causing alarm or confusion.
 */
class WatchWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            WearMessages.CALL_FAILED_PATH -> vibrateTwice()
        }
    }

    private fun vibrateTwice() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                // Two distinct pulses: silence 0ms, buzz 200ms, pause 150ms, buzz 200ms, repeat index -1 (do not loop).
                val pattern = longArrayOf(0, 200, 150, 200)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate watch for call failure feedback", e)
        }
    }

    private companion object {
        const val TAG = "WatchListener"
    }
}
