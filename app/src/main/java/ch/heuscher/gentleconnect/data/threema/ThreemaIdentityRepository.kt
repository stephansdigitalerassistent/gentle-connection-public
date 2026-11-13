package ch.heuscher.gentleconnect.data.threema

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encapsulates the user's Threema credentials (ID and Client Key).
 *
 * Credentials are encrypted at rest using [EncryptedSharedPreferences] backed by the Android
 * Keystore (AES-256 GCM).
 *
 * CRITICAL SECURITY REQUIREMENT:
 * The client key is equivalent to her entire Threema account.
 * It MUST NEVER be logged or included in diagnostic dumps.
 */
data class ThreemaIdentity(
    val threemaId: String,
    val clientKey: String,
    val serverGroup: String = "d0",
    val nickname: String = "gentle connection"
)

/**
 * Stores the user's Threema credentials securely at rest.
 *
 * Why migration on construction is required:
 * Her client key exists in the legacy plaintext preferences file ("threema_identity_secure_prefs").
 * When upgrading, if the encrypted store holds no identity and the old plaintext store does,
 * all fields (Threema ID, client key, server group, nickname) are copied into the encrypted store
 * and only then cleared from the legacy store. A change that simply opened an empty encrypted store
 * would permanently destroy her Threema identity on upgrade, since no second copy exists on the device.
 *
 * Why fallback to plaintext preferences is required:
 * [EncryptedSharedPreferences.create] can throw if Android Keystore keys are invalidated or corrupted.
 * If creating the encrypted preferences throws, we catch the exception, log a warning, and fall back
 * to the unencrypted SharedPreferences file. A phone keeping her key in a plaintext file is a lesser
 * fault than a launcher crashing on boot with no way in.
 */
class ThreemaIdentityRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

        val encryptedStore = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize EncryptedSharedPreferences; falling back to plaintext preferences", e)
            null
        }

        if (encryptedStore == null) {
            prefs = legacyPrefs
        } else {
            val encryptedId = encryptedStore.getString(KEY_THREEMA_ID, null)
            val encryptedKey = encryptedStore.getString(KEY_CLIENT_KEY, null)
            val hasEncryptedIdentity = !encryptedId.isNullOrBlank() && !encryptedKey.isNullOrBlank()

            val legacyId = legacyPrefs.getString(KEY_THREEMA_ID, null)
            val legacyKey = legacyPrefs.getString(KEY_CLIENT_KEY, null)
            val hasLegacyIdentity = !legacyId.isNullOrBlank() && !legacyKey.isNullOrBlank()

            if (!hasEncryptedIdentity && hasLegacyIdentity) {
                val group = legacyPrefs.getString(KEY_SERVER_GROUP, "d0") ?: "d0"
                val nickname = legacyPrefs.getString(KEY_NICKNAME, "gentle connection") ?: "gentle connection"

                // commit(), not apply(), and the old file is cleared only once it has returned
                // true. This is the single write in this app with no second copy anywhere on the
                // device: apply() defers the disk write and reports nothing, so a failure — or a
                // process killed in the moment between the two edits — would leave her key erased
                // from the file that had it and never written to the one that was meant to.
                val migrated = try {
                    encryptedStore.edit()
                        .putString(KEY_THREEMA_ID, legacyId)
                        .putString(KEY_CLIENT_KEY, legacyKey)
                        .putString(KEY_SERVER_GROUP, group)
                        .putString(KEY_NICKNAME, nickname)
                        .commit()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not move the Threema identity into the encrypted store", e)
                    false
                }

                if (migrated) {
                    legacyPrefs.edit().clear().apply()
                    prefs = encryptedStore
                } else {
                    // Go on reading the file that still holds her key. An empty encrypted store
                    // is indistinguishable from a phone nobody has set up yet, and the app would
                    // quietly start asking a caregiver to provision Threema all over again.
                    prefs = legacyPrefs
                }
            } else {
                prefs = encryptedStore
            }
        }
    }

    /**
     * The Threema ID last stored on this phone, kept outside the encrypted store and with no key
     * beside it. The ID is her public address and no secret; what it is for is to still be there
     * when the encrypted store cannot be opened, so that an empty store is recognised as hers
     * being unreadable rather than as a phone nobody has set up. See
     * [ThreemaStartupPolicy.mayRegisterNewIdentity].
     */
    private val marker: SharedPreferences =
        context.applicationContext.getSharedPreferences(MARKER_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Phones set up before the marker existed get it on their first start with this build,
        // as long as the identity can still be read then.
        getIdentity()?.let { rememberProvisioned(it.threemaId) }
    }

    /** The ID of an identity that was stored here and not deliberately removed, or null. */
    fun provisionedIdentityId(): String? = marker.getString(KEY_THREEMA_ID, null)

    private fun rememberProvisioned(threemaId: String) {
        if (marker.getString(KEY_THREEMA_ID, null) != threemaId) {
            marker.edit().putString(KEY_THREEMA_ID, threemaId).apply()
        }
    }

    fun hasIdentity(): Boolean {
        val id = prefs.getString(KEY_THREEMA_ID, null)
        val key = prefs.getString(KEY_CLIENT_KEY, null)
        return !id.isNullOrBlank() && !key.isNullOrBlank()
    }

    fun getIdentity(): ThreemaIdentity? {
        val id = prefs.getString(KEY_THREEMA_ID, null)?.trim()?.uppercase() ?: return null
        val key = prefs.getString(KEY_CLIENT_KEY, null)?.trim() ?: return null
        if (id.isEmpty() || key.isEmpty()) return null
        val group = prefs.getString(KEY_SERVER_GROUP, "d0") ?: "d0"
        val nickname = prefs.getString(KEY_NICKNAME, "gentle connection") ?: "gentle connection"
        return ThreemaIdentity(
            threemaId = id,
            clientKey = key,
            serverGroup = group,
            nickname = nickname
        )
    }

    fun saveIdentity(
        threemaId: String,
        clientKey: String,
        serverGroup: String = "d0",
        nickname: String = "gentle connection"
    ) {
        val cleanId = threemaId.trim().uppercase()
        val cleanKey = clientKey.trim()
        prefs.edit()
            .putString(KEY_THREEMA_ID, cleanId)
            .putString(KEY_CLIENT_KEY, cleanKey)
            .putString(KEY_SERVER_GROUP, serverGroup)
            .putString(KEY_NICKNAME, nickname)
            .apply()
        rememberProvisioned(cleanId)
    }

    /**
     * Change only the name her messages arrive under.
     *
     * Its own method because the nickname is the one part of an identity that is a decision rather
     * than a credential: it goes out with every message she sends and is what her family sees at
     * the top of it. Restoring an account should not silently rename her, and setting the name
     * should not require handling her client key.
     *
     * @return false when there is no identity to name.
     */
    fun setNickname(nickname: String): Boolean {
        val cleaned = nickname.trim()
        if (cleaned.isEmpty() || !hasIdentity()) return false
        prefs.edit().putString(KEY_NICKNAME, cleaned).apply()
        return true
    }

    /** Removes her identity on purpose — unlinking — which is what allows a fresh one again. */
    fun clearIdentity() {
        prefs.edit().clear().apply()
        marker.edit().clear().apply()
    }

    companion object {
        private const val TAG = "ThreemaIdentityRepo"
        private const val LEGACY_PREFS_NAME = "threema_identity_secure_prefs"
        private const val ENCRYPTED_PREFS_NAME = "threema_identity_encrypted_prefs"
        private const val KEY_THREEMA_ID = "threema_id"
        private const val KEY_CLIENT_KEY = "client_key"
        private const val KEY_SERVER_GROUP = "server_group"
        private const val KEY_NICKNAME = "nickname"
        private const val MARKER_PREFS_NAME = "threema_identity_marker"
    }
}
