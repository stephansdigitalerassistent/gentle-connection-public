package ch.heuscher.gentleconnect.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A picture message arriving — and being dropped.
 *
 * Read this before assuming it is a stub somebody forgot to finish. Android will not grant the
 * messages role at all unless an app declares a receiver for `WAP_PUSH_DELIVER`, and it sends that
 * broadcast to exactly one app. This app has no MMS implementation: no PDU parsing, no download
 * from the carrier's MMSC, no store. So a picture message sent to her while this app holds the
 * role is **lost**, not merely unshown — it is not filed in another app for later, it is gone.
 *
 * That was a decision, taken on 2026-08-16 with the cost stated: the alternative was leaving text
 * messages outside the app's own hands, which is what blocks the whole application from being
 * distributed at all. If picture messages turn out to matter, the fix is a real implementation
 * here, and until then the honest thing is that this file says plainly what it does.
 *
 * Logged in logcat and recorded in CaregiverLog so that "did she get the photo?" has an answer
 * on the caregiver website and in the phone's error log.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(
            "GentleConnection",
            "A picture message arrived and was dropped: this app holds the messages role and " +
                "has no MMS support. See MmsDeliverReceiver."
        )

        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CaregiverLogRepository(
                    AppDatabase.getInstance(appContext).caregiverEventDao()
                ).record(
                    CaregiverEvent(
                        kind = CaregiverEventKind.MMS_DROPPED_UNSUPPORTED,
                        at = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.w("GentleConnection", "Failed to record MMS drop in CaregiverLog", e)
            } finally {
                result.finish()
            }
        }
    }
}

