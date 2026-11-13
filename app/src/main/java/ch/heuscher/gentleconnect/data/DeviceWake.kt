package ch.heuscher.gentleconnect.data

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Wakes gentle device, the phone's device owner / DPC app, when steering actions arrive
 * on Firestore [docs/phase1b.md §3b].
 *
 * Listens for:
 * 1. Pending commands in `devices/{deviceId}/commands` (`status == "pending"`).
 * 2. Policy updates in `devices/{deviceId}/policy/current`.
 *
 * When a non-cache, non-empty update arrives, sends an explicit broadcast
 * `ch.heuscher.gentledevice.action.WAKE` to `ch.heuscher.gentledevice`, debounced to at most
 * once every 5,000 ms.
 *
 * [start] is called on every start of the messenger service and is idempotent, so a listener
 * that failed (for example before the anonymous sign-in finished) is registered again later.
 *
 * Completely inert if gentle device is not installed or not signed with the pinned certificate
 * (e.g. on her phone).
 */
class DeviceWake(private val context: Context) {

    private var commandsRegistration: ListenerRegistration? = null
    private var policyRegistration: ListenerRegistration? = null
    private var permissionErrorLogged = false
    private var lastWakeMs = 0L

    @Synchronized
    fun start() {
        if (!GentleDeviceTrust.isInstalledAndTrusted(context)) {
            Log.d(TAG, "gentle device not installed or untrusted; DeviceWake stays inert")
            return
        }
        if (commandsRegistration != null || policyRegistration != null) {
            return
        }

        val deviceId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, null)
            ?: (Messengers.settings(context) as? FirestoreSettingsRepository)?.deviceId

        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "No deviceId found; cannot listen for commands or policy updates")
            return
        }

        runCatching {
            val deviceDoc = Firebase.firestore.collection(COLLECTION_DEVICES).document(deviceId)

            commandsRegistration = deviceDoc.collection(COLLECTION_COMMANDS)
                .whereEqualTo("status", "pending")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (!permissionErrorLogged) {
                            permissionErrorLogged = true
                            Log.w(TAG, "commands listen error: ${error.message}", error)
                        }
                        // A failed listener is dead for good (typically: not signed in yet).
                        // Forget it so the next start() from the service registers it again.
                        clearAfterError { commandsRegistration?.remove(); commandsRegistration = null }
                        return@addSnapshotListener
                    }
                    if (snapshot == null || snapshot.metadata.isFromCache || snapshot.isEmpty) {
                        return@addSnapshotListener
                    }
                    triggerWake()
                }

            policyRegistration = deviceDoc.collection(COLLECTION_POLICY)
                .document(DOC_CURRENT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (!permissionErrorLogged) {
                            permissionErrorLogged = true
                            Log.w(TAG, "policy listen error: ${error.message}", error)
                        }
                        // A failed listener is dead for good (typically: not signed in yet).
                        // Forget it so the next start() from the service registers it again.
                        clearAfterError { policyRegistration?.remove(); policyRegistration = null }
                        return@addSnapshotListener
                    }
                    if (snapshot == null || snapshot.metadata.isFromCache || !snapshot.exists()) {
                        return@addSnapshotListener
                    }
                    triggerWake()
                }
        }.onFailure { e ->
            if (!permissionErrorLogged) {
                permissionErrorLogged = true
                Log.w(TAG, "Failed to register DeviceWake listeners", e)
            }
        }
    }

    @Synchronized
    private fun clearAfterError(clear: () -> Unit) = clear()

    @Synchronized
    fun stop() {
        commandsRegistration?.remove()
        commandsRegistration = null
        policyRegistration?.remove()
        policyRegistration = null
    }

    @Synchronized
    private fun triggerWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeMs < DEBOUNCE_MS) {
            return
        }
        lastWakeMs = now
        Log.i(TAG, "Sending wake broadcast to gentle device")
        val intent = Intent(ACTION_WAKE).apply {
            setPackage(GentleDeviceTrust.PACKAGE_NAME)
        }
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "DeviceWake"
        private const val PREFS_NAME = "gentle_connection_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val COLLECTION_DEVICES = "devices"
        private const val COLLECTION_COMMANDS = "commands"
        private const val COLLECTION_POLICY = "policy"
        private const val DOC_CURRENT = "current"
        const val ACTION_WAKE = "ch.heuscher.gentledevice.action.WAKE"
        private const val DEBOUNCE_MS = 5000L
    }
}
