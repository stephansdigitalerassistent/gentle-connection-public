package ch.heuscher.gentleconnect.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.MainActivity
import ch.heuscher.gentleconnect.R

/**
 * How the phone says, out loud, that somebody has written.
 *
 * Until this existed the app made no sound at all and lit nothing up. Everything it had to say
 * was on the home screen, which is only ever seen by somebody already holding the phone — so a
 * message from her son arrived in silence and waited on a dark screen on the kitchen table. For
 * somebody not expecting anything, that wait is measured in days.
 *
 * What this deliberately is *not* is a notification in the ordinary sense. She will never pull
 * down the shade, has never been taught to, and a list of things to swipe away is exactly the
 * kind of state this app exists to keep off her phone. When the screen is off, a full-screen intent
 * is used to wake the display and put the notice in front of her; when the screen is already on,
 * she is holding the phone, so an ordinary heads-up notice — which Android retires by itself after
 * a few seconds — is posted instead of a sticky banner that would stay until dismissed. What she
 * is meant to end up looking at is the message itself, which is what the notice says it will show
 * her — "Press to read it". It cancels itself after a couple of minutes so that nothing
 * accumulates in the shade behind it.
 *
 * It used to end at her home screen instead, on the reasoning that the card there is a place she
 * knows. In practice that made the notice a signpost to a second signpost: the card repeated the
 * same sentence and put the message one more press away, behind a button she had to find. The
 * message id now travels with the notice and MainActivity opens the conversation with it; the home
 * screen is where she lands only when that message cannot be found.
 *
 * Two things it will not do, both because a phone that does them once is a phone the family turns
 * off:
 *
 *  - **Not while she is on the telephone.** A chime in her ear mid-call is startling and she
 *    cannot see what caused it.
 *  - **Not at night.** See [ch.heuscher.gentleconnect.model.Arrivals], which is also where "twice
 *    for the same message" and "loudly, for a four-year-old message being imported" are ruled out.
 *
 * The sound is the app's own — two low tones, a fifth apart, decaying — rather than the system's
 * default. Ordinary notification sounds are short, bright and high, which is the part of the
 * range that goes first: a "ping" she cannot hear is a feature that does not exist.
 */
class Attention(context: Context) {

    private val app = context.applicationContext

    /**
     * Wake the phone for a message from [senderName].
     *
     * The sentence is the one the card at the top of her home screen uses, so that what she hears
     * about and what she then sees are the same words. Silently does nothing if the permission to
     * post notifications was refused — the card is still there, and an app that crashes over its
     * own reminder is worse than one that is merely quiet.
     *
     * [missedCall] changes only the words. Somebody who rang and somebody who wrote are the same
     * event to everything else here — both are a person trying to reach her, and both are worth
     * the screen lighting up — but a notice saying her daughter "hat geschrieben" when her
     * daughter rang sends her looking for a message that does not exist.
     */
    fun call(senderName: String, messageId: String? = null, missedCall: Boolean = false) {
        if (onACall()) return

        // Both of these have to be yes, and they are different questions. From Android 13 posting
        // a notification is a permission somebody has to grant — asked for at startup, refusable,
        // and withdrawable later in the phone's own settings. Independently of that, notifications
        // can be switched off for the app or for this one channel. Written out here rather than
        // behind a name because the tool that reads this code for missing permissions only trusts
        // a check it can see at the call site.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        createChannel()

        val open = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ATTENTION, true)
                // Which message this notice is about. FLAG_UPDATE_CURRENT below is what makes the
                // id right: the pending intent is reused for every notice, and without it the
                // second message of the evening would open the first one.
                .putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (missedCall) {
                    app.getString(R.string.nudge_missed_call_from, senderName)
                } else {
                    app.getString(R.string.nudge_message_from, senderName)
                }
            )
            .setContentText(
                app.getString(
                    if (missedCall) R.string.attention_body_missed_call else R.string.attention_body
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (missedCall) NotificationCompat.CATEGORY_MISSED_CALL
                else NotificationCompat.CATEGORY_MESSAGE
            )
            .setAutoCancel(true)
            // Gone from the shade on its own. Nothing in this app should ever need dismissing.
            .setTimeoutAfter(CLEARS_AFTER_MS)
            .setContentIntent(open)

        if (!isScreenOn()) {
            // Turning the screen on is wanted only when the screen is dark. When she is already
            // holding the phone and looking at it, an ordinary heads-up notice — which Android
            // retires by itself after a few seconds — is all that is needed. Android treats a
            // full-screen intent like an incoming call and leaves the heads-up banner sitting on
            // her screen until dismissed, which puts a thing to swipe away right over the top
            // of whatever she was doing.
            builder.setFullScreenIntent(open, true)
        }

        val notification = builder.build()

        // The checks above are the real guard; this is for the platform refusing anyway. A
        // reminder that cannot be delivered must never take the launcher down with it — she would
        // be left holding a phone with no home screen at all.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (refused: SecurityException) {
            // Nothing to do and nobody to tell. The card on her home screen is still there; this
            // was only ever the part that fetches her.
        }
    }

    /**
     * Whether the screen is on right now.
     *
     * Treated as on when the power manager is unavailable: the safe answer, an ordinary heads-up
     * that retires itself rather than a sticky full-screen intent.
     */
    private fun isScreenOn(): Boolean {
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    /**
     * Whether she is on the telephone right now.
     *
     * Asked of the audio system rather than of the telephony state, because it is true for the
     * calls this app places over the internet as well as for ordinary ones, and because it needs
     * no permission of its own.
     */
    private fun onACall(): Boolean {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val sound = Uri.parse("android.resource://" + app.packageName + "/" + R.raw.gentle_chime)
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.attention_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = app.getString(R.string.attention_channel_description)
            enableVibration(true)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        // Distinct from the connection channel MessengerService owns, which is silent and as low
        // as Android permits. Nothing about that one should ever make a sound; nothing about this
        // one should ever be quiet.
        const val CHANNEL_ID = "gentle_arrivals"

        // Not 4711: that is the notification MessengerService keeps posted for as long as it is
        // connected, and Android files a notification by its id alone. Posting this one under the
        // same number replaces the service's — the service goes on running with no notice of its
        // own, and when this one clears itself two minutes later the phone is left showing a
        // foreground service that appears to have vanished.
        const val NOTIFICATION_ID = 4712
        const val REQUEST_CODE = 4712
        const val CLEARS_AFTER_MS = 2 * 60 * 1000L
    }
}
