package ch.heuscher.gentleconnect.data.threema

import android.content.Context
import android.util.Log
import ch.heuscher.gentleconnect.data.Messengers
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Registers a Threema identity for this phone, once, and hands it to [ThreemaIdentityRepository].
 *
 * A Threema ID is not a session — it is the address her contacts have for her. Creating a new one
 * on every launch would mean every message she sent came from a stranger, so registration happens
 * exactly once and the result is persisted; every later start reuses it. [ensureIdentity] is
 * therefore a no-op whenever an identity is already stored, and that is the normal case.
 *
 * The client key that comes back IS the account. It is read off the subprocess's stdout, written
 * straight to app-private storage, and never logged — not at debug level, not in an error path.
 */
class ThreemaRegistrar(
    private val context: Context,
    private val identityRepository: ThreemaIdentityRepository = Messengers.threemaIdentity(context)
) {

    sealed class Result {
        /** An identity was already stored; nothing was created. */
        data object AlreadyRegistered : Result()

        /** A new identity was registered and persisted. */
        data class Registered(val threemaId: String) : Result()

        data class Failed(val reason: String) : Result()
    }

    suspend fun ensureIdentity(): Result = withContext(Dispatchers.IO) {
        if (identityRepository.hasIdentity()) {
            return@withContext Result.AlreadyRegistered
        }
        val previous = identityRepository.provisionedIdentityId()
        if (!ThreemaStartupPolicy.mayRegisterNewIdentity(hasIdentity = false, provisionedBefore = previous != null)) {
            Log.e(TAG, "Threema identity $previous was set up here but cannot be read; not registering another")
            return@withContext Result.Failed("identity $previous cannot be read; restore it or unlink it")
        }

        val binary = getSubprocessExecutable()
        if (binary == null || !binary.exists()) {
            return@withContext Result.Failed("Executable libthreema_bridge.so not found")
        }

        try {
            // Both the license and the device id go in the environment rather than argv: a
            // process's command line is world-readable through /proc, its environment is not.
            val pb = ProcessBuilder(listOf(binary.absolutePath, "--create-identity"))
            pb.environment()["THREEMA_LICENSE_KEY"] = LICENSE_KEY
            pb.environment()["THREEMA_DEVICE_ID"] = deviceId()
            val process = pb.start()

            // Drain stderr on the side. The bridge logs at TRACE, and a full stderr pipe would
            // block the subprocess before it ever printed the identity on stdout.
            val stderr = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).forEachLine { }
                } catch (_: Exception) {
                }
            }
            stderr.start()

            var created: JSONObject? = null
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).forEachLine { raw ->
                val line = raw.removePrefix("BRIDGE-JSON: ").trim()
                if (line.startsWith("{")) {
                    try {
                        val json = JSONObject(line)
                        if (json.optString("type") == "IdentityCreated") created = json
                    } catch (_: Exception) {
                        // Not our line. Never log it — this stream carries the client key.
                    }
                }
            }
            process.waitFor()
            stderr.join(STDERR_DRAIN_TIMEOUT_MS)

            val result = created
                ?: return@withContext Result.Failed("Bridge exited ${process.exitValue()} without an identity")

            val threemaId = result.getString("identity")
            identityRepository.saveIdentity(
                threemaId = threemaId,
                clientKey = result.getString("client_key"),
                serverGroup = result.optString("server_group", "d0")
            )
            Log.i(TAG, "Registered Threema identity $threemaId")
            Result.Registered(threemaId)
        } catch (e: Exception) {
            // The message may quote the failing request, so report the type only.
            Result.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * A stable per-install id for the registration request. Reuses the same value the Firestore
     * device document uses, so one phone stays one device across both backends.
     */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: DEFAULT_DEVICE_ID
    }

    private fun getSubprocessExecutable(): File? {
        val nativeSo = File(context.applicationInfo.nativeLibraryDir, "libthreema_bridge.so")
        return if (nativeSo.exists()) nativeSo else null
    }

    companion object {
        private const val TAG = "ThreemaRegistrar"

        /**
         * The Threema serial license this build registers under. It has to be present in the APK
         * for registration to work at all, so keeping it out of git would buy nothing — the same
         * key is committed in treema-light, which is where it came from.
         */
        private const val LICENSE_KEY = "G55TH-RBV2S"

        private const val DEVICE_PREFS_NAME = "gentle_connection_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val DEFAULT_DEVICE_ID = "gentle-connection"
        private const val STDERR_DRAIN_TIMEOUT_MS = 2_000L
    }
}
