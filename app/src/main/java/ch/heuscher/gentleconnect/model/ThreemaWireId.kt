package ch.heuscher.gentleconnect.model

import kotlin.random.Random

/**
 * The name a Threema message goes by on the wire.
 *
 * The app chooses these for its own outgoing messages, which it did not used to. The bridge
 * generated one at random and never reported it, so the app stored what it had sent under an id
 * of its own invention — `out_1786314227427_0` — and had no idea what Threema called it.
 *
 * That is invisible until somebody reacts. A reaction names the message it refers to, and the
 * ones her family react to are, overwhelmingly, the messages *she* wrote: a daughter putting a
 * heart on her mother's message is the whole point of the feature. Every one of those named a
 * message the app had no record of, and was dropped without trace.
 */
object ThreemaWireId {

    /** Eight bytes, which is sixteen hexadecimal characters. */
    const val HEX_LENGTH = 16

    /**
     * A fresh id.
     *
     * Random rather than sequential or time-based: a message id is visible to everyone she writes
     * to, and one that counted upwards would tell each of them how much she uses the telephone.
     */
    fun random(source: Random = Random.Default): String =
        (1..HEX_LENGTH).map { HEX[source.nextInt(HEX.size)] }.joinToString("")

    /** Whether this is a usable Threema message id, in the form the bridge prints and reads. */
    fun isValid(id: String?): Boolean {
        val candidate = id ?: return false
        return candidate.length == HEX_LENGTH && candidate.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private val HEX = ('0'..'9') + ('a'..'f')
}
