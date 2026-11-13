package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.db.toEntity
import ch.heuscher.gentleconnect.data.db.toMail
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.MailPresentation
import ch.heuscher.gentleconnect.model.MailProminence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that replaced the filter.
 *
 * The thing being guarded is not a look, it is a promise: nothing her postman brings is thrown
 * away before she sees it. The old filter broke that promise silently, which is why the first
 * test here is the one that matters most.
 */
class MailPresentationTest {

    private fun mail(
        id: String,
        known: Boolean,
        timestamp: Long = 1_000L,
        isRead: Boolean = false
    ) = Mail(
        id = id,
        senderName = if (known) "Anna" else "Migros Newsletter",
        senderAddress = if (known) "anna@example.com" else "news@migros.example",
        subject = "Betreff",
        snippet = "…",
        timestamp = timestamp,
        isRead = isRead,
        isFromKnownSender = known
    )

    @Test
    fun `her post screen hides nothing at all`() {
        val everything = listOf(
            mail("known", known = true),
            mail("stranger", known = false),
            mail("another stranger", known = false)
        )

        assertEquals(everything, MailPresentation.forPostScreen(everything))
        assertEquals(everything, MailPresentation.forPostScreen(everything, onlyKnownSenders = false))
    }

    @Test
    fun `only when a caregiver asks does her post screen leave strangers out`() {
        val known = mail("known", known = true)
        val everything = listOf(known, mail("stranger", known = false))

        assertEquals(listOf(known), MailPresentation.forPostScreen(everything, onlyKnownSenders = true))
    }

    @Test
    fun `a letter from a stranger is shown, and shown quietly`() {
        assertEquals(
            MailProminence.FROM_A_STRANGER,
            MailPresentation.prominenceOf(mail("a", known = false))
        )
        assertEquals(
            MailProminence.FROM_SOMEONE_SHE_KNOWS,
            MailPresentation.prominenceOf(mail("b", known = true))
        )
    }

    @Test
    fun `her home screen keeps its few slots for people she knows`() {
        val mails = listOf(
            mail("stranger1", known = false, timestamp = 5_000L),
            mail("anna", known = true, timestamp = 4_000L),
            mail("stranger2", known = false, timestamp = 3_000L)
        )

        assertEquals(listOf("anna"), MailPresentation.forHomeScreen(mails).map { it.id })
    }

    @Test
    fun `a morning of circulars cannot push her son off the home screen`() {
        // The failure this exists to prevent, spelled out. Home.mails takes the first few after
        // putting unread first, so without the filter the four newest — all newsletters — would
        // be the whole list, and the letter from her son would be on a screen she never scrolls.
        val circulars = (1..10).map { mail("junk$it", known = false, timestamp = 10_000L + it) }
        val fromHerSon = mail("son", known = true, timestamp = 1_000L)

        val onScreen = Home.mails(MailPresentation.forHomeScreen(circulars + fromHerSon))

        assertEquals(listOf("son"), onScreen.map { it.id })
    }

    @Test
    fun `an unread circular does not claim her attention`() {
        assertFalse(MailPresentation.callsForAttention(mail("junk", known = false)))
        assertTrue(MailPresentation.callsForAttention(mail("anna", known = true)))
    }

    @Test
    fun `a letter she has already read claims no attention either way`() {
        assertFalse(MailPresentation.callsForAttention(mail("anna", known = true, isRead = true)))
        assertFalse(MailPresentation.callsForAttention(mail("junk", known = false, isRead = true)))
    }

    @Test
    fun `the waiting count is about people, not volume`() {
        val mails = listOf(
            mail("anna", known = true),
            mail("read", known = true, isRead = true),
            mail("junk1", known = false),
            mail("junk2", known = false),
            mail("junk3", known = false)
        )

        assertEquals(1, MailPresentation.attentionCount(mails))
    }

    @Test
    fun `an empty postbag counts to zero rather than throwing`() {
        assertEquals(0, MailPresentation.attentionCount(emptyList()))
        assertEquals(emptyList<Mail>(), MailPresentation.forHomeScreen(emptyList()))
    }

    @Test
    fun `who sent it survives the round trip through the database`() {
        // The column is still called isVisible in SQLite — see MailEntity — so this checks that
        // the rename stayed a rename and did not quietly become a different column.
        val known = mail("a", known = true)
        val stranger = mail("b", known = false)

        assertTrue(known.toEntity().toMail().isFromKnownSender)
        assertFalse(stranger.toEntity().toMail().isFromKnownSender)
        assertEquals(stranger, stranger.toEntity().toMail())
    }
}
