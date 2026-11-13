package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.Nudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose messages get the top of her home screen.
 *
 * The card at the top of the phone said "ParkApp wrote a message", and the message from her
 * family was below the fold underneath it. Companies write far more often than her children do,
 * so recency alone hands them the screen.
 */
class HomeTest {

    private var clock = 1_700_000_000_000L

    // Who a missed call was from, which used to be asked by name and is now asked by number.

    private val laura = Contact(
        id = "c-laura",
        // Exactly as an address book holds it, quoted nickname and all. This is the string that
        // never matched the telephone log's "Laura Hartmann".
        name = "Laura \"Lala Lulu\" Hartmann",
        phoneNumber = "+41785550101"
    )
    private val jana = Contact(id = "c-jana", name = "Jana", phoneNumber = "+41791111111")

    private fun missedCall(number: String, name: String = "Laura Hartmann") =
        Nudge.MissedCallNudge(contactName = name, timestamp = 1_700_000_000_000L, number = number)

    @Test
    fun `a favourite whose name is spelled differently is still found`() {
        // The whole bug in one assertion. The name in the address book and the name in the call
        // log are the same person and have never been the same string.
        val found = Home.callBack(listOf(jana, laura), missedCall("+41785550101"))

        assertEquals(laura, found)
    }

    @Test
    fun `the same telephone written two ways is one telephone`() {
        // She was rung from abroad and the log kept the international form; the address book has
        // the national one. See CallHistory.sameNumber.
        val national = laura.copy(phoneNumber = "078 555 01 01")

        assertNotNull(Home.callBack(listOf(national), missedCall("+41785550101")))
    }

    @Test
    fun `a number nobody has is nobody`() {
        // The surgery ringing back. An ordinary answer, not a failure — the card then takes her
        // to her list of calls instead of offering to ring a stranger.
        assertNull(Home.callBack(listOf(laura, jana), missedCall("+41315550110")))
    }

    @Test
    fun `a withheld number matches nobody at all`() {
        // Blank must not fall through to the first favourite with a blank number of her own,
        // which would put somebody's name on a card about a call they did not make.
        val noNumber = jana.copy(phoneNumber = "")

        assertNull(Home.callBack(listOf(noNumber, laura), missedCall("")))
    }

    @Test
    fun `the first matching favourite is the one offered`() {
        // Two entries for one person is an ordinary state of an address book. Either would do;
        // what matters is that it settles rather than depending on the order she starred them.
        val duplicate = laura.copy(id = "c-laura-2", name = "Lala")

        assertEquals(laura, Home.callBack(listOf(laura, duplicate), missedCall("+41785550101")))
    }

    @Test
    fun `ringing her daughter back from anywhere takes the card away`() {
        // The bug this exists for: the card offered "Call back", she pressed her daughter's face
        // underneath it instead, spoke to her, and came home to a phone still announcing the
        // missed call.
        assertTrue(Home.answeredBy(missedCall("+41785550101"), laura.phoneNumber))
    }

    @Test
    fun `the same telephone written two ways still answers the card`() {
        // The log kept the international form of the number that rang; the address book she rang
        // back from has the national one. Matching them is the whole point of doing this by
        // number — see CallHistory.sameNumber.
        assertTrue(Home.answeredBy(missedCall("+41785550101"), "078 555 01 01"))
    }

    @Test
    fun `ringing somebody else leaves the card alone`() {
        // She rings Jana while the card is about Laura. Laura is still waiting to be rung back
        // and the card is the only thing that says so.
        assertFalse(Home.answeredBy(missedCall("+41785550101"), jana.phoneNumber))
    }

    @Test
    fun `a withheld number is not answered by the next call she makes`() {
        // Blank on both sides must not match blank. A caller who withheld the number cannot be
        // rung back at all, so nothing she does can be her answer to that card.
        assertFalse(Home.answeredBy(missedCall(""), ""))
        assertFalse(Home.answeredBy(missedCall(""), laura.phoneNumber))
        assertFalse(Home.answeredBy(missedCall("+41785550101"), null))
    }

    @Test
    fun `a card about a message is not a card a call can answer`() {
        // Reading is what takes that one away. Ringing whoever wrote is a kind thing to do and
        // not the same thing as having read what they wrote.
        val written = Nudge.MessageNudge(fromPerson("Bin um 4 da"))

        assertFalse(Home.answeredBy(written, "+41791234567"))
        assertFalse(Home.answeredBy(null, laura.phoneNumber))
    }

    private fun fromPerson(text: String, unread: Boolean = true, who: String = "Karolina") = Message(
        id = "p${clock--}",
        senderName = who,
        senderAddress = "+4179" + who.hashCode().toString().takeLast(7),
        text = text,
        timestamp = clock,
        sourcePackage = "",
        isRead = !unread,
        channel = MessageChannel.ON_DEVICE
    )

    private fun fromCompany(text: String, unread: Boolean = true) = Message(
        id = "c${clock--}",
        senderName = "ParkApp",
        // An alphanumeric originating address: reserved for services, and the reason she can
        // never write back to one.
        senderAddress = "ParkApp",
        text = text,
        timestamp = clock,
        sourcePackage = "",
        isRead = !unread,
        channel = MessageChannel.DEVICE
    )

    @Test
    fun `a company never gets the card at the top of the screen`() {
        // The bug, exactly as it stood on the phone: the parking app wrote most recently, so the
        // parking app was the one thing her home screen was about.
        val newest = listOf(fromCompany("Parking expires"), fromPerson("Bin am Samstag da"))
        assertEquals("Bin am Samstag da", Home.worthANudge(newest)?.text)
    }

    @Test
    fun `the message button is marked when somebody has written`() {
        assertTrue(Home.marksTheMessageTab(listOf(fromPerson("Bin am Samstag da"))))
    }

    @Test
    fun `the message button is not marked for a parking app she will never open`() {
        // The mark has to be clearable, or it stops meaning anything. She opens what her family
        // writes and never opens what the parking app writes, so counting the second would leave
        // a mark on the button for ever.
        assertFalse(Home.marksTheMessageTab(listOf(fromCompany("Parking expires"))))
    }

    @Test
    fun `the message button is not marked once she has read it`() {
        assertFalse(Home.marksTheMessageTab(listOf(fromPerson("Bin am Samstag da", unread = false))))
    }

    @Test
    fun `the mark and the card at the top of home never disagree`() {
        // Deliberately the same question. Two answers to "has somebody written" would eventually
        // be two different answers on the same screen.
        val messages = listOf(fromCompany("Parking expires"), fromPerson("Bin am Samstag da"))
        assertEquals(Home.worthANudge(messages) != null, Home.marksTheMessageTab(messages))
    }

    @Test
    fun `nobody having written means no card at all`() {
        // Quiet is the right answer. A card is for something worth noticing.
        assertNull(Home.worthANudge(listOf(fromCompany("Parking expires"))))
        assertNull(Home.worthANudge(listOf(fromPerson("read already", unread = false))))
        assertNull(Home.worthANudge(emptyList()))
    }

    @Test
    fun `the home list is people only`() {
        val mixed = listOf(
            fromCompany("Zur Rose"),
            fromPerson("Johann", who = "Johann"),
            fromCompany("RoamingInfo"),
            fromPerson("Laura", who = "Laura")
        )
        assertEquals(listOf("Johann", "Laura"), Home.messages(mixed).map { it.text })
    }

    @Test
    fun `an unread message is never pushed under the fold by a read one`() {
        val messages = listOf(
            fromPerson("newest, already read", unread = false, who = "Johann"),
            fromPerson("older, not read yet", who = "Laura")
        )
        assertEquals(
            listOf("older, not read yet", "newest, already read"),
            Home.messages(messages).map { it.text }
        )
    }

    @Test
    fun `the home list stops at Home LIST_LENGTH`() {
        val many = (1..12).map { fromPerson("message $it", who = "Person $it") }
        assertEquals(2, Home.messages(many).size)
        assertEquals(Home.LIST_LENGTH, Home.messages(many).size)
        // The newest ones, not any of them.
        assertEquals("message 1", Home.messages(many).first().text)
    }

    @Test
    fun `one person emptying a camera roll gets one row, not the whole screen`() {
        // Twelve pictures in an afternoon used to take both rows and push her daughter off the
        // screen entirely — see [ch.heuscher.gentleconnect.model.PictureFlood].
        val flood = (1..12).map { fromPerson("picture $it", who = "Markus") }
        val daughter = fromPerson("Kommst du am Sonntag?", who = "Katrin")

        assertEquals(
            listOf("picture 1", "Kommst du am Sonntag?"),
            Home.messages(flood + daughter).map { it.text }
        )
    }

    @Test
    fun `an inbox of nothing but companies leaves the home list empty`() {
        // And empty is right. They are all still there under Message.
        val onlyCompanies = listOf(fromCompany("one"), fromCompany("two"))
        assertEquals(emptyList<Message>(), Home.messages(onlyCompanies))
    }

    @Test
    fun `home shows a couple of faces, and the rest are under Call`() {
        // Five filled the screen on their own and cut "Messages" in half at the bottom edge.
        val eight = (1..8).map { Contact(id = "$it", name = "Person $it", phoneNumber = "+4179000000$it") }
        val shown = Home.favourites(eight)

        assertEquals(2, shown.size)
        assertEquals(Home.LIST_LENGTH, shown.size)
        assertEquals(listOf("Person 1", "Person 2"), shown.map { it.name })
    }

    @Test
    fun `the order somebody chose for her is not rearranged`() {
        // Her son's face must not move out from under her thumb because somebody else wrote.
        val chosen = listOf(
            Contact(id = "1", name = "Simon", phoneNumber = "+41790000001"),
            Contact(id = "2", name = "Claire-Lise", phoneNumber = "+41790000002")
        )
        assertEquals(listOf("Simon", "Claire-Lise"), Home.favourites(chosen).map { it.name })
    }

    @Test
    fun `fewer favourites than the list length is not padded out`() {
        val two = (1..2).map { Contact(id = "$it", name = "Person $it", phoneNumber = "+4179000000$it") }
        assertEquals(2, Home.favourites(two).size)
        assertEquals(0, Home.favourites(emptyList()).size)
    }

    @Test
    fun `letters she has not opened come first`() {
        val mails = listOf(
            mail("newest, already read", unread = false),
            mail("older, not opened yet")
        )
        assertEquals(
            listOf("older, not opened yet", "newest, already read"),
            Home.mails(mails).map { it.subject }
        )
    }

    @Test
    fun `the letters on home stop at the list length`() {
        val many = (1..9).map { mail("letter $it") }
        assertEquals(Home.LIST_LENGTH, Home.mails(many).size)
        assertEquals("letter 1", Home.mails(many).first().subject)
    }

    @Test
    fun `an empty postbag lists nothing rather than padding`() {
        assertEquals(emptyList<Mail>(), Home.mails(emptyList()))
    }

    @Test
    fun `a section only offers more when it is holding some back`() {
        assertTrue(Home.hasMore(Home.LIST_LENGTH + 1))
        assertFalse(Home.hasMore(Home.LIST_LENGTH))
        assertFalse(Home.hasMore(0))
    }

    private fun mail(subject: String, unread: Boolean = true) = Mail(
        id = subject,
        senderName = "Somebody",
        senderAddress = "somebody@example.com",
        subject = subject,
        snippet = subject,
        timestamp = 0L,
        isRead = !unread
    )
}
