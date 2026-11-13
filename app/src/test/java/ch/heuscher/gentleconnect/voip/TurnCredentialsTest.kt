package ch.heuscher.gentleconnect.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class TurnCredentialsTest {

    @Test
    fun testValidCredentials() {
        val futureDate = Date(System.currentTimeMillis() + 3600 * 1000L)
        val creds = TurnCredentials(
            turnUrls = listOf("turn:turn.threema.ch:443?transport=udp"),
            turnUrlsDualStack = listOf("turn:turn6.threema.ch:443?transport=udp"),
            turnUsername = "user123",
            turnPassword = "pass123_secret",
            expirationDate = futureDate
        )

        assertTrue(creds.isValid())
        assertEquals(2, creds.allTurnUrls().size)
        assertTrue(creds.allTurnUrls().contains("turn:turn.threema.ch:443?transport=udp"))
        assertTrue(creds.allTurnUrls().contains("turn:turn6.threema.ch:443?transport=udp"))
    }

    @Test
    fun testExpiredCredentials() {
        val pastDate = Date(System.currentTimeMillis() - 1000L)
        val creds = TurnCredentials(
            turnUrls = listOf("turn:turn.threema.ch:443"),
            turnUrlsDualStack = emptyList(),
            turnUsername = "user123",
            turnPassword = "pass123",
            expirationDate = pastDate
        )

        assertFalse(creds.isValid())
    }

    @Test
    fun testCredentialsNearExpirationWithinMargin() {
        // Expiration in 30 seconds, margin is 60 seconds -> should be considered invalid (needs refetch)
        val nearExpiration = Date(System.currentTimeMillis() + 30 * 1000L)
        val creds = TurnCredentials(
            turnUrls = listOf("turn:turn.threema.ch:443"),
            turnUrlsDualStack = emptyList(),
            turnUsername = "user123",
            turnPassword = "pass123",
            expirationDate = nearExpiration
        )

        assertFalse(creds.isValid(marginSeconds = 60))
    }

    @Test
    fun testMissingUrlsOrCredentials() {
        val futureDate = Date(System.currentTimeMillis() + 3600 * 1000L)
        val emptyUrls = TurnCredentials(
            turnUrls = emptyList(),
            turnUrlsDualStack = emptyList(),
            turnUsername = "user123",
            turnPassword = "pass123",
            expirationDate = futureDate
        )
        assertFalse(emptyUrls.isValid())

        val emptyUser = TurnCredentials(
            turnUrls = listOf("turn:turn.threema.ch:443"),
            turnUrlsDualStack = emptyList(),
            turnUsername = "",
            turnPassword = "pass123",
            expirationDate = futureDate
        )
        assertFalse(emptyUser.isValid())
    }
}
