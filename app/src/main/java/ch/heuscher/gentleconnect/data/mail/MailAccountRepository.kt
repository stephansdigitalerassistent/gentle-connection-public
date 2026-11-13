package ch.heuscher.gentleconnect.data.mail

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Which Gmail account her post is read from — the address and nothing else. The grant itself is
 * held by Play services (see [GmailAuthorizer]); there is no password here to leak.
 *
 * Same encrypted file the IMAP credentials lived in, and anything left of those is deleted the
 * first time this is read: an app password sitting unused on the phone is still a key to her
 * whole mailbox.
 */
class MailAccountRepository(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getGmailAddress(): String? {
        forgetImapCredentials()
        return prefs.getString(KEY_GMAIL_ADDRESS, null)?.takeIf { isPlausibleAddress(it) }
    }

    fun saveGmailAddress(address: String): Boolean {
        if (!isPlausibleAddress(address)) return false
        prefs.edit().putString(KEY_GMAIL_ADDRESS, address.trim()).apply()
        return true
    }

    fun clearAccount() {
        prefs.edit().remove(KEY_GMAIL_ADDRESS).apply()
        forgetImapCredentials()
    }

    private fun forgetImapCredentials() {
        if (LEGACY_IMAP_KEYS.none { prefs.contains(it) }) return
        prefs.edit().apply { LEGACY_IMAP_KEYS.forEach { remove(it) } }.apply()
    }

    companion object {
        private const val PREFS_FILENAME = "encrypted_mail_account_prefs"
        private const val KEY_GMAIL_ADDRESS = "gmail_address"

        private val LEGACY_IMAP_KEYS = listOf(
            "email_address", "imap_host", "imap_port", "app_password", "folder", "uid_validity"
        )

        fun isPlausibleAddress(address: String): Boolean {
            val trimmed = address.trim()
            return trimmed.length >= 3 &&
                trimmed.indexOf('@') in 1 until trimmed.length - 1 &&
                trimmed.none { it.isWhitespace() }
        }
    }
}
