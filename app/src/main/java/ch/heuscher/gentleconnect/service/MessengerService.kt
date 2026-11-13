package ch.heuscher.gentleconnect.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import ch.heuscher.gentleconnect.MainActivity
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.ArrivalPipeline
import ch.heuscher.gentleconnect.data.DeviceWake
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.MessengerRuntime
import ch.heuscher.gentleconnect.util.CaregiverStatsExport

/**
 * Keeps the messengers connected while she is doing something else.
 *
 * This exists because of one line in `dumpsys netpolicy`, read on her phone:
 *
 *     UID=10638 blocked_state={blocked=APP_BACKGROUND, allowed=NONE, effective=APP_BACKGROUND}
 *
 * which appeared at the exact second the launcher stopped being the foreground app. Android cuts
 * the network of a backgrounded app: every socket it owns starts failing with EPERM, and the
 * failure is silent. It cost most of a day to find, because the symptom is not an error — it is
 * messages simply not arriving.
 *
 * The app being a launcher hid this for a long time. A launcher is usually the foreground app, so
 * the messengers usually worked; the moment she opened anything else — and the moment a caregiver
 * had to open WhatsApp to type a pairing code, which is the one thing linking cannot avoid — they
 * stopped. That is not an edge case. Her phone rings, she takes a photograph, somebody hands her
 * the weather: the launcher is backgrounded constantly, and every one of those moments was
 * silently disconnecting her.
 *
 * A foreground service is Android's only supported answer. It costs a permanent notification —
 * see [notification], which is written for somebody who did not ask for it and does not know what
 * a service is.
 *
 * This service holds no messenger state of its own. The clients live in [Messengers] and outlive
 * any single screen; all this does is keep the process out of the background bucket so their
 * sockets survive.
 *
 * [ArrivalPipeline] is started before the clients. Nothing outside the pipeline collects what the
 * Threema client emits, and a client that connects, is handed her messages, acknowledges them to
 * the server and has nobody listening has not delivered them — it has destroyed them. The server
 * does not keep a second copy of an acknowledged message. Connecting without a collector attached
 * would be worse than not connecting. Order matters and is the whole point: a collector must be
 * attached before a client is allowed to connect, or the first messages of the session are
 * acknowledged into nothing.
 */
class MessengerService : Service() {

    private var deviceWake: DeviceWake? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground()) {
            return START_NOT_STICKY
        }
        running = true
        stoppedByTimeLimit = false
        ArrivalPipeline.start(this)
        CaregiverStatsExport.start(this)
        // Kept registered in the service so the counter keeps counting while the phone sleeps and midnight is noticed.
        AppGraph.stepCounter(this).start()
        // Idempotent: registers what is missing, including listeners that failed last time.
        (deviceWake ?: DeviceWake(this).also { deviceWake = it }).start()
        // The clients, the call manager and every listener that has to run with the screen off,
        // in the one order that works — see MessengerRuntime. The call manager used to be built
        // only by the view model, so after a restart with nobody looking at the screen Threema was
        // connected with nothing to ring for an incoming call (seen on the Fold, 2026-09-16 22:47,
        // right after installing 1.0.1384 with the screen off). The contact seeding and the
        // connection-failure log had the same problem until they moved there too.
        MessengerRuntime.start(this)
        // The website's lists of groups, senders and posters, and the faults worth a caregiver's
        // attention — kept up to date with the screen off, which they were not while the home
        // screen published them.
        AppGraph.caregiverPublisher(this).start()
        // START_STICKY: if Android kills the process for memory, bring it back. The messengers
        // reconnect on their own once there is a process to reconnect in.
        return START_STICKY
    }

    /**
     * Android's allowance for a time-limited service type, used up.
     *
     * Should no longer be reached: see [connectionType]. It is here because it was reached — the
     * service used to be `dataSync`, which from Android 15 gets six hours in any twenty-four, and a
     * few seconds after this call Android crashes the whole app if the service is still of that
     * type. That happened on the test phone at 04:31 on 2026-09-14, with location off, while nobody
     * was looking: no messages, no call turned away, nothing on the screen. Should Android ever put
     * a limit on a type this service uses, this is the difference between that and a clean stop.
     *
     * So, where it is genuinely held, carry on as `location` alone, which has no limit. Where it is
     * not, stop cleanly. Her messengers then wait until she next has the app in front of her —
     * that is what gives the hours back, and [restartAfterTimeLimit] starts it again from there.
     * On her phone, where this app is the home screen, that is the next time she looks at it.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Only ever called from Android 15 on; the version check is for lint, which cannot know.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mayReportLocation(this)) {
            val locationOnly = runCatching {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
            if (locationOnly.isSuccess) {
                claimedLocation = true
                Log.i(TAG, "time limit reached for type $fgsType; carrying on as location")
                return
            }
        }
        Log.w(TAG, "time limit reached for type $fgsType; stopping until the app is opened")
        stoppedByTimeLimit = true
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        deviceWake?.stop()
        deviceWake = null
        ArrivalPipeline.stop()
        CaregiverStatsExport.stop()
        AppGraph.stepCounter(this).stop()
        Messengers.stopAll()
    }

    /**
     * Go foreground, and say as little about what this service does as is currently true.
     *
     * From Android 14 a foreground service declares its types and the system checks it holds what
     * each type requires. This one keeps her messenger connections open — see [connectionType] for
     * why that is `remoteMessaging` and not `dataSync` — and, when the phone is in a position to do
     * it, is also `location`, because the same process is what answers "where is the phone" for the
     * caregiver website between heartbeats.
     *
     * The order of the fallback is the important part. The location type is claimed only when
     * every prerequisite is genuinely held, and if the system refuses it anyway the service starts
     * again as plain `dataSync`. That asymmetry is deliberate and is not defensive habit: this
     * service is the reason her messages arrive at all, and the failure mode of getting the type
     * wrong is not a missing position on a website — it is a launcher that throws on start and a
     * woman whose family cannot reach her, with a permission dialog somewhere as the only clue.
     * A position is worth having. It is not worth that.
     *
     * Returns true if foreground status was successfully established, false if Android refused it.
     */
    private fun startInForeground(): Boolean {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val location = mayReportLocation(this)
            val connection = connectionType()
            val types = connection or if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

            val primary = runCatching { startForeground(NOTIFICATION_ID, notification, types) }
            if (primary.isSuccess) {
                claimedLocation = location
                return true
            }

            val fallback = runCatching {
                startForeground(NOTIFICATION_ID, notification, connection)
            }
            if (fallback.isSuccess) {
                claimedLocation = false
                return true
            }

            // Location on its own, and only where it is genuinely held: the last way left to keep
            // her connected if Android refuses the connection type. The claim is true — this
            // process is what reports where the phone is. Started again from the home screen, it
            // declares the connection type too.
            if (location) {
                val locationOnly = runCatching {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                if (locationOnly.isSuccess) {
                    claimedLocation = true
                    return true
                }
            }

            // Foreground transition refused (e.g. ForegroundServiceStartNotAllowedException on Android 14+).
            // Stop cleanly rather than crashing the launcher process.
            claimedLocation = null
            stopSelf()
            return false
        } else {
            val legacy = runCatching { startForeground(NOTIFICATION_ID, notification) }
            if (legacy.isFailure) {
                stopSelf()
                return false
            }
            return true
        }
    }

    /**
     * The notification she will see, for ever, in her notification shade.
     *
     * Wording matters more than usual here. It is on her phone, she did not ask for it, and she
     * cannot dismiss it — so it says what it is in words that mean something to her ("messages
     * can arrive"), and not what it is technically ("foreground service", "bridge", "connected").
     * Silent, no badge, and as low a priority as Android permits, so it sits at the bottom of the
     * shade and never interrupts anything.
     *
     * Tapping it opens her home screen rather than doing nothing, because a notification that
     * does nothing when tapped is a thing that appears broken.
     */
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.messenger_service_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.messenger_service_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.messenger_service_title))
            .setContentText(getString(R.string.messenger_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gentle_connection_messengers"
        private const val NOTIFICATION_ID = 4711
        private const val TAG = "MessengerService"

        /**
         * Whether [onTimeout] stopped it, and nothing has started it since.
         *
         * Nothing else would. It is started from the view model once, when the screen is first
         * made, and START_STICKY does not bring back a service that stopped itself.
         */
        @Volatile
        private var stoppedByTimeLimit = false

        /**
         * Started in the foreground in this process and not destroyed since.
         *
         * Only asked by gentle device, the phone's management app, through
         * [ch.heuscher.gentleconnect.data.DeviceLinkProvider]'s `status`: "is the app on her
         * phone doing its job" is the one thing it cannot see from outside.
         */
        @Volatile
        var running = false
            private set

        /**
         * The type that keeps her connections open: `remoteMessaging` from Android 14, where it
         * exists, and `dataSync` before that.
         *
         * `dataSync` is for finite work — a backup, an upload — and Android 15 treats it that way:
         * six hours in any twenty-four, then [onTimeout], then a crash; and it may not be started
         * after the phone restarts. A messenger connection is neither finite nor optional, and
         * `remoteMessaging` is the type for it: no time limit, and allowed from BOOT_COMPLETED so
         * [RestartReceiver] can bring her messages back after a restart even with location off.
         * Threema's own always-connected mode (ThreemaPushService) uses the same type for the same
         * reason. Before Android 14 there is no such type and neither restriction exists.
         */
        private fun connectionType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        /**
         * Whether the location type was claimed the last time this service went foreground, or
         * null if it has not started yet.
         *
         * Held here rather than asked of the system, because the question it answers is not "may
         * this service report location" but "does what is running still match what is true" — and
         * only the service knows what it claimed.
         */
        @Volatile
        private var claimedLocation: Boolean? = null

        /**
         * Whether this service may honestly call itself a location service.
         *
         * All three conditions, because the system checks all three and because each of them is a
         * switch somebody can flip on a live phone: she can turn location off in the quick
         * settings, a caregiver can withdraw the permission, and Android itself drops the
         * background grant when the foreground one goes.
         *
         * Background is required rather than merely preferred. A service of type `location` may
         * not be started from the background without it — and being started from the background
         * is the normal case here, since START_STICKY brings this back whenever Android has
         * reclaimed the process.
         */
        private fun mayReportLocation(context: Context): Boolean {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                    )
            if (!granted) return false

            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            return runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
        }

        /**
         * Start again, but only if the answer has changed since it last declared itself.
         *
         * A service's foreground type is fixed at the moment it starts, and the grant this depends
         * on arrives afterwards: Android does not put "immer zulassen" in a dialog, it opens its
         * own settings page and the caregiver taps it there. By the time they come back the
         * service has been running as plain `dataSync` for a minute, and would go on doing so
         * until something restarted it — a phone with the permission granted and nothing using it.
         *
         * The permission result cannot be trusted to say when that happened. A request the system
         * satisfies by opening a settings page comes back reporting denied, because at the instant
         * it returned that was true. Verified on the phone: granted in the system UI, result false,
         * service still `types=0x1`. So this asks the system what is true now instead, and is safe
         * to call from anywhere as often as it likes — unchanged means nothing happens, and
         * never-started means the ordinary start is still to come and will get it right.
         */
        fun refreshLocationEntitlement(context: Context) {
            val claimed = claimedLocation ?: return
            if (claimed != mayReportLocation(context)) start(context)
        }

        /**
         * Start it again if Android's time limit stopped it, now that she has the app in front of
         * her — the one moment Android lifts a used-up allowance before the day is over. Call it on
         * every resume; when nothing was stopped it does nothing.
         */
        fun restartAfterTimeLimit(context: Context) {
            if (stoppedByTimeLimit) start(context)
        }

        /**
         * Start it, if it is not already running.
         *
         * Safe to call on every resume: starting a running foreground service is a no-op beyond
         * re-delivering the notification. Failures are swallowed — Android refuses to start a
         * foreground service from the background in some states, and a launcher that crashed
         * because it could not post a notification would be very much worse than one whose
         * messengers reconnect a moment later.
         */
        fun start(context: Context) {
            tryStart(context)
        }

        /**
         * [start], saying whether Android accepted the start. From Android 12 a start from the
         * background throws (ForegroundServiceStartNotAllowedException); that is a false here.
         * True only means the start was accepted — the service may still refuse to go foreground.
         */
        fun tryStart(context: Context): Boolean {
            val intent = Intent(context, MessengerService::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start refused", it) }.isSuccess
        }
    }
}
