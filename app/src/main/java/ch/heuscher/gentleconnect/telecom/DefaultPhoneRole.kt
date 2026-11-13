package ch.heuscher.gentleconnect.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/**
 * Being the telephone, rather than borrowing one.
 *
 * Android lets exactly one app hold this at a time, and holding it is what turns
 * [GentlePhoneCallService] from dormant code into the thing that answers the phone. Until it is
 * granted, everything in this package is inert and calls go to whichever app held it before —
 * which on her phone is a different app of ours, with a different call screen.
 *
 * Only the owner of the phone can grant it, on a system screen this app cannot draw or dismiss.
 * So this is a caregiver's job during setup, not something the app can arrange for itself.
 */
object DefaultPhoneRole {

    /** Whether this app is the telephone right now. */
    fun isHeld(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                roles.isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage == context.packageName
        }
    } catch (e: Exception) {
        // A device with no telephony at all answers some of this by throwing. Not the telephone,
        // then — which is the truthful answer and not a crash.
        false
    }

    /**
     * The system screen that asks. Null when there is nothing to ask for.
     *
     * Null covers three quite different situations, and the caller wants the same behaviour for
     * all of them — say nothing and carry on: the role is already held, this device has no dialler
     * role because it has no telephony, or the platform is too old to have the concept.
     */
    fun requestIntent(context: Context): Intent? {
        if (isHeld(context)) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = context.getSystemService(RoleManager::class.java) ?: return null
                if (!roles.isRoleAvailable(RoleManager.ROLE_DIALER)) return null
                roles.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else {
                @Suppress("DEPRECATION")
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    context.packageName
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
