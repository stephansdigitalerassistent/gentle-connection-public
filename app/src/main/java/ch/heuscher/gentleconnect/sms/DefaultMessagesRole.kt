package ch.heuscher.gentleconnect.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * Being the messages app, rather than reading somebody else's inbox.
 *
 * The same shape as `telecom.DefaultPhoneRole`, and the same reason for existing: exactly one app
 * on the phone holds it, only the owner of the phone can grant it, and it is granted on a system
 * screen this app can open but not draw.
 *
 * What changes when it is held is larger than it looks. Android stops filing incoming text
 * messages itself and hands each one to [SmsDeliverReceiver] instead — so this role is not a
 * label, it is responsibility for every text message the phone receives.
 */
object DefaultMessagesRole {

    /** Whether this app is the messages app right now. */
    fun isHeld(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS) &&
                roles.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    } catch (e: Exception) {
        false
    }

    /**
     * The system screen that asks. Null when there is nothing to ask for — already held, no such
     * role on this device, or a device that cannot receive text messages at all.
     */
    fun requestIntent(context: Context): Intent? {
        if (isHeld(context)) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = context.getSystemService(RoleManager::class.java) ?: return null
                if (!roles.isRoleAvailable(RoleManager.ROLE_SMS)) return null
                roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                @Suppress("DEPRECATION")
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                    Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
                    context.packageName
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
