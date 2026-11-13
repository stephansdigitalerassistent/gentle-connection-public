package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.CaregiverAlerts
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.PhoneSilence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverAlertsTest {

    private val now = 1_800_000_000_000L

    private fun event(
        kind: CaregiverEventKind,
        at: Long = now,
        subject: String? = null,
        occurrences: Int = 1
    ) = CaregiverEvent(kind = kind, at = at, subject = subject, occurrences = occurrences)

    @Test
    fun `the six faults that travel are the ones nobody can see from outside`() {
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.MESSAGE_NOT_SENT))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.GROUP_MESSAGE_PART_SENT))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.MESSENGER_CONNECTION_FAILED))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.MMS_DROPPED_UNSUPPORTED))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.MESSAGES_LOST_ON_ARRIVAL))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.SMS_NOT_STORED))
        assertTrue(CaregiverAlerts.worthNotifying(CaregiverEventKind.MAIL_ACCESS_LOST))
    }

    @Test
    fun `faults nobody can act on stay in the log on the phone`() {
        // Not an oversight, and the test says so: each of these is worth reading on a visit and
        // is not worth a notification, because there is no action on the other end of it.
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.GROUP_MEMBERSHIP_UNKNOWN))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.GROUP_NO_LONGER_MEMBER))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.GROUP_NOBODY_LEFT))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CONTACT_SYNC_FAILED))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD))
        // This one is not a fault at all: the switch ending a stranger's call is doing what it was
        // asked to. On a phone that gets three cold calls a day, notifying would fire three times
        // a day and bury the three lines above it that mean something is actually broken.
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CALL_FROM_STRANGER_ENDED))
        // A WhatsApp call nobody could answer: the caller has already been told to use her
        // number and she has already been shown the missed call. A push for each of these would
        // fire every time a relative forgets, which is the habit this is meant to change.
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.WHATSAPP_CALL_UNANSWERED))
        // Direct caregiver actions taken on the phone are intentional and not external system faults.
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.MESSAGE_DELETED_ON_PHONE))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.NUMBER_BLOCKED))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CONVERSATION_MUTED))
        // Fail-at-tap and blocked stranger calls are logged for caregiver visits but not pushed.
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.CALL_FAILED_NO_CONNECTION))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.THREEMA_CALL_FROM_STRANGER_ENDED))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED))
        assertFalse(CaregiverAlerts.worthNotifying(CaregiverEventKind.RESTLESSNESS_DETECTED))
    }

    @Test
    fun `every kind has been decided one way or the other`() {
        // The point of this one is that it keeps passing: `worthNotifying` is a `when` with no
        // `else`, so a kind added later fails to compile until somebody chooses. This asserts the
        // choice is total rather than what it is.
        CaregiverEventKind.entries.forEach { CaregiverAlerts.worthNotifying(it) }
        // Bumped to 23 for MAIL_ACCESS_LOST.
        assertEquals(23, CaregiverEventKind.entries.size)
    }


    @Test
    fun `only the notifiable faults are published`() {
        val alerts = CaregiverAlerts.toAlerts(
            listOf(
                event(CaregiverEventKind.CONTACT_SYNC_FAILED),
                event(CaregiverEventKind.MESSAGE_NOT_SENT, subject = "Karolina"),
                event(CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD)
            ),
            now
        )

        assertEquals(1, alerts.size)
        assertEquals(CaregiverEventKind.MESSAGE_NOT_SENT, alerts[0].kind)
    }

    @Test
    fun `a fault from last month is not what is wrong now`() {
        val old = now - CaregiverAlerts.RECENT_WINDOW_MS - 1
        val justInside = now - CaregiverAlerts.RECENT_WINDOW_MS

        assertTrue(
            CaregiverAlerts.toAlerts(listOf(event(CaregiverEventKind.MESSAGE_NOT_SENT, at = old)), now)
                .isEmpty()
        )
        assertEquals(
            1,
            CaregiverAlerts.toAlerts(
                listOf(event(CaregiverEventKind.MESSAGE_NOT_SENT, at = justInside)),
                now
            ).size
        )
    }

    @Test
    fun `a phone whose clock runs fast still reports its faults`() {
        val future = now + 60_000
        assertEquals(
            1,
            CaregiverAlerts.toAlerts(
                listOf(event(CaregiverEventKind.MESSAGE_NOT_SENT, at = future)),
                now
            ).size
        )
    }

    @Test
    fun `the list stays short`() {
        val many = (1..40).map {
            event(CaregiverEventKind.MESSAGE_NOT_SENT, at = now - it, subject = "P$it")
        }

        val alerts = CaregiverAlerts.toAlerts(many, now)

        assertEquals(CaregiverAlerts.MAX_ALERTS, alerts.size)
        // Newest-first in, newest-first out: the ones dropped are the oldest, not the newest.
        assertEquals("P1", alerts.first().subject)
    }

    @Test
    fun `the same fault about the same person is one fault`() {
        val a = event(CaregiverEventKind.MESSAGE_NOT_SENT, subject = "Karolina")
        val b = event(CaregiverEventKind.MESSAGE_NOT_SENT, at = now + 90_000, subject = "karolina")

        assertEquals(CaregiverAlerts.signature(a), CaregiverAlerts.signature(b))
    }

    @Test
    fun `the same fault about different people is two faults`() {
        assertTrue(
            CaregiverAlerts.signature(event(CaregiverEventKind.MESSAGE_NOT_SENT, subject = "Anne")) !=
                CaregiverAlerts.signature(event(CaregiverEventKind.MESSAGE_NOT_SENT, subject = "Hannah"))
        )
    }

    @Test
    fun `a subjectless fault has a signature of its own`() {
        // MESSENGER_CONNECTION_FAILED carries no subject, and must not collide with a
        // MESSAGE_NOT_SENT that happens to have lost its name.
        assertTrue(
            CaregiverAlerts.signature(event(CaregiverEventKind.MESSENGER_CONNECTION_FAILED)) !=
                CaregiverAlerts.signature(event(CaregiverEventKind.MESSAGE_NOT_SENT))
        )
    }

    @Test
    fun `an unchanged list is not written again`() {
        val list = listOf(event(CaregiverEventKind.MESSAGE_NOT_SENT, subject = "Karolina"))

        assertFalse(CaregiverAlerts.changed(list, list))
        assertTrue(CaregiverAlerts.changed(null, list))
        assertTrue(CaregiverAlerts.changed(emptyList(), list))
    }

    @Test
    fun `the same fault happening again is news`() {
        val once = listOf(event(CaregiverEventKind.MESSENGER_CONNECTION_FAILED, occurrences = 1))
        val twice = listOf(
            event(CaregiverEventKind.MESSENGER_CONNECTION_FAILED, at = now + 60_000, occurrences = 2)
        )

        // Same kind, same (absent) subject — the signature is identical, so this is exactly the
        // case where comparing signatures instead of content would leave the website saying the
        // messenger broke this morning while it is in fact still broken.
        assertEquals(CaregiverAlerts.signature(once[0]), CaregiverAlerts.signature(twice[0]))
        assertTrue(CaregiverAlerts.changed(once, twice))
    }

    @Test
    fun `a fault that cleared is written too`() {
        val had = listOf(event(CaregiverEventKind.MESSENGER_CONNECTION_FAILED))
        assertTrue(CaregiverAlerts.changed(had, emptyList()))
    }

    @Test
    fun `a phone that has never reported is not the same as one that has gone quiet`() {
        assertEquals(PhoneSilence.NEVER_HEARD, CaregiverAlerts.silence(null, now))
    }

    @Test
    fun `a missed heartbeat is not a fault`() {
        // Four times the heartbeat interval and still nothing is wrong. Doze, a router reboot and
        // a walk to the shops all live in this gap, and each of them ringing a daughter's phone
        // is how an alarm gets ignored.
        val missedFour = now - (CaregiverAlerts.HEARTBEAT_INTERVAL_MS * 4)
        assertEquals(PhoneSilence.HEARD, CaregiverAlerts.silence(missedFour, now))
    }

    @Test
    fun `a night of silence passes and a morning of it does not`() {
        val justUnder = now - CaregiverAlerts.SILENCE_THRESHOLD_MS + 1
        val atThreshold = now - CaregiverAlerts.SILENCE_THRESHOLD_MS

        assertEquals(PhoneSilence.HEARD, CaregiverAlerts.silence(justUnder, now))
        assertEquals(PhoneSilence.QUIET, CaregiverAlerts.silence(atThreshold, now))
    }

    @Test
    fun `a fast clock does not report a working phone as missing`() {
        assertEquals(PhoneSilence.HEARD, CaregiverAlerts.silence(now + 3_600_000, now))
    }

    @Test
    fun `silence is read long after a missed heartbeat, never at the first one`() {
        // The relationship between the two constants is the whole safety margin, so it is asserted
        // rather than left to whoever edits one of them next.
        assertTrue(CaregiverAlerts.SILENCE_THRESHOLD_MS > CaregiverAlerts.HEARTBEAT_INTERVAL_MS * 8)
    }
}
