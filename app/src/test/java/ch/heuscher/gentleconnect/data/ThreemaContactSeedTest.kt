package ch.heuscher.gentleconnect.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreemaContactSeedTest {

    @Test
    fun `identities are trimmed, upper-cased and listed once`() {
        assertEquals(
            listOf("ECHO1001", "ABCD1234"),
            ThreemaContactSeed.identities(listOf(" echo1001", "ABCD1234", "ECHO1001 ", "abcd1234"))
        )
    }

    @Test
    fun `anything that is not eight characters is left out`() {
        assertEquals(
            listOf("ABCD1234"),
            ThreemaContactSeed.identities(listOf("ABCD1234", "", "ABC", "ABCD12345", null))
        )
    }
}
