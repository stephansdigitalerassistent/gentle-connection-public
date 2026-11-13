package ch.heuscher.gentleconnect.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Something that happens every fifteen minutes of the world's time, not of the phone's.
 *
 * The heartbeat used to be `while (true) { delay(FIFTEEN_MINUTES); beat() }`. That is wrong on a
 * phone, and quietly: a coroutine's `delay` parks on a clock that stops while the device is
 * suspended, so it measures fifteen minutes of *being awake*. Measured on her kind of phone:
 *
 *     Time on battery:            15h 44m realtime,  5h 22m uptime  (34.2%)
 *     Time on battery screen off: 12h 23m realtime,  2h 01m uptime  (12.9%)
 *
 * With the screen off that phone is awake an eighth of the time, so the fifteen-minute heartbeat
 * was really about two hours, and the quarter-hour figure the rest of this feature is reasoned
 * from — the writes-per-day arithmetic, "one missed beat is nothing", the position that is meant
 * to be current — was never true. Nobody noticed because two hours is still well inside the twelve
 * hours of silence that mean something, so the website never actually lied about her phone being
 * alive. It only ever told the truth late, and with a stale position attached.
 *
 * An alarm is the fix. `ELAPSED_REALTIME_WAKEUP` counts time the way a person does, including the
 * hours the phone spent asleep, and wakes the device to deliver. Chosen over `RTC_WAKEUP` because
 * the wall clock on a phone nobody administers moves — timezone, network time, a manual correction
 * — and an interval must not skip or repeat because of that.
 *
 * **What this deliberately does not do is outlive the app.** The alarm goes to a receiver
 * registered in code rather than in the manifest, which means a dead process has nothing to
 * deliver to and the beat simply does not happen. That is the whole point of the heartbeat and the
 * reason `WorkManager` was refused for it: a launcher whose process is gone is not working, and a
 * beat that kept arriving from somewhere else would report a dead app as healthy. Silence is the
 * report — see [ch.heuscher.gentleconnect.model.CaregiverAlerts.silence].
 *
 * Nothing here is unit-testable; it is all framework. The judgement this feature has is in
 * [ch.heuscher.gentleconnect.model.CaregiverAlerts] and
 * [ch.heuscher.gentleconnect.model.PhoneLocation], which is where CI can reach it.
 */
class HeartbeatAlarm(
    private val context: Context,
    private val intervalMs: Long,
    private val scope: CoroutineScope,
    private val beat: suspend () -> Unit
) {

    /**
     * Whether a beat is still going. One at a time, because the work inside can wait on a phone
     * looking for satellites and on a database that may be unreachable, and two of those running
     * over each other would ask the same questions twice for one answer.
     */
    private val beating = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The next alarm is set BEFORE the work, not after. Set afterwards, any beat that
            // never finishes ends the heartbeat for good — and one of them can: a Firestore write
            // made while the backend is unreachable is queued rather than failed, and the task it
            // hands back never completes. That is how the old loop could stop for ever after one
            // bad moment, with the website reporting a phone that was running perfectly well as
            // silent. Now a lost beat costs one beat.
            schedule()

            if (!beating.compareAndSet(false, true)) return
            scope.launch {
                try {
                    withTimeoutOrNull(BEAT_TIMEOUT_MS) { beat() }
                } finally {
                    beating.set(false)
                }
            }
        }
    }

    /**
     * Begin. The first beat is one interval away, not now — whoever starts this has just written
     * the same thing by hand.
     *
     * Never unregistered, and there is nowhere it could be: this lives as long as the process, and
     * the process ending is precisely the event it exists to make visible.
     */
    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        schedule()
    }

    /**
     * Inexact on purpose: [AlarmManagerCompat.setAndAllowWhileIdle] needs no permission and is
     * allowed through Doze roughly every ten minutes, which a quarter-hour interval fits inside.
     * The exact variants would need `SCHEDULE_EXACT_ALARM` — a permission for alarm clocks and
     * calendar reminders — to buy precision that means nothing here. A beat is not late at 15:04.
     */
    private fun schedule() {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            AlarmManagerCompat.setAndAllowWhileIdle(
                alarms,
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pendingIntent()
            )
        }
    }

    /**
     * Addressed to this app and nobody else. `setPackage` keeps the broadcast inside the package
     * and `RECEIVER_NOT_EXPORTED` keeps anything outside it from delivering one — an alarm nobody
     * else can forge, for a receiver nobody else can reach.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val ACTION = "ch.heuscher.gentleconnect.HEARTBEAT"

        /**
         * How long one beat may take before it is abandoned.
         *
         * Generous — a beat asks the phone where it is, which can mean waiting on a provider —
         * and still far inside the interval, so an abandoned beat can never overlap the next.
         * Abandoning a Firestore write does not lose it: it stays queued and goes when the phone
         * next has a connection.
         */
        const val BEAT_TIMEOUT_MS = 2L * 60 * 1000
    }
}
