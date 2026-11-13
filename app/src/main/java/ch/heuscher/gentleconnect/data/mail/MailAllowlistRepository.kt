package ch.heuscher.gentleconnect.data.mail

import android.content.Context
import android.content.SharedPreferences

/**
 * Storage for caregiver-added email allowlist addresses or names.
 */
class MailAllowlistRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mail_allowlist_prefs", Context.MODE_PRIVATE)

    fun getAllowlist(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
    }

    fun addAddress(address: String) {
        if (address.isBlank()) return
        val current = getAllowlist().toMutableSet()
        current.add(address.trim())
        prefs.edit().putStringSet(KEY_ALLOWLIST, current).apply()
    }

    fun removeAddress(address: String) {
        if (address.isBlank()) return
        val current = getAllowlist().toMutableSet()
        current.remove(address.trim())
        prefs.edit().putStringSet(KEY_ALLOWLIST, current).apply()
    }

    fun setAllowlist(addresses: Collection<String>) {
        val set = addresses.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        prefs.edit().putStringSet(KEY_ALLOWLIST, set).apply()
    }

    companion object {
        private const val KEY_ALLOWLIST = "allowlist_addresses"
    }
}
