package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telephone numbers, as WhatsApp writes them and as her address book writes them.
 *
 * The failure being guarded is specific and silent: a JID assembled wrongly is accepted by
 * WhatsApp's servers and delivered to nobody, so a reply she watched leave the screen never
 * arrives and nothing anywhere reports a problem.
 */
class WhatsAppNumberTest {

    @Test
    fun `a personal jid gives up its number`() {
        assertEquals("41791234567", WhatsAppNumber.numberFromJid("41791234567@s.whatsapp.net"))
    }

    @Test
    fun `answering from a laptop is still the same person`() {
        // A companion device appends its number to the user part. Keeping it would make the same
        // daughter, writing from her laptop, a stranger her mother's address book has never seen.
        assertEquals("41791234567", WhatsAppNumber.numberFromJid("41791234567:12@s.whatsapp.net"))
    }

    @Test
    fun `a group is not a person and does not pretend to be one`() {
        assertNull(WhatsAppNumber.numberFromJid("1234567890-1234567890@g.us"))
        assertTrue(WhatsAppNumber.isGroup("1234567890-1234567890@g.us"))
        assertFalse(WhatsAppNumber.isGroup("41791234567@s.whatsapp.net"))
        assertFalse(WhatsAppNumber.isGroup(null))
    }

    @Test
    fun `rubbish gives nothing rather than something plausible`() {
        assertNull(WhatsAppNumber.numberFromJid(null))
        assertNull(WhatsAppNumber.numberFromJid(""))
        assertNull(WhatsAppNumber.numberFromJid("41791234567"))
        assertNull(WhatsAppNumber.numberFromJid("@s.whatsapp.net"))
    }

    @Test
    fun `a number written any of the ways people write it makes one jid`() {
        val expected = "41791234567@s.whatsapp.net"

        assertEquals(expected, WhatsAppNumber.jidForNumber("+41791234567"))
        assertEquals(expected, WhatsAppNumber.jidForNumber("+41 79 123 45 67"))
        assertEquals(expected, WhatsAppNumber.jidForNumber("0041791234567"))
        assertEquals(expected, WhatsAppNumber.jidForNumber("+41 (0)79 123 45 67".replace("(0)", "")))
    }

    @Test
    fun `something too short to be a telephone number makes no jid at all`() {
        // A malformed JID is accepted by the server and delivered nowhere, so this returns null
        // and lets the caller report a failure rather than inventing a recipient.
        assertNull(WhatsAppNumber.jidForNumber("123"))
        assertNull(WhatsAppNumber.jidForNumber(""))
        assertNull(WhatsAppNumber.jidForNumber(null))
        assertNull(WhatsAppNumber.jidForNumber("keine nummer"))
    }

    @Test
    fun `a single leading zero is a trunk prefix and is left alone`() {
        // 00 is the written form of "+" and comes off. One 0 is national dialling and stays: a
        // country code guessed for somebody who moved abroad sends her message to a stranger.
        assertEquals("41791234567", WhatsAppNumber.digitsOf("0041791234567"))
        assertEquals("0791234567", WhatsAppNumber.digitsOf("079 123 45 67"))
    }

    @Test
    fun `the same person written two ways is recognised as one person`() {
        assertTrue(WhatsAppNumber.sameSubscriber("+41 79 123 45 67", "41791234567"))
        assertTrue(WhatsAppNumber.sameSubscriber("079 123 45 67", "41791234567"))
        assertTrue(WhatsAppNumber.sameSubscriber("0041791234567", "+41791234567"))
    }

    @Test
    fun `two different people are not merged`() {
        assertFalse(WhatsAppNumber.sameSubscriber("41791234567", "41791234568"))
        assertFalse(WhatsAppNumber.sameSubscriber("41791234567", null))
        assertFalse(WhatsAppNumber.sameSubscriber(null, null))
    }

    @Test
    fun `a Swiss mobile and a Berlin landline are not the same person`() {
        // The regression. Suffix matching on the last seven digits made these one person, because
        // 079 123 45 67 and 49 30 123 45 67 both end in 1234567 — so a reply meant for her
        // daughter would have gone to a stranger in Berlin, and been reported as sent.
        assertFalse(WhatsAppNumber.sameSubscriber("079 123 45 67", "49301234567"))
    }

    @Test
    fun `the country code is the only thing allowed to differ`() {
        // Same subscriber digits, but the gap is far too wide to be a country code — so these are
        // two different numbers that happen to end alike, not one number written two ways.
        assertFalse(WhatsAppNumber.sameSubscriber("791234567", "999888791234567"))
    }

    @Test
    fun `a number too short to compare safely never matches`() {
        // Suffix matching is only safe with enough digits behind it. Below that it would start
        // declaring unrelated people the same, which is how a message reaches a stranger.
        assertFalse(WhatsAppNumber.sameSubscriber("123456", "41791123456"))
    }

    /**
     * The key form of a number, which has to agree with [WhatsAppNumber.sameSubscriber] or a name
     * looked up by it is a name found for the wrong person — or, worse, not found at all, which
     * is how a woman in her address book was shown as "." for two days.
     */
    @Test
    fun `two ways of writing one number make one key`() {
        val international = WhatsAppNumber.subscriberKey("+41 79 555 01 19")
        assertEquals(international, WhatsAppNumber.subscriberKey("41795550119"))
        assertEquals(international, WhatsAppNumber.subscriberKey("0041795550119"))
        // The trunk zero is how a country tells its own subscribers to dial each other.
        assertEquals(international, WhatsAppNumber.subscriberKey("079 555 01 19"))
    }

    @Test
    fun `two different people do not share a key`() {
        assertNotEquals(
            WhatsAppNumber.subscriberKey("+41795550119"),
            WhatsAppNumber.subscriberKey("+41785550121")
        )
    }

    /** Too short to identify anybody, so it identifies nobody rather than the wrong somebody. */
    @Test
    fun `a number too short to be a person has no key`() {
        assertNull(WhatsAppNumber.subscriberKey("12345"))
        assertNull(WhatsAppNumber.subscriberKey(""))
        assertNull(WhatsAppNumber.subscriberKey(null))
    }
}
