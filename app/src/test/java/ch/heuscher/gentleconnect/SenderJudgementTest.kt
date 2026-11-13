package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.KnownSender
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SenderJudgement
import ch.heuscher.gentleconnect.model.SenderKind
import ch.heuscher.gentleconnect.model.namesSomebodyToWriteTo
import ch.heuscher.gentleconnect.model.senderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who counts as a person once somebody looking after her has had their say.
 *
 * Invisible from her side when it goes wrong, which is why it is worth testing at all: a
 * correction whose key did not match is a phone that stays silent for exactly the message she was
 * waiting for, and nothing on the screen says a switch was ever thrown.
 */
class SenderJudgementTest {

    private fun message(
        id: String = "1",
        address: String?,
        name: String = "Praxis Dr. Meier",
        direction: MessageDirection = MessageDirection.INCOMING,
        contactId: String? = null
    ) = Message(
        id = id,
        senderName = name,
        senderAddress = address,
        contactId = contactId,
        text = "Ihr Termin ist am Dienstag um 10 Uhr.",
        timestamp = 1_700_000_000_000L,
        sourcePackage = "com.google.android.apps.messaging",
        direction = direction
    )

    @Test
    fun `nothing is corrected until somebody corrects it`() {
        val surgery = message(address = "4144")
        assertEquals(SenderKind.ORGANISATION, surgery.senderKind)
        assertFalse(SenderJudgement.isTreatedAsPerson(surgery, emptySet()))
    }

    @Test
    fun `a corrected sender is a person on her screen`() {
        val corrected = SenderJudgement.treatedAsPeople(listOf("4144"))
        val surgery = message(address = "4144")

        assertTrue(SenderJudgement.isTreatedAsPerson(surgery, corrected))
        assertEquals(SenderKind.PERSON, surgery.copy(treatedAsPerson = true).senderKind)
    }

    @Test
    fun `a correction reaches the same number spelt another way`() {
        // Published as the network handed it over, ticked on the website, and the next message
        // arrives spelt by a different app. A key that only matched itself would be a switch that
        // appears to do nothing.
        val corrected = SenderJudgement.treatedAsPeople(listOf("+41 44 123 45 67"))

        assertTrue(SenderJudgement.isTreatedAsPerson(message(address = "+41441234567"), corrected))
        assertTrue(SenderJudgement.isTreatedAsPerson(message(address = "+41-44-123-45-67"), corrected))
        assertTrue(SenderJudgement.isTreatedAsPerson(message(address = "PRAXIS"), SenderJudgement.treatedAsPeople(listOf("praxis"))))
    }

    @Test
    fun `two different numbers stay two different senders`() {
        val corrected = SenderJudgement.treatedAsPeople(listOf("+41441234567"))
        assertFalse(SenderJudgement.isTreatedAsPerson(message(address = "0441234567"), corrected))
    }

    @Test
    fun `rubbish in the list corrects nobody`() {
        val corrected = SenderJudgement.treatedAsPeople(listOf(null, "", "   "))
        assertTrue(corrected.isEmpty())
        assertFalse(SenderJudgement.isTreatedAsPerson(message(address = null), corrected))
        assertFalse(SenderJudgement.isTreatedAsPerson(message(address = ""), corrected))
    }

    @Test
    fun `a correction never makes a sender answerable`() {
        // The whole of the safety of this feature. There is nobody behind "Reinigung!", and a
        // reply field there is her writing an answer that goes nowhere.
        val laundry = message(address = "Reinigung!").copy(treatedAsPerson = true)

        assertEquals(SenderKind.PERSON, laundry.senderKind)
        assertFalse(laundry.namesSomebodyToWriteTo)
        // And the check is still awake: her sister, on the same channel, can be written to. Without
        // this the assertion above would also pass on a phone that had stopped replying to anybody.
        assertTrue(message(address = "+41791234567", name = "Anna").namesSomebodyToWriteTo)
    }

    @Test
    fun `only the companies are offered, and only once each`() {
        val known = SenderJudgement.known(
            listOf(
                message(id = "1", address = "4144", name = "Praxis Dr. Meier"),
                message(id = "2", address = "41 44", name = "Praxis Dr. Meier"),
                message(id = "3", address = "+41791234567", name = "Anna"),
                message(id = "4", address = "Apotheke", name = "Apotheke")
            )
        )

        // By name, so the page does not reorder itself between visits: Apotheke before Praxis.
        assertEquals(
            listOf(KnownSender("apotheke", "Apotheke"), KnownSender("4144", "Praxis Dr. Meier")),
            known
        )
    }

    @Test
    fun `somebody in her address book is never offered`() {
        // Already a person, so there is nothing to correct — and her family's numbers have no
        // business on a website because of a switch nobody needs.
        val known = SenderJudgement.known(
            listOf(message(address = "999", name = "Anna", contactId = "contact-7"))
        )
        assertTrue(known.isEmpty())
    }

    @Test
    fun `her own messages are not senders`() {
        val known = SenderJudgement.known(
            listOf(message(address = "4144", direction = MessageDirection.OUTGOING))
        )
        assertTrue(known.isEmpty())
    }

    @Test
    fun `a sender already corrected stays on the list`() {
        // Read the other way round it would be a switch that vanishes the moment it is used, and
        // a caregiver who ticked the wrong row could never untick it.
        val corrected = message(address = "4144").copy(treatedAsPerson = true)
        assertEquals(listOf(KnownSender("4144", "Praxis Dr. Meier")), SenderJudgement.known(listOf(corrected)))
    }

    @Test
    fun `the companies stay until somebody switches them off`() {
        val messages = listOf(
            message(id = "1", address = "Reinigung!", name = "Reinigung!"),
            message(id = "2", address = "+41791234567", name = "Anna")
        )
        assertEquals(messages, SenderJudgement.shown(messages, showCompanies = true))
    }

    @Test
    fun `switched off, only the people are left`() {
        val messages = listOf(
            message(id = "1", address = "Reinigung!", name = "Reinigung!"),
            message(id = "2", address = "444", name = "Werbung"),
            message(id = "3", address = "+41791234567", name = "Anna")
        )
        assertEquals(listOf("3"), SenderJudgement.shown(messages, showCompanies = false).map { it.id })
    }

    @Test
    fun `a corrected sender survives the companies being switched off`() {
        // The whole point of the two settings together: no firms, except the surgery. Read the
        // address here instead of the correction and this is the caregiver's decision undone by
        // their own next decision, with nothing on either screen to say which one did it.
        val surgery = message(id = "1", address = "4144").copy(treatedAsPerson = true)
        val laundry = message(id = "2", address = "Reinigung!", name = "Reinigung!")
        val anna = message(id = "3", address = "+41791234567", name = "Anna")

        val shown = SenderJudgement.shown(listOf(surgery, laundry, anna), showCompanies = false)

        assertEquals(listOf("1", "3"), shown.map { it.id })
    }

    @Test
    fun `her own reply to a company goes with it`() {
        // Half a conversation is stranger than none: her answer, on the list, under a name, with
        // nothing it was an answer to.
        val hers = message(id = "1", address = "Reinigung!", direction = MessageDirection.OUTGOING)
        assertTrue(SenderJudgement.shown(listOf(hers), showCompanies = false).isEmpty())
    }

    @Test
    fun `somebody in her address book is never a company`() {
        // She put them there. Whatever the number looks like, this switch is not about them.
        val short = message(id = "1", address = "999", name = "Anna", contactId = "contact-7")
        assertEquals(listOf("1"), SenderJudgement.shown(listOf(short), showCompanies = false).map { it.id })
    }

    @Test
    fun `the list is named by what her own screen calls them`() {
        val known = SenderJudgement.known(
            listOf(
                message(id = "1", address = "Apotheke", name = "Apotheke Bahnhof"),
                message(id = "2", address = "444", name = "Zahnarzt")
            )
        )
        assertEquals(listOf("Apotheke Bahnhof", "Zahnarzt"), known.map { it.name })
    }
}
