package ch.heuscher.gentleconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings her messengers back after the two things that stop them without anybody noticing: the
 * app being updated, and the phone being restarted.
 *
 * [MessengerService] is started from the home screen, and until this existed that was the only
 * thing that started it. Installing an update kills the process, and so does a restart; on a phone
 * lying face down with the screen off nobody presses Home for hours, and for those hours nothing
 * arrives — no WhatsApp, no Threema, no WhatsApp call turned away — with nothing on the phone to
 * say so. Seen on the second test phone on 2026-09-14: updated at night, dead until somebody
 * happened to wake it. Play's automatic updates would do the same to her phone, silently.
 *
 * - **`MY_PACKAGE_REPLACED`** arrives in the new version of the app itself, straight after the
 *   update, and Android allows a foreground service to be started from it.
 * - **`BOOT_COMPLETED`** arrives once the phone has been unlocked after a restart. Android 15 and
 *   later refuse most foreground service types started from it, `dataSync` among them; the
 *   `remoteMessaging` and `location` types [MessengerService] uses are two of the few allowed. If
 *   the start is refused anyway, the service stops quietly and the home screen starts it as
 *   before — no worse than without this receiver.
 *
 * After an update the home screen is put back as well, see [HomeAfterUpdate]. Starting a service
 * that is already running is harmless, and every decision about what runs is [MessengerService]'s,
 * as it is when the home screen starts it.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MessengerService.start(context.applicationContext)
                HomeAfterUpdate.restore(context)
            }
            // After a restart Android opens the home screen by itself.
            Intent.ACTION_BOOT_COMPLETED -> MessengerService.start(context.applicationContext)
        }
    }
}
