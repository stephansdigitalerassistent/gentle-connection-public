package ch.heuscher.gentleconnect.model

import java.security.SecureRandom

/**
 * The password an export is encrypted with, chosen here rather than by whoever is exporting.
 *
 * A caregiver asked to invent a password for a once-a-year account backup invents a bad one, or a
 * good one they will not have when it matters. So the exporter picks it, shows it once, and the
 * only job left is to write it down. Nothing in this app can recover it: the backup is encrypted
 * by libthreema with this password and by nothing else, so a lost password is a lost account.
 *
 * Shaped to be copied off a screen onto paper and typed back in months later, by someone who may
 * not see well:
 *
 *  - the alphabet leaves out `I`, `L`, `O`, `U`, `0` and `1`, the characters that are read back
 *    as each other — a backup that fails because a 0 was written as an O is indistinguishable
 *    from a corrupted one;
 *  - it is grouped in fours, because a run of sixteen characters is copied wrongly;
 *  - it is upper case, so nobody has to record whether a letter was capital.
 *
 * Sixteen characters out of an alphabet of thirty is a little over 78 bits, which is far past
 * anything worth attacking for a file that never leaves her phone unless a caregiver moves it.
 */
object BackupPassword {

    /** No `I`, `L`, `O`, `U`, `0` or `1`. `U` goes too, so no group can spell an unfortunate word. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    const val GROUP_LENGTH = 4
    const val GROUPS = 4

    /** How many characters the password has, ignoring the dashes between groups. */
    const val LENGTH = GROUP_LENGTH * GROUPS

    /**
     * A new password, as `XXXX-XXXX-XXXX-XXXX`.
     *
     * [randomBytes] exists so a test can hand this deterministic bytes; nothing but a test should
     * pass it. The default is [SecureRandom], because a password drawn from the ordinary random
     * generator is a password an attacker can draw again.
     */
    fun generate(randomBytes: (Int) -> ByteArray = ::secureBytes): String {
        val chars = StringBuilder(LENGTH)
        while (chars.length < LENGTH) {
            // Rejection sampling. Taking a byte modulo thirty would make the first sixteen letters
            // of the alphabet likelier than the rest, which quietly costs a few bits of the
            // strength this is supposed to have.
            for (byte in randomBytes(LENGTH)) {
                if (chars.length == LENGTH) break
                val value = byte.toInt() and 0xFF
                if (value >= LARGEST_UNBIASED) continue
                chars.append(ALPHABET[value % ALPHABET.length])
            }
        }
        return chars.chunked(GROUP_LENGTH).joinToString("-")
    }

    /**
     * What was typed, in the form [generate] produced.
     *
     * Dashes and spaces are how it was written down, not part of the secret, so they go. Case goes
     * the same way. Nothing else is corrected: no character is guessed into another, because
     * guessing that a typed `O` meant `Q` would silently try a different password and report a
     * good backup as unreadable.
     */
    fun normalise(typed: String): String =
        typed.filterNot { it == '-' || it.isWhitespace() }.uppercase()

    /** Whether [typed] could be a password this object produced. Not whether it is the right one. */
    fun looksWellFormed(typed: String): Boolean {
        val cleaned = normalise(typed)
        return cleaned.length == LENGTH && cleaned.all { it in ALPHABET }
    }

    /** The largest byte value that can be reduced into the alphabet without favouring its start. */
    private val LARGEST_UNBIASED = (256 / ALPHABET.length) * ALPHABET.length

    private fun secureBytes(count: Int): ByteArray =
        ByteArray(count).also { SecureRandom().nextBytes(it) }
}
