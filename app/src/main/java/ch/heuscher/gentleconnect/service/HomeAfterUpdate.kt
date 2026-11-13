package ch.heuscher.gentleconnect.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log

/**
 * Puts her home screen back after an update has taken it away.
 *
 * Installing an update kills the process, and with it the home screen she was looking at. Android
 * does not bring the home screen back; it shows whatever was next in line instead. Seen on the Fold
 * after Play's automatic updates on 2026-09-30 and 2026-10-01: the Play Store, Samsung's Recents
 * screen, Chrome — each time until she pressed Home herself, which she reported as "I sometimes
 * have to press Home".
 *
 * Android lets the current home app start an activity from the background, so the new version
 * can simply open itself from [RestartReceiver]. It does that only when nobody would be
 * interrupted by it:
 *
 *  - **the screen is off**: she will find the home screen when she next picks the phone up,
 *    instead of whatever app happened to be behind it;
 *  - **or the home screen was in front when the update killed it**: she was looking at it, and
 *    it simply comes back.
 *
 * With the screen on and her in another app — the camera, a WhatsApp call — the update changed
 * nothing she can see, and she is left where she is. Never during a call either: the call's own
 * screen matters more than this one.
 */
object HomeAfterUpdate {

    private const val TAG = "HomeAfterUpdate"
    private const val PREFERENCES = "home_after_update"
    private const val KEY_IN_FRONT = "home_in_front"

    /**
     * Remembers whether the home screen is in front. Called from MainActivity's onResume and
     * onPause. A process killed by an update gets no onPause, so the last value written is the
     * one that was true at the moment of the update.
     */
    fun markInFront(context: Context, inFront: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IN_FRONT, inFront)
            .apply()
    }

    /** Called once, from [RestartReceiver], in the new version straight after an update. */
    fun restore(context: Context) {
        val app = context.applicationContext
        val wasInFront = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_IN_FRONT, false)
        val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (onACall(app) || (screenOn && !wasInFront)) {
            Log.i(TAG, "not restoring home: screenOn=$screenOn wasInFront=$wasInFront")
            return
        }

        // The same intent the Home button sends. Limited to this package, so that it can only
        // ever open this home screen and never ask her to choose between launchers.
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // runCatching: if Android refuses the start, she is no worse off than before this existed,
        // and a receiver that crashes would also lose the messenger restart next to it.
        runCatching { app.startActivity(home) }
            .onSuccess { Log.i(TAG, "home restored after update (screenOn=$screenOn wasInFront=$wasInFront)") }
            .onFailure { Log.w(TAG, "could not restore home after update", it) }
    }

    /** Same test as [Attention]'s: the audio mode covers ordinary and internet calls alike. */
    private fun onACall(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
    }
}
