package ch.heuscher.gentleconnect

import android.app.Application
import ch.heuscher.gentleconnect.util.LogRecorder

/**
 * The first thing to run in the app's process, whichever part of the app woke it: an activity, the
 * messenger service, a call or an SMS.
 */
class GentleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogRecorder.start(this)
    }
}
