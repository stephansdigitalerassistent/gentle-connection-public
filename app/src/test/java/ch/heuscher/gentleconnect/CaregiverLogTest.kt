package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.CaregiverEventMapper
import ch.heuscher.gentleconnect.data.CaregiverLog
import ch.heuscher.gentleconnect.data.db.CaregiverEventEntity
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caregiver's error log.
 *
 * This is the only place in the app where a fault is ever put into words, so the ways it can fail
 * are all ways of failing silently: a log that floods shows nothing, a log that grows without bound
 * is never pruned by anyone, and a log that writes down identities quietly accumulates a copy of
 * everyone she is in touch with.
 */
class CaregiverLogTest {

    private fun event(
        kind: CaregiverEventKind = CaregiverEventKind.MESSAGE_NOT_SENT,
        at: Long = 1_700_000_000_000L,
        subject: String? = "Karolina",
        count: Int = 0,
        total: Int = 0
    ) = CaregiverEvent(kind = kind, at = at, subject = subject, count = count, total = total)

    @Test
    fun `the newest fault is the first one read`() {
        val first = event(at = 1L)
        val second = event(kind = CaregiverEventKind.CONTACT_SYNC_FAILED, at = 2L, subject = null)

        val log = CaregiverLog.add(CaregiverLog.add(emptyList(), first), second)

        assertEquals(listOf(second, first), log)
    }

    @Test
    fun `the same fault twice is one line that counts`() {
        // The flood this prevents is real: a messenger that cannot connect retries on a timer, and
        // one bad evening of that would push every other fault off the end of the log.
        val log = generateSequence { event(kind = CaregiverEventKind.MESSENGER_CONNECTION_FAILED, subject = null) }
            .take(40)
            .fold(emptyList<CaregiverEvent>()) { acc, e -> CaregiverLog.add(acc, e) }

        assertEquals(1, log.size)
        assertEquals(40, log.first().occurrences)
    }

    @Test
    fun `a fan-out that fails differently the second time is still the same fault`() {
        // Deliberately ignoring the counts when collapsing. Two of five and then three of five is
        // one thing going wrong repeatedly, not two things.
        val first = event(kind = CaregiverEventKind.GROUP_MESSAGE_PART_SENT, subject = "Familie", count = 2, total = 5)
        val second = first.copy(count = 3, at = first.at + 1000)

        val log = CaregiverLog.add(CaregiverLog.add(emptyList(), first), second)

        assertEquals(1, log.size)
        assertEquals(2, log.first().occurrences)
        // The line reports the most recent attempt, not the first one.
        assertEquals(3, log.first().count)
    }

    @Test
    fun `the same fault about two different people stays two lines`() {
        val toKarolina = event(subject = "Karolina")
        val toJohann = event(subject = "Johann")

        val log = CaregiverLog.add(CaregiverLog.add(emptyList(), toKarolina), toJohann)

        assertEquals(2, log.size)
    }

    @Test
    fun `the log does not grow for ever`() {
        // Nobody administers this phone, and nobody clears this table.
        val log = (1..CaregiverLog.MAX_ENTRIES + 50).fold(emptyList<CaregiverEvent>()) { acc, i ->
            CaregiverLog.add(acc, event(at = i.toLong(), subject = "Person $i"))
        }

        assertEquals(CaregiverLog.MAX_ENTRIES, log.size)
        // What survives is the newest, not the first ones that happened to arrive.
        assertEquals("Person ${CaregiverLog.MAX_ENTRIES + 50}", log.first().subject)
    }

    @Test
    fun `tidying up cannot push a fault off the end of the log`() {
        // The case this exists for: somebody visits, deletes a great many messages, and the
        // failed send from three weeks ago that they were going to be asked about is gone.
        val fault = event(at = 1L, subject = "Karolina")
        val log = (1..CaregiverLog.MAX_ACTION_ENTRIES + 200).fold(listOf(fault)) { acc, i ->
            CaregiverLog.add(
                acc,
                event(
                    kind = CaregiverEventKind.MESSAGE_DELETED_ON_PHONE,
                    at = 100L + i,
                    subject = "Person $i"
                )
            )
        }

        assertTrue(log.contains(fault))
        assertEquals(
            CaregiverLog.MAX_ACTION_ENTRIES,
            log.count { CaregiverLog.isCaregiverAction(it.kind) }
        )
    }

    @Test
    fun `faults still have their own budget, whatever the caregiver has been doing`() {
        val actions = (1..10).fold(emptyList<CaregiverEvent>()) { acc, i ->
            CaregiverLog.add(
                acc,
                event(kind = CaregiverEventKind.CONVERSATION_MUTED, at = i.toLong(), subject = "P$i")
            )
        }
        val log = (1..CaregiverLog.MAX_ENTRIES + 50).fold(actions) { acc, i ->
            CaregiverLog.add(acc, event(at = 1_000L + i, subject = "Person $i"))
        }

        assertEquals(
            CaregiverLog.MAX_ENTRIES,
            log.count { !CaregiverLog.isCaregiverAction(it.kind) }
        )
        // And the actions were not evicted by the flood of faults either.
        assertEquals(10, log.count { CaregiverLog.isCaregiverAction(it.kind) })
    }

    @Test
    fun `every kind belongs to exactly one budget`() {
        // The `when` in isCaregiverAction has no `else`, so an unclassified kind is a compile
        // error. This asserts the classification is total rather than what it says, in the same
        // way CaregiverAlertsTest does for worthNotifying.
        CaregiverEventKind.entries.forEach { CaregiverLog.isCaregiverAction(it) }
        assertEquals(
            CaregiverEventKind.entries.count { CaregiverLog.isCaregiverAction(it) },
            CaregiverLog.CAREGIVER_ACTION_KINDS.size
        )
        assertFalse(CaregiverLog.isCaregiverAction(CaregiverEventKind.MESSAGE_NOT_SENT))
        assertTrue(CaregiverLog.isCaregiverAction(CaregiverEventKind.NUMBER_BLOCKED))
    }

    @Test
    fun `a name is kept because the log is useless without it`() {
        assertEquals("Karolina", CaregiverLog.anonymise("Karolina"))
        assertEquals("Familie Hartmann", CaregiverLog.anonymise("Familie Hartmann"))
        assertEquals("Claire-Lise", CaregiverLog.anonymise(" Claire-Lise "))
    }

    @Test
    fun `an identity is never written down`() {
        // A message from somebody she has no contact for is displayed under their raw identity, so
        // the name handed to the log is sometimes the identity itself. Writing those down would
        // slowly build a list of everyone she is in touch with, on a screen meant for faults.
        assertNull(CaregiverLog.anonymise("TSTMBR03"))
        assertNull(CaregiverLog.anonymise("TSTMBR01"))
        assertNull(CaregiverLog.anonymise("TSTMBR02"))
    }

    @Test
    fun `an eight-letter first name is not mistaken for an identity`() {
        // Why the test requires a digit. KAROLINA and MATTHIAS are eight uppercase letters and are
        // exactly the names this log exists to be readable about; mangling them into "someone"
        // would be the more damaging way to be wrong.
        assertEquals("KAROLINA", CaregiverLog.anonymise("KAROLINA"))
        assertEquals("MATTHIAS", CaregiverLog.anonymise("MATTHIAS"))
    }

    @Test
    fun `nothing to name is nothing rather than an empty line`() {
        assertNull(CaregiverLog.anonymise(null))
        assertNull(CaregiverLog.anonymise(""))
        assertNull(CaregiverLog.anonymise("   "))
    }

    @Test
    fun `nothing collapses into an empty log`() {
        assertFalse(CaregiverLog.collapses(null, event()))
    }

    @Test
    fun `a fault survives the round trip through the database`() {
        val original = event(
            kind = CaregiverEventKind.GROUP_MESSAGE_PART_SENT,
            subject = "Familie Hartmann",
            count = 2,
            total = 5
        ).copy(occurrences = 3)

        val restored = CaregiverEventMapper.fromRow(CaregiverEventMapper.toRow(original))

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `the kind is stored by name so reordering the enum cannot relabel old rows`() {
        val row = CaregiverEventMapper.toRow(event(kind = CaregiverEventKind.CONTACT_SYNC_FAILED))
        assertEquals("CONTACT_SYNC_FAILED", row.kind)
    }

    @Test
    fun `a fault a later version of the app invented is dropped rather than shown blank`() {
        val row = CaregiverEventEntity(
            id = 1,
            kind = "SOMETHING_A_NEWER_BUILD_KNOWS_ABOUT",
            at = 1L,
            subject = null,
            count = 0,
            total = 0,
            occurrences = 1
        )

        assertNull(CaregiverEventMapper.fromRow(row))
    }

    @Test
    fun `a row claiming it never happened still reads as having happened once`() {
        val row = CaregiverEventMapper.toRow(event()).copy(occurrences = 0)
        assertEquals(1, CaregiverEventMapper.fromRow(row)?.occurrences)
    }

    @Test
    fun `every kind of fault can be stored and read back`() {
        // Guards the gap between the enum and what the database round trip understands: a kind
        // added without a thought for storage would otherwise vanish on the way back out.
        CaregiverEventKind.entries.forEach { kind ->
            val restored = CaregiverEventMapper.fromRow(CaregiverEventMapper.toRow(event(kind = kind)))
            assertEquals(kind, restored?.kind)
        }
        assertTrue(CaregiverEventKind.entries.isNotEmpty())
    }
}
