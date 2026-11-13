package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.telecom.CallStage
import ch.heuscher.gentleconnect.telecom.PhoneCall
import ch.heuscher.gentleconnect.telecom.whoToShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ringing screen says, and when the green circle is on it.
 *
 * Worth a test rather than a glance: both answers are decided at the moment the telephone is
 * ringing, which is the one moment nobody can go and check.
 */
class PhoneCallTest {

    private fun call(
        number: String = "+41791234567",
        name: String? = null,
        stage: CallStage = CallStage.RINGING,
        incoming: Boolean = true
    ) = PhoneCall(
        number = number,
        name = name,
        photoUri = null,
        stage = stage,
        isIncoming = incoming
    )

    private val withheld = "Number withheld"

    @Test
    fun `a caller in her address book is shown by name`() {
        assertEquals("Anna", call(name = "Anna").whoToShow(withheld))
    }

    @Test
    fun `a caller she does not know is shown as the number`() {
        assertEquals("+41791234567", call().whoToShow(withheld))
    }

    @Test
    fun `a withheld number is named as withheld, not left blank`() {
        // The distinction that matters: a screen with nothing on it beside a green circle is the
        // most likely thing on this phone to get a scam call answered.
        assertEquals(withheld, call(number = "").whoToShow(withheld))
    }

    @Test
    fun `a blank name does not beat the number`() {
        // Providers do return an empty display name rather than none at all, and an empty string
        // would otherwise win simply for not being null.
        assertEquals("+41791234567", call(name = "   ").whoToShow(withheld))
    }

    @Test
    fun `a name is trimmed before it is shown`() {
        assertEquals("Anna", call(name = "  Anna  ").whoToShow(withheld))
    }

    @Test
    fun `a nickname in quotes is left off the call screen, as it is everywhere else`() {
        // Her address book writes people down as `Tobias "Mü" Müller`. Every other screen shows
        // that as "Tobias Müller"; this one used to show all of it, so the same person had two
        // names depending on whether he was writing or ringing. It is also the longest form of
        // the name, and this screen prints it at 38sp.
        assertEquals("Tobias Müller", call(name = "Tobias \"Mü\" Müller").whoToShow(withheld))
    }

    @Test
    fun `an incoming call that is ringing can be answered`() {
        assertTrue(call(stage = CallStage.RINGING, incoming = true).canAnswer)
    }

    @Test
    fun `her own outgoing call offers no way to answer it`() {
        assertFalse(call(stage = CallStage.DIALLING, incoming = false).canAnswer)
        assertFalse(call(stage = CallStage.RINGING, incoming = false).canAnswer)
    }

    @Test
    fun `a call already being talked on cannot be answered again`() {
        assertFalse(call(stage = CallStage.TALKING, incoming = true).canAnswer)
    }

    @Test
    fun `a finished call offers no green circle`() {
        // The screen lingers for a moment after a call ends. A green circle on it would be an
        // offer to answer something that has gone.
        assertFalse(call(stage = CallStage.OVER, incoming = true).canAnswer)
    }
}
