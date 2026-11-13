package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Arrivals
import ch.heuscher.gentleconnect.model.Arrivals.quietAt
import ch.heuscher.gentleconnect.model.Arrivals.worthAnnouncing
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.arrivedAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone is allowed to wake her for.
 *
 * Every test here is a way of getting this wrong that she would experience as the phone having
 * gone strange: a light in the night, a sound for a message from four years ago, the same message
 * announced twice.
 */
class ArrivalsTest {

    private val now = 1_700_000_000_000L
    private val justNow = now - 30_000L

    @Test
    fun `a message that has just arrived is announced`() {
        assertTrue(worthAnnouncing(justNow, announcedUpTo = 0L, now = now))
    }

    @Test
    fun `a message written an hour ago and received seconds ago is announced`() {
        // Messengers deliver in bursts on reconnect: she was out of coverage for an hour, the
        // server delivers everything at once with the original send time. Because the phone
        // received it seconds ago, it is fresh to her and must be announced.
        val writtenAnHourAgo = now - 60 * 60_000L
        val receivedSecondsAgo = now - 10_000L
        val message = Message(
            id = "threema_1",
            senderName = "Son",
            text = "Bin gleich da",
            timestamp = writtenAnHourAgo,
            receivedAt = receivedSecondsAgo,
            sourcePackage = ""
        )
        assertTrue(
            worthAnnouncing(
                arrivedAt = message.arrivedAt(),
                announcedUpTo = 0L,
                now = now
            )
        )
    }

    @Test
    fun `a message written seconds ago but with an unknown receipt time still falls back to its send time and is announced`() {
        // External bridge messages carry no receipt clock and leave receivedAt = 0. When written
        // seconds ago, falling back to the send timestamp announces it normally.
        val writtenSecondsAgo = now - 15_000L
        val message = Message(
            id = "bridge_1",
            senderName = "Son",
            text = "Hallo Mama",
            timestamp = writtenSecondsAgo,
            receivedAt = 0L,
            sourcePackage = ""
        )
        assertEquals(writtenSecondsAgo, message.arrivedAt())
        assertTrue(
            worthAnnouncing(
                arrivedAt = message.arrivedAt(),
                announcedUpTo = 0L,
                now = now
            )
        )
    }

    @Test
    fun `a row with receivedAt = 0 and an old send time is not announced`() {
        // The migrated-history case: rows predating the receivedAt column have receivedAt = 0 and
        // fall back to their original send timestamp, so they are filtered by the 5-minute rule
        // and cannot be announced retroactively.
        val sentYesterday = now - 24 * 60 * 60_000L
        val message = Message(
            id = "migrated_1",
            senderName = "Son",
            text = "Gute Nacht",
            timestamp = sentYesterday,
            receivedAt = 0L,
            sourcePackage = ""
        )
        assertFalse(
            worthAnnouncing(
                arrivedAt = message.arrivedAt(),
                announcedUpTo = 0L,
                now = now
            )
        )
    }

    @Test
    fun `announcedUpTo surviving a restart means the same message is not announced twice`() {
        // When the launcher restarts, announcedUpTo restored from disk matches the receipt time
        // of the newest message already announced, and that is the whole of what stops a second
        // chime — the moment the process started is no longer part of the rule.
        val receivedJustNow = now - 20_000L
        val message = Message(
            id = "threema_2",
            senderName = "Daughter",
            text = "Bis spater",
            timestamp = now - 30 * 60_000L,
            receivedAt = receivedJustNow,
            sourcePackage = ""
        )
        val persistedAnnouncedUpTo = receivedJustNow
        assertFalse(
            worthAnnouncing(
                arrivedAt = message.arrivedAt(),
                announcedUpTo = persistedAnnouncedUpTo,
                now = now
            )
        )
        // And the message that lands a second after the launcher comes back is announced, which
        // is the case the old listeningSince guard used to swallow.
        assertTrue(
            worthAnnouncing(
                arrivedAt = now - 1_000L,
                announcedUpTo = persistedAnnouncedUpTo,
                now = now
            )
        )
    }

    @Test
    fun `arrivedAt uses receivedAt when known and falls back to timestamp when zero`() {
        val withReceipt = Message(
            id = "m1",
            senderName = "Anna",
            text = "Hallo",
            timestamp = 1_000L,
            receivedAt = 5_000L,
            sourcePackage = ""
        )
        assertEquals(5_000L, withReceipt.arrivedAt())

        val withoutReceipt = Message(
            id = "m2",
            senderName = "Anna",
            text = "Hallo",
            timestamp = 1_000L,
            receivedAt = 0L,
            sourcePackage = ""
        )
        assertEquals(1_000L, withoutReceipt.arrivedAt())
    }

    @Test
    fun `the same message is never announced twice`() {
        // The list this is driven from re-emits whenever anything about it changes — a photograph
        // finishing its download, a delivery receipt — and every one of those would otherwise be
        // a second chime for a message she has already been told about.
        assertFalse(
            worthAnnouncing(justNow, announcedUpTo = justNow, now = now)
        )
    }

    @Test
    fun `a restart does not re-announce what she has already been told`() {
        // A launcher restarts constantly: Android reclaims memory, an update lands, something
        // crashes. Every one of those would otherwise sound exactly like her son writing. What
        // stops it is the watermark, which is read back from disk — not the moment this process
        // happened to start, which is no longer known here and would suppress a message that
        // arrived a second before the screen came up.
        val alreadyTold = now - 60_000L
        assertFalse(worthAnnouncing(alreadyTold, announcedUpTo = alreadyTold, now = now))
        assertTrue(worthAnnouncing(alreadyTold, announcedUpTo = alreadyTold - 1, now = now))
    }

    @Test
    fun `a history import does not wake her for a four-year-old message`() {
        // The case that made the freshness rule necessary: a reconnecting session hands the app
        // years of messages at once, all of them new to the database.
        val fourYearsAgo = now - 4L * 365 * 24 * 60 * 60 * 1000
        assertFalse(
            worthAnnouncing(fourYearsAgo, announcedUpTo = 0L, now = now)
        )
    }

    @Test
    fun `something a few minutes late is still announced`() {
        // Delivery is not instant, and a message that took two minutes to reach the phone is
        // still the message she is waiting for.
        val twoMinutesAgo = now - 2 * 60_000L
        assertTrue(
            worthAnnouncing(twoMinutesAgo, announcedUpTo = 0L, now = now)
        )
        assertTrue("the bound itself counts", Arrivals.STILL_FRESH_MS > 2 * 60_000L)
    }

    @Test
    fun `the phone stays dark at night`() {
        assertTrue(quietAt(23))
        assertTrue(quietAt(3))
        assertTrue(quietAt(6))
        assertTrue("the evening boundary is quiet", quietAt(Arrivals.QUIET_FROM_HOUR))
    }

    @Test
    fun `the phone speaks up during the day`() {
        assertFalse(quietAt(7))
        assertFalse(quietAt(12))
        assertFalse(quietAt(20))
    }

    // The quiet hours a caregiver sets on the website. The defaults are still 21:00 to 07:00;
    // what these cover is the window being somebody else's.

    @Test
    fun `a caregiver can move bedtime later`() {
        // She reads until midnight. Under the default window her family would go quiet at nine.
        assertFalse(Arrivals.quietAt(22, fromHour = 23, untilHour = 7))
        assertTrue(Arrivals.quietAt(23, fromHour = 23, untilHour = 7))
        assertTrue(Arrivals.quietAt(2, fromHour = 23, untilHour = 7))
        assertFalse(Arrivals.quietAt(7, fromHour = 23, untilHour = 7))
    }

    @Test
    fun `a caregiver can move the morning earlier`() {
        assertTrue(Arrivals.quietAt(4, fromHour = 21, untilHour = 5))
        assertFalse(Arrivals.quietAt(5, fromHour = 21, untilHour = 5))
    }

    @Test
    fun `a window inside one day is honoured as written`() {
        // An afternoon rest. Read the other way round this would be quiet for twenty-six hours.
        assertTrue(Arrivals.quietAt(13, fromHour = 13, untilHour = 15))
        assertTrue(Arrivals.quietAt(14, fromHour = 13, untilHour = 15))
        assertFalse(Arrivals.quietAt(15, fromHour = 13, untilHour = 15))
        assertFalse(Arrivals.quietAt(20, fromHour = 13, untilHour = 15))
        assertFalse(Arrivals.quietAt(3, fromHour = 13, untilHour = 15))
    }

    @Test
    fun `the same hour twice means never quiet, not always quiet`() {
        // The ambiguous case, resolved towards her hearing her family. The other reading is a
        // telephone that never makes a sound again and nobody knowing why.
        for (hour in 0..23) {
            assertFalse(Arrivals.quietAt(hour, fromHour = 9, untilHour = 9))
        }
    }

    @Test
    fun `an hour that is not on a clock falls back to the default window`() {
        // Whatever a bad write puts in the document, the phone keeps a sane night.
        assertTrue(Arrivals.quietAt(23, fromHour = 99, untilHour = 7))
        assertFalse(Arrivals.quietAt(12, fromHour = 99, untilHour = 7))
        assertTrue(Arrivals.quietAt(3, fromHour = 21, untilHour = -1))
        assertFalse(Arrivals.quietAt(12, fromHour = -5, untilHour = 40))
    }

    @Test
    fun `the defaults are unchanged when nobody has set anything`() {
        assertTrue(Arrivals.quietAt(21))
        assertTrue(Arrivals.quietAt(3))
        assertFalse(Arrivals.quietAt(7))
        assertFalse(Arrivals.quietAt(14))
    }
}
