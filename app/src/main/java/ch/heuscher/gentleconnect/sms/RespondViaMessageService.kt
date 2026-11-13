package ch.heuscher.gentleconnect.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * "Can't talk now — text me instead", the button on some phones' incoming-call screens.
 *
 * Declared because Android requires a service for `RESPOND_VIA_MESSAGE` before it will offer an
 * app as the messages app, in the same way it requires a dialler to answer `ACTION_DIAL`.
 *
 * It deliberately does nothing. That button is on the *other* app's call screen, and the call
 * screen on this phone is now this app's — where it is not offered, because "decline this call and
 * send a message saying why" is three decisions in the two seconds a telephone is ringing, and she
 * would be making them with her daughter waiting on the line. Answering or not answering is the
 * whole of the choice this app gives her.
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
