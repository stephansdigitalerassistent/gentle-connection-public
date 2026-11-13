package ch.heuscher.gentleconnect.voip

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import ch.heuscher.gentleconnect.telecom.RingerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles acoustic ringtones and haptic vibration for incoming VoIP calls.
 *
 * Self-managed Telecom calls do not trigger the platform's default ringer, so the app
 * must produce the ringtone and vibration itself. To behave identically to an ordinary
 * incoming telephone call, this ringer strictly adheres to:
 * - Do Not Disturb (DND) settings ([NotificationManager.currentInterruptionFilter])
 * - The device's ringer mode (Normal, Vibrate, Silent) via [AudioManager.ringerMode]
 * - The caregiver's automatic silence timeout via [RingerSettings.getSilenceTimeoutSeconds]
 * - Manual silencing (via volume keys or screen buttons) without dropping the incoming call
 */
class IncomingRinger(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var silenceJob: Job? = null
    private var isSilenced: Boolean = false

    /**
     * Starts ringing and vibrating according to system audio policies and caregiver settings.
     *
     * @param scope coroutine scope for the automatic silence timeout timer
     */
    @Synchronized
    fun start(scope: CoroutineScope, callerThreemaId: String? = null) {
        stop()
        isSilenced = false

        val isRingThrough = callerThreemaId != null &&
            ch.heuscher.gentleconnect.data.support.SupportManager.isRingThroughActiveForThreema(context, callerThreemaId)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val isDndMuted = notificationManager?.let {
            val filter = it.currentInterruptionFilter
            filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } ?: false

        if (isDndMuted && !isRingThrough) {
            Log.i(TAG, "Incoming ringer suppressed by Do Not Disturb filter")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
                if (!isRingThrough) {
                    Log.i(TAG, "Incoming ringer silent due to RINGER_MODE_SILENT")
                    return
                } else {
                    startRingtone()
                    startVibration()
                }
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                if (isRingThrough) {
                    startRingtone()
                }
                startVibration()
            }
            AudioManager.RINGER_MODE_NORMAL -> {
                startRingtone()
                startVibration()
            }
        }

        val timeoutSeconds = RingerSettings.getSilenceTimeoutSeconds(context)
        if (timeoutSeconds > 0 && !isRingThrough) {
            silenceJob = scope.launch {
                delay(timeoutSeconds * 1000L)
                silence()
            }
        }
    }

    /**
     * Silences both ringtone and vibration without hanging up or rejecting the call.
     * Called when the user presses volume keys or taps the silence control on screen.
     */
    @Synchronized
    fun silence() {
        if (isSilenced) return
        isSilenced = true
        silenceJob?.cancel()
        silenceJob = null

        stopRingtone()
        stopVibration()
        Log.i(TAG, "Incoming ringer silenced")
    }

    /**
     * Stops all ringer playback, vibration, and timers completely.
     */
    @Synchronized
    fun stop() {
        silenceJob?.cancel()
        silenceJob = null

        stopRingtone()
        stopVibration()
        ringtone = null
        vibrator = null
        isSilenced = false
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(context, uri) ?: return

            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
            }

            r.play()
            ringtone = r
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start incoming ringtone", e)
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ringtone", e)
        }
    }

    private fun startVibration() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
            vibrator = v
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start vibration for incoming call", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping vibration", e)
        }
    }

    companion object {
        private const val TAG = "IncomingRinger"
    }
}
