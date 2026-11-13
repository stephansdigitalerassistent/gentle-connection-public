package ch.heuscher.gentleconnect.data

import java.io.File
import java.security.MessageDigest

/**
 * What makes two downloaded pictures the same picture: their contents.
 *
 * Found on her phone. On 16 August Hans Müller put up nine statuses between 08:15 and 08:23, and
 * they were three photographs, each posted three times. Nothing upstream can see it — every copy
 * arrives with its own WhatsApp id and lands in its own file, so to the messenger, to the store
 * and to every id-based rule in this app they are nine different things. Only the bytes say
 * otherwise, and they say it flatly: the files are identical.
 *
 * Identity, never resemblance. Two photographs of the same vase taken a second apart are two
 * photographs and both are hers to see; deciding they are "the same" would be this app throwing
 * away something somebody sent her because it looked a bit like something else. Only an exact
 * match is a repeat, and an exact match is not a judgement call.
 *
 * ### Why it is cached, and why that is safe
 *
 * A feed is rebuilt whenever anything arrives, and hashing four hundred files each time would be
 * sixty megabytes of reading to draw a list. So each path is read once and remembered. That is
 * only correct because these files never change: a downloaded status is written once under a name
 * made from its message id and is never rewritten — the app either has it or has not.
 */
object MediaFingerprint {

    /**
     * How much of a file is read. A photograph is well under this and is therefore hashed whole;
     * a film is not, and does not need to be — a film sharing its length to the byte *and* its
     * first megabyte with another film is the same film.
     */
    private const val BYTES_READ = 1024 * 1024

    private val known = HashMap<String, String?>()

    /**
     * A key two identical files share and two different ones do not, or null when there is no
     * file to read.
     *
     * The length is part of the key, so a film cut short is never mistaken for the whole of it on
     * the strength of a matching first megabyte.
     */
    @Synchronized
    fun keyOf(path: String?): String? {
        if (path.isNullOrBlank()) return null
        // containsKey, not getOrPut: "this file could not be read" has to be remembered as firmly
        // as a hash is, or every rebuild goes back to the disk for the one that is not there.
        if (known.containsKey(path)) return known[path]

        val key = runCatching {
            val file = File(path)
            if (!file.isFile || !file.canRead() || file.length() == 0L) return@runCatching null

            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (read < BYTES_READ) {
                    val wanted = minOf(buffer.size.toLong(), BYTES_READ - read).toInt()
                    val got = stream.read(buffer, 0, wanted)
                    if (got <= 0) break
                    digest.update(buffer, 0, got)
                    read += got
                }
            }
            file.length().toString() + ":" + digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

        known[path] = key
        return key
    }

    /** For tests, which must not inherit another test's disk. */
    @Synchronized
    fun forget() = known.clear()
}
