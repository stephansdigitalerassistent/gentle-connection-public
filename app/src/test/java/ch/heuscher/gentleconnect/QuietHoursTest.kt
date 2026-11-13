package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    @Test
    fun `fallback before 7 days treats only fixed nocturnal window as quiet`() {
        val qh = QuietHours()
        // Record 6 days of activity
        for (day in 1L..6L) {
            qh.record(day, 14)
        }

        // 01..04 are always quiet via AgitationPolicy.isNocturnalHour
        assertTrue(qh.isQuiet(1))
        assertTrue(qh.isQuiet(2))
        assertTrue(qh.isQuiet(3))
        assertTrue(qh.isQuiet(4))

        // All other hours return false before 7 days
        assertFalse(qh.isQuiet(0))
        assertFalse(qh.isQuiet(5))
        assertFalse(qh.isQuiet(10))
        assertFalse(qh.isQuiet(14))
        assertFalse(qh.isQuiet(23))
    }

    @Test
    fun `learned quiet hours once 7 days recorded`() {
        val qh = QuietHours()
        // Record 7 days:
        // Hour 10 active on 1 day -> quiet (activeDays <= 1)
        // Hour 12 active on 2 days -> not quiet (activeDays > 1)
        // Hour 14 active on 7 days -> not quiet
        // Hour 16 active on 0 days -> quiet
        qh.record(1L, 10)
        qh.record(1L, 12)
        qh.record(2L, 12)
        for (day in 1L..7L) {
            qh.record(day, 14)
        }

        assertTrue(qh.isQuiet(10))
        assertTrue(qh.isQuiet(16))
        assertFalse(qh.isQuiet(12))
        assertFalse(qh.isQuiet(14))
    }

    @Test
    fun `fixed 01-05 window is always quiet even if active on all days`() {
        val qh = QuietHours()
        // Active on hours 1, 2, 3, 4 every single day for 10 days
        for (day in 1L..10L) {
            qh.record(day, 1)
            qh.record(day, 2)
            qh.record(day, 3)
            qh.record(day, 4)
        }

        assertTrue(qh.isQuiet(1))
        assertTrue(qh.isQuiet(2))
        assertTrue(qh.isQuiet(3))
        assertTrue(qh.isQuiet(4))
    }

    @Test
    fun `14-day retention drops older days when new days recorded`() {
        val qh = QuietHours()
        // Record days 1..14 with hour 8 active on each
        for (day in 1L..14L) {
            qh.record(day, 8)
        }
        assertFalse(qh.isQuiet(8))

        // Now record days 15..28 where hour 8 is never active (record hour 20 instead)
        for (day in 15L..28L) {
            qh.record(day, 20)
        }

        // Days 1..14 have been pruned, so hour 8 is active on 0 of the 14 retained days -> quiet!
        assertTrue(qh.isQuiet(8))
        assertFalse(qh.isQuiet(20))
    }

    @Test
    fun `record return value indicates newly set bit`() {
        val qh = QuietHours()
        // First record of hour 15 on day 100 -> true
        assertTrue(qh.record(100L, 15))
        // Repeating same hour on same day -> false
        assertFalse(qh.record(100L, 15))
        // Different hour on same day -> true
        assertTrue(qh.record(100L, 16))
        // Invalid hours -> false
        assertFalse(qh.record(100L, -1))
        assertFalse(qh.record(100L, 24))
    }

    @Test
    fun `encode and decode round-trip and garbage handling`() {
        val qh = QuietHours()
        qh.record(100L, 2)
        qh.record(100L, 14)
        qh.record(101L, 10)

        val encoded = qh.encode()
        val decoded = QuietHours.decode(encoded)
        assertEquals(encoded, decoded.encode())

        // Garbage and null/empty handling
        val empty1 = QuietHours.decode(null)
        assertEquals("", empty1.encode())
        val empty2 = QuietHours.decode("")
        assertEquals("", empty2.encode())
        val empty3 = QuietHours.decode("   ")
        assertEquals("", empty3.encode())
        val empty4 = QuietHours.decode("garbage,invalid:token,foo:bar")
        assertEquals("", empty4.encode())

        // Partial garbage retains valid entries
        val mixed = QuietHours.decode("invalid,100:8,bad:number,200:16")
        assertEquals("100:8,200:16", mixed.encode())
    }

    @Test
    fun `alwaysQuiet helper forces specified hours`() {
        val qh = QuietHours.alwaysQuiet(setOf(0, 12, 23))
        assertTrue(qh.isQuiet(0))
        assertTrue(qh.isQuiet(12))
        assertTrue(qh.isQuiet(23))
        // Still has fixed nocturnal window
        assertTrue(qh.isQuiet(2))
        // Other hours are not quiet
        assertFalse(qh.isQuiet(13))
    }
}
