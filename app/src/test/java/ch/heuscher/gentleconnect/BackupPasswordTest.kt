package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.BackupPassword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPasswordTest {

    @Test
    fun `is grouped in fours and holds sixteen characters`() {
        val password = BackupPassword.generate()
        assertEquals(4, password.split("-").size)
        password.split("-").forEach { assertEquals(4, it.length) }
        assertEquals(BackupPassword.LENGTH, BackupPassword.normalise(password).length)
    }

    /** The characters that are read back as one another are the ones a written-down password loses. */
    @Test
    fun `never contains a character that is read as another`() {
        repeat(200) {
            val password = BackupPassword.normalise(BackupPassword.generate())
            listOf('I', 'L', 'O', 'U', '0', '1').forEach { confusable ->
                assertFalse("$password contains $confusable", password.contains(confusable))
            }
        }
    }

    @Test
    fun `two passwords are not the same password`() {
        val many = (1..200).map { BackupPassword.generate() }.toSet()
        assertEquals(200, many.size)
    }

    /**
     * The whole alphabet has to be reachable. A generator that quietly used two thirds of it would
     * still look random and still pass every other test here.
     */
    @Test
    fun `uses every letter of its alphabet`() {
        val seen = (1..500).flatMap { BackupPassword.normalise(BackupPassword.generate()).toList() }.toSet()
        assertEquals(30, seen.size)
    }

    /** Bytes at or above 240 must be discarded rather than folded, or the alphabet's start wins. */
    @Test
    fun `rejects the byte values that would bias the alphabet`() {
        // 240..255 would all fold onto the first sixteen letters; only the 5 and the 35 count.
        val bytes = byteArrayOf(250.toByte(), 5, 245.toByte(), 35, 240.toByte(), 61, 255.toByte(), 91)
        var call = 0
        val password = BackupPassword.generate { _ -> if (call++ == 0) bytes else bytes }
        val cleaned = BackupPassword.normalise(password)
        // 5 and 35 both land on 'F', 61 and 91 both on 'B'; 250, 245, 240 and 255 are discarded.
        // If they were folded instead of discarded the password would contain other letters.
        assertEquals(BackupPassword.LENGTH, cleaned.length)
        assertTrue("unbiased bytes only: $cleaned", cleaned.all { it == 'F' || it == 'B' })
    }

    @Test
    fun `reads back a password however it was written down`() {
        val password = BackupPassword.generate()
        val written = password.lowercase().replace("-", " ")
        assertEquals(BackupPassword.normalise(password), BackupPassword.normalise(written))
    }

    /** No character is guessed into another: a typed O is wrong, not silently repaired. */
    @Test
    fun `does not repair a character that is not in the alphabet`() {
        assertFalse(BackupPassword.looksWellFormed("ABCD-EFGH-JKMN-PQRO"))
        assertFalse(BackupPassword.looksWellFormed("ABCD-EFGH-JKMN"))
        assertTrue(BackupPassword.looksWellFormed(BackupPassword.generate()))
    }
}
