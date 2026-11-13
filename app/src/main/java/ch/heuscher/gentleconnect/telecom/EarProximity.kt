package ch.heuscher.gentleconnect.telecom

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Turns the screen off while the phone is against her ear, so her cheek cannot press anything.
 *
 * Used by both ordinary cellular calls ([GentlePhoneCallService]) and Threema VoIP calls
 * ([ch.heuscher.gentleconnect.voip.GentleCallConnection]), through [CallAudioRouting].
 *
 * Only while the sound comes out of the earpiece, which is the only time the phone is meant to be at
 * her ear. On the loudspeaker the screen stays on whatever the sensor says: that is when she is
 * looking at it, and a thumb over the sensor, or a phone lying face up and still reading *near*,
 * must not take away the buttons she needs, including the one that brings the sound back to the
 * earpiece.
 *
 * It no longer decides where the sound goes — see [CallAudioPolicy] for why the sensor lost that job.
 * Android's own proximity wake lock does the sensing; nothing here reads the sensor.
 */
class EarProximity(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var proximityWakeLock: PowerManager.WakeLock? = null

    /** Blank the screen at her ear from now on, or stop doing so. Safe to call repeatedly. */
    fun blankAtEar(enabled: Boolean) {
        if (enabled) acquire() else stop()
    }

    private fun acquire() {
        if (proximityWakeLock?.isHeld == true) return
        try {
            if (powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "GentleConnect:EarProximityLock"
                ).apply {
                    acquire(PROXIMITY_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire proximity screen-off wake lock", e)
        }
    }

    /**
     * Let the screen stay on. Must survive being called when nothing was acquired.
     *
     * Must be called when a call ends, fails, or is destroyed.
     */
    fun stop() {
        proximityWakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release proximity wake lock", e)
            }
        }
        proximityWakeLock = null
    }

    companion object {
        private const val TAG = "EarProximity"
        private const val PROXIMITY_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 hours
    }
}
