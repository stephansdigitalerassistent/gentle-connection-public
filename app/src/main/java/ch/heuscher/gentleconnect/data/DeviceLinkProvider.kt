package ch.heuscher.gentleconnect.data

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import ch.heuscher.gentleconnect.service.MessengerService
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.concurrent.TimeUnit

/**
 * The one door gentle device — the phone's management app — has into gentle connection.
 *
 * The two apps are signed with different keys (the management app's key is the one its
 * provisioning QR code pins), so a signature permission cannot join them. Instead this provider
 * answers exactly one call, `link`, and only to the package `ch.heuscher.gentledevice` holding
 * the pinned gentle device certificate. The call hands over the management app's own anonymous
 * Firebase uid; this app writes it onto its own device document as `dpcUid`, which is what the
 * Firestore rules then trust to write `devices/{id}/managed/…`, and answers with the device id.
 *
 * The second call, `status`, is the management app's watchdog asking whether this app is doing
 * its job: is the messenger service up, when did a heartbeat last reach the server, does it hold
 * the home, phone and SMS roles. Asked to (`kick`), it starts the messenger service the way an
 * update or a restart does. No messages, contacts, numbers or settings — see gentle device's
 * `docs/phase1b.md`, slice 2.
 *
 * Nothing else is exposed: no query, no insert, no data. On a phone without gentle device —
 * hers today — nobody ever calls it.
 */
class DeviceLinkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return failure("no_context")
        if (method != METHOD_LINK && method != METHOD_STATUS) return failure("unknown_method")
        // Checked before anything else is read or written: an untrusted caller learns nothing,
        // not even whether this phone has a device id yet.
        if (!GentleDeviceTrust.callerIsGentleDevice(ctx, Binder.getCallingUid())) return failure("caller_untrusted")

        return if (method == METHOD_STATUS) status(ctx, extras?.getBoolean(KEY_KICK) == true)
        else link(ctx, arg)
    }

    private fun link(ctx: Context, arg: String?): Bundle {
        val dpcUid = arg?.trim().orEmpty()
        if (!UID_PATTERN.matches(dpcUid)) return failure("bad_uid")

        val deviceId = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, null)
            ?: return failure("no_device")
        if (Firebase.auth.currentUser == null) return failure("not_signed_in")

        // call() runs on a binder thread, never the main thread, so a bounded wait is allowed.
        // A timeout leaves the write queued in the offline cache; it reports failure anyway so
        // the management app tries again on its next run rather than trusting a write that has
        // not reached the server.
        return try {
            Tasks.await(
                Firebase.firestore.collection(COLLECTION_DEVICES).document(deviceId).update(
                    mapOf(
                        "dpcUid" to dpcUid,
                        "dpcLinkedAt" to FieldValue.serverTimestamp()
                    )
                ),
                WRITE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            Log.i(TAG, "Linked gentle device ${dpcUid.take(6)}… to $deviceId")
            Bundle().apply {
                putBoolean(KEY_OK, true)
                putString(KEY_DEVICE_ID_RESULT, deviceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Linking gentle device failed", e)
            failure(if (e is java.util.concurrent.TimeoutException) "timeout" else "write_failed")
        }
    }

    /**
     * How this app is doing, as far as it can tell from inside.
     *
     * The kick is asked for only when gentle device is enforcing. It goes through
     * [MessengerService.tryStart], the same start an update or a restart makes, and reports
     * whether Android let a background start through: that is the open question slice 2 measures.
     * A start that was accepted is reported as `started`; the service coming up is the next
     * status call's `messengerRunning`.
     */
    private fun status(ctx: Context, kick: Boolean): Bundle {
        val kickResult = when {
            !kick -> "not_asked"
            MessengerService.running -> "not_needed"
            MessengerService.tryStart(ctx.applicationContext) -> "started"
            else -> "refused"
        }
        val lastBeatOkMs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(FirestoreSettingsRepository.KEY_LAST_BEAT_OK_MS, 0L)
        return Bundle().apply {
            putBoolean(KEY_OK, true)
            putLong(KEY_VERSION_CODE, versionCode(ctx))
            putBoolean(KEY_MESSENGER_RUNNING, MessengerService.running)
            putLong(KEY_LAST_BEAT_OK_MS, lastBeatOkMs)
            putBoolean(KEY_HOME, holdsRole(ctx, RoleManager.ROLE_HOME))
            putBoolean(KEY_DIALER, holdsRole(ctx, RoleManager.ROLE_DIALER))
            putBoolean(KEY_SMS, holdsRole(ctx, RoleManager.ROLE_SMS))
            putString(KEY_KICK, kickResult)
        }
    }

    private fun versionCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun holdsRole(ctx: Context, role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = ctx.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roles.isRoleAvailable(role) && roles.isRoleHeld(role) }.getOrDefault(false)
    }


    private fun failure(reason: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, reason)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "DeviceLinkProvider"
        private const val METHOD_LINK = "link"
        private const val METHOD_STATUS = "status"
        private const val KEY_OK = "ok"
        private const val KEY_ERROR = "error"
        private const val KEY_DEVICE_ID_RESULT = "deviceId"
        private const val KEY_KICK = "kick"
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_MESSENGER_RUNNING = "messengerRunning"
        private const val KEY_LAST_BEAT_OK_MS = "lastBeatOkMs"
        private const val KEY_HOME = "home"
        private const val KEY_DIALER = "dialer"
        private const val KEY_SMS = "sms"
        private const val WRITE_TIMEOUT_SECONDS = 10L

        // Same file and key as FirestoreSettingsRepository.getOrCreateDeviceId. Read, never
        // created: a phone that has not made its device document yet has nothing to link to.
        private const val PREFS_NAME = "gentle_connection_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val COLLECTION_DEVICES = "devices"

        /** Firebase uids are 28 characters of [A-Za-z0-9]; allow a little room, nothing else. */
        private val UID_PATTERN = Regex("^[A-Za-z0-9]{20,128}$")
    }
}
