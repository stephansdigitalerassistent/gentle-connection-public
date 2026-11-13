package ch.heuscher.gentleconnect.util

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Keeps the app's own log on disk, so a fault can still be read hours after it happened.
 *
 * The system log is a ring buffer, and Samsung caps it at 5 MiB (`logcat -G` is refused above
 * that). With the messenger bridges talking, that covers well under an hour; by the time anybody
 * asks "what happened this afternoon" the lines are gone. So the app runs its own `logcat` for
 * the whole life of its process and lets logcat rotate the file itself.
 *
 * An app without READ_LOGS only ever sees its own uid's lines, so this records the app and the
 * bridge processes it starts and nothing of anybody else's.
 *
 * The files are in the app's external files directory,
 * `/sdcard/Android/data/ch.heuscher.gentleconnect/files/logs`, so `adb pull` reads them on any
 * build. They used to be in the private directory, where only `run-as` reaches — and `run-as`
 * only works on a debug build, which her phone has only until Play next updates it. No other app
 * can read that directory from Android 11 on; adb, or a computer she plugs the unlocked phone
 * into, can — the same as Threema's and WhatsApp's own folders beside it.
 */
object LogRecorder {

    private const val TAG = "LogRecorder"

    /** Size of one file in KiB. logcat rotates to `app.log.1` and so on when it is reached. */
    private const val FILE_SIZE_KIB = 5 * 1024

    /**
     * How many rotated files are kept besides the live one: ten files of 5 MiB, about a day or
     * more of this app's logging, and nothing to the phone's storage.
     */
    private const val ROTATED_FILES = 9

    /**
     * The logcat command line.
     *
     * [sinceMillis] is when the previous process last wrote to the file. Without it every start
     * would copy the whole ring buffer in again, and the same lines would appear two or three
     * times. With it, the lines the previous process wrote just before it died are still there, and
     * nothing is written twice. Null for a first start, where the whole ring buffer is wanted.
     */
    fun command(logFile: String, sinceMillis: Long?): List<String> = buildList {
        addAll(listOf("/system/bin/logcat", "-v", "threadtime", "-f", logFile))
        addAll(listOf("-r", FILE_SIZE_KIB.toString(), "-n", ROTATED_FILES.toString()))
        if (sinceMillis != null && sinceMillis > 0) {
            add("-T")
            add(String.format(Locale.ROOT, "%d.%03d", sinceMillis / 1000, sinceMillis % 1000))
        }
    }

    /** Starts the recorder. Once per process, from [android.app.Application.onCreate]. */
    fun start(context: Context) {
        runCatching {
            // Private storage only if the external one is unavailable (it is not removable storage
            // here, so in practice it never is).
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "logs").apply { mkdirs() }
            val file = File(dir, "app.log")
            val since = file.takeIf { it.exists() }?.lastModified()
            ProcessBuilder(command(file.path, since))
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
            // Android kills the process group when the app dies, so this logcat goes with it.
        }.onFailure { Log.w(TAG, "could not start the log recorder", it) }
    }
}
