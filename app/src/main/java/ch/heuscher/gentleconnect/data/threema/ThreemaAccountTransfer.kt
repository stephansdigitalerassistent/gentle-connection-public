package ch.heuscher.gentleconnect.data.threema

import android.content.Context
import android.util.Log
import ch.heuscher.gentleconnect.model.BackupPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Moving her Threema account on and off this phone.
 *
 * Three things a caregiver may need and could not do before: take a copy of the account, take the
 * account off this device, and put it back — or put it on a replacement phone. All three go
 * through libthreema's own ID backup, so what comes out here is the same string the real Threema
 * app produces and accepts. That matters more than it looks: an account that only this app can
 * restore is an account that dies with this app.
 *
 * The password is chosen by [BackupPassword] and shown once. Nothing here can recover it, and
 * nothing here stores it — a stored password would make the encryption ornamental.
 *
 * Runs the bridge in its own short-lived mode rather than talking to the running client: both
 * operations are offline, and import in particular has to work when there is no identity at all,
 * which is precisely when the normal bridge refuses to start.
 */
class ThreemaAccountTransfer(
    private val context: Context,
    private val identityRepository: ThreemaIdentityRepository
) {

    sealed class Export {
        /**
         * The backup string and the password that opens it. Both are shown to the caregiver
         * together, once, and only the string is safe to write to a file.
         */
        data class Ready(val backup: String, val password: String) : Export()
        data object NoIdentity : Export()
        data class Failed(val reason: String) : Export()
    }

    sealed class Import {
        /** The backup held the identity this phone already has. Nothing was written. */
        data class Unchanged(val threemaId: String) : Import()
        data class Restored(val threemaId: String) : Import()

        /**
         * The backup holds a different account from the one on this phone. Refused rather than
         * done, because doing it silently would disconnect her from every conversation she has
         * without anybody deciding to.
         */
        data class WouldReplace(val existing: String, val incoming: String) : Import()
        data class Failed(val reason: String) : Import()
    }

    /**
     * Encrypt this phone's identity into a backup string.
     *
     * The password comes back with it and is never persisted: whoever asked for the export has to
     * write it down now, and this is said in the screen that shows it.
     */
    suspend fun export(): Export = withContext(Dispatchers.IO) {
        val identity = identityRepository.getIdentity()
            ?: return@withContext Export.NoIdentity
        val binary = bridgeBinary() ?: return@withContext Export.Failed("bridge not found")

        val password = BackupPassword.generate()
        val output = runBridge(
            binary = binary,
            mode = "--export-identity",
            environment = mapOf(
                "THREEMA_IDENTITY" to identity.threemaId,
                "THREEMA_CLIENT_KEY" to identity.clientKey,
                "THREEMA_BACKUP_PASSWORD" to password
            ),
            expecting = "IdentityBackup"
        ) ?: return@withContext Export.Failed("the bridge produced no backup")

        Export.Ready(backup = output.getString("backup"), password = password)
    }

    /**
     * Read a backup string back onto this phone.
     *
     * Idempotent on purpose. Importing the backup of the account already here changes nothing and
     * says so, so a caregiver who is unsure whether the last attempt worked can simply do it
     * again — which is what people do, and it must not be the thing that breaks her messaging.
     */
    suspend fun import(
        backup: String,
        password: String,
        replaceExisting: Boolean = false
    ): Import = withContext(Dispatchers.IO) {
        val binary = bridgeBinary() ?: return@withContext Import.Failed("bridge not found")

        // Two spellings of one secret, tried in this order.
        //
        // Normalising is right for the passwords this app generates and shows once on paper:
        // spaces, dashes and lower case are how it got written down, not part of it. It is wrong
        // for a password a person chose in Threema's own app, where every character is meant —
        // "frühlingsfest" normalised is "FRÜHLINGSFEST", a different password. Doing only the first meant
        // no backup made outside this app could ever be restored, and the failure looked exactly
        // like a typo. Trying both costs one extra run of the bridge, and only when the first
        // spelling was wrong.
        val passwordsToTry = listOf(BackupPassword.normalise(password), password.trim()).distinct()

        val output = passwordsToTry.firstNotNullOfOrNull { candidate ->
            runBridge(
                binary = binary,
                mode = "--import-identity",
                environment = mapOf(
                    "THREEMA_BACKUP" to backup.trim(),
                    "THREEMA_BACKUP_PASSWORD" to candidate
                ),
                expecting = "IdentityImported"
            )
        } ?: return@withContext Import.Failed("wrong password, or not a Threema backup")

        val incoming = output.getString("identity")
        val clientKey = output.getString("client_key")
        val existing = identityRepository.getIdentity()

        when {
            existing == null -> {
                // No nickname to carry across: a backup holds the account, not the name it writes
                // under, so this is where "gentle connection" comes from until somebody sets it.
                // See ThreemaIdentityRepository.setNickname and the caregiver screen.
                identityRepository.saveIdentity(threemaId = incoming, clientKey = clientKey)
                Log.i(TAG, "Restored Threema identity $incoming")
                Import.Restored(incoming)
            }

            existing.threemaId == incoming && existing.clientKey.equals(clientKey, ignoreCase = true) ->
                Import.Unchanged(incoming)

            !replaceExisting -> Import.WouldReplace(existing = existing.threemaId, incoming = incoming)

            else -> {
                identityRepository.saveIdentity(
                    threemaId = incoming,
                    clientKey = clientKey,
                    nickname = existing.nickname
                )
                Log.i(TAG, "Replaced Threema identity with $incoming")
                Import.Restored(incoming)
            }
        }
    }

    /**
     * Take the account off this phone.
     *
     * Only the credentials go. Her messages stay where they are, because they are hers to read
     * whether or not this device can still send: a caregiver unlinking a phone that is being
     * replaced should not erase the conversations on it in the same gesture.
     *
     * Returns false when there was nothing to unlink, so the caller can say "already unlinked"
     * rather than claiming to have done something.
     *
     * An identity that was set up here but cannot be read counts as something to unlink: the
     * registrar refuses to register another while its marker stands, and tells the caregiver to
     * unlink. See [ThreemaStartupPolicy.hasSomethingToUnlink].
     */
    fun unlink(): Boolean {
        val somethingToUnlink = ThreemaStartupPolicy.hasSomethingToUnlink(
            hasIdentity = identityRepository.hasIdentity(),
            provisionedBefore = identityRepository.provisionedIdentityId() != null
        )
        if (!somethingToUnlink) return false
        identityRepository.clearIdentity()
        Log.i(TAG, "Threema identity cleared from this device")
        return true
    }

    /**
     * Run one of the bridge's short modes and return the line it was asked for.
     *
     * Secrets go through the environment, never argv: a process's command line is world-readable
     * through `/proc` and its environment is not — the same rule registration already follows.
     */
    private fun runBridge(
        binary: File,
        mode: String,
        environment: Map<String, String>,
        expecting: String
    ): JSONObject? = try {
        val builder = ProcessBuilder(listOf(binary.absolutePath, mode))
        environment.forEach { (name, value) -> builder.environment()[name] = value }
        val process = builder.start()

        // Drained on the side and thrown away. The bridge logs at TRACE and a full stderr pipe
        // would block it before it printed anything; the log is also no place for this stream.
        val stderr = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).forEachLine { }
            } catch (_: Exception) {
            }
        }
        stderr.start()

        var wanted: JSONObject? = null
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).forEachLine { raw ->
            val line = raw.removePrefix("BRIDGE-JSON: ").trim()
            if (line.startsWith("{")) {
                try {
                    val json = JSONObject(line)
                    if (json.optString("type") == expecting) wanted = json
                } catch (_: Exception) {
                    // Not our line, and never logged: this stream carries the client key.
                }
            }
        }
        process.waitFor()
        stderr.join(STDERR_DRAIN_TIMEOUT_MS)
        wanted
    } catch (e: Exception) {
        // The message can quote what failed, and what failed here holds an account.
        Log.w(TAG, "Bridge $mode failed: ${e.javaClass.simpleName}")
        null
    }

    private fun bridgeBinary(): File? =
        File(context.applicationInfo.nativeLibraryDir, "libthreema_bridge.so").takeIf { it.exists() }

    private companion object {
        const val TAG = "ThreemaAccountTransfer"
        const val STDERR_DRAIN_TIMEOUT_MS = 2_000L
    }
}
