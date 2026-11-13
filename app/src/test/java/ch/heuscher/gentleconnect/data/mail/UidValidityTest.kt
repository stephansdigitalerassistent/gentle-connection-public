package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.data.MailRepository
import ch.heuscher.gentleconnect.model.Mail
import org.junit.Assert.assertNull
import org.junit.Test

class UidValidityTest {

    @Test
    fun `UIDVALIDITY change discards stored UIDs`() {
        val repository = MailRepository()
        val mail1 = Mail(
            id = "m1",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Hallo",
            snippet = "Hi",
            timestamp = 1000L,
            uid = 42L,
            uidValidity = 100L
        )

        repository.addMail(mail1)

        // When UIDVALIDITY changes on server, clearUids is called
        repository.clearUids()

        val updated = repository.mails.value.single()
        assertNull(updated.uid)
        assertNull(updated.uidValidity)
    }
}
