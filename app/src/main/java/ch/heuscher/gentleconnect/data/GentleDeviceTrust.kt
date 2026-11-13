package ch.heuscher.gentleconnect.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Shared trust verification for gentle device, the phone's management app (DPC) [docs/phase1b.md §2, §3b].
 *
 * Verifies that the package name is [PACKAGE_NAME] and that it is signed with the pinned
 * gentle device certificate (SHA-256 32:0F:F6:42:…:60:14). Used by:
 * - [DeviceLinkProvider] to verify the caller for link and status calls.
 * - [DeviceWake] to stay completely inert on phones without gentle device (hers).
 */
object GentleDeviceTrust {

    const val PACKAGE_NAME = "ch.heuscher.gentledevice"

    /** SHA-256 of gentle device's signing certificate, 32:0F:F6:42:…:60:14. */
    val CERT_SHA256: ByteArray =
        "320FF6421E05A08F627644C313B6522C7D0D6D4EAA2394A3E423E7287DE46014"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /**
     * Checks whether the calling UID belongs exclusively to gentle device and holds the pinned
     * certificate. Used by [DeviceLinkProvider].
     */
    fun callerIsGentleDevice(context: Context, uid: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return false
        return packages.size == 1 &&
            packages[0] == PACKAGE_NAME &&
            runCatching {
                pm.hasSigningCertificate(uid, CERT_SHA256, PackageManager.CERT_INPUT_SHA256)
            }.getOrDefault(false)
    }

    /**
     * Checks whether gentle device is installed on this device and signed with the pinned
     * certificate.
     */
    fun isInstalledAndTrusted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm = context.packageManager
        return runCatching {
            pm.hasSigningCertificate(PACKAGE_NAME, CERT_SHA256, PackageManager.CERT_INPUT_SHA256)
        }.getOrDefault(false)
    }
}
