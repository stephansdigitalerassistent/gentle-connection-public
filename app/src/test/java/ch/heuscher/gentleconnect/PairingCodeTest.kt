package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `generatePairingCode produces 6-character uppercase alphanumeric code`() {
        val code = RemoteSettingsMapper.generatePairingCode()
        assertEquals(6, code.length)
        assertTrue(code.all { it in 'A'..'Z' || it in '0'..'9' })
    }

    @Test
    fun `generatePairingCode generates unique codes`() {
        val code1 = RemoteSettingsMapper.generatePairingCode()
        val code2 = RemoteSettingsMapper.generatePairingCode()
        assertNotEquals(code1, code2)
    }

    @Test
    fun `isPairingCodeExpired correctly identifies unexpired vs expired timestamps`() {
        val now = 1_000_000L
        val unexpiredAt = now + RemoteSettingsMapper.PAIRING_CODE_VALIDITY_MS
        val expiredAt = now - 1000L

        assertFalse(RemoteSettingsMapper.isPairingCodeExpired(unexpiredAt, now))
        assertTrue(RemoteSettingsMapper.isPairingCodeExpired(expiredAt, now))
        assertTrue(RemoteSettingsMapper.isPairingCodeExpired(now, now)) // Equal timestamp counts as expired
    }
}
