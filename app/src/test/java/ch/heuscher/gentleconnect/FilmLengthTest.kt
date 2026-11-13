package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.FilmLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two numbers a film puts on her screen: how long it is, and how far through it she is.
 *
 * Worth testing closely for the same reason the scroll bar's arithmetic is: both are things she
 * is asked to believe without being able to check them. A length that says one minute for a film
 * that runs five, or a bar that sits at the end while the film carries on, is a screen that has
 * lied to her — and the next one she will not read at all.
 */
class FilmLengthTest {

    @Test
    fun `a short film is counted in seconds`() {
        assertEquals(FilmLength.Length.Seconds(8), FilmLength.of(8_000))
        assertEquals(FilmLength.Length.Seconds(35), FilmLength.of(34_400))
    }

    @Test
    fun `part of a second is still a second`() {
        // Rounding down here would put "0 Sekunden" under a button that plays something.
        assertEquals(FilmLength.Length.Seconds(1), FilmLength.of(1))
        assertEquals(FilmLength.Length.Seconds(1), FilmLength.of(900))
        assertEquals(FilmLength.Length.Seconds(2), FilmLength.of(1_001))
    }

    @Test
    fun `a full minute is a minute rather than sixty seconds`() {
        assertEquals(FilmLength.Length.Minutes(1), FilmLength.of(60_000))
        // 59.6 seconds rounds up to 60 and must not stay in seconds afterwards.
        assertEquals(FilmLength.Length.Minutes(1), FilmLength.of(59_600))
    }

    @Test
    fun `longer films are rounded to the nearest minute`() {
        assertEquals(FilmLength.Length.Minutes(2), FilmLength.of(107_000))
        assertEquals(FilmLength.Length.Minutes(1), FilmLength.of(80_000))
        assertEquals(FilmLength.Length.Minutes(4), FilmLength.of(230_000))
    }

    @Test
    fun `a film that never rounds away to nothing`() {
        // 30 seconds past the minute mark rounds to 1, not to 0, whatever the arithmetic says.
        assertEquals(FilmLength.Length.Minutes(1), FilmLength.of(61_000))
    }

    @Test
    fun `a length the phone could not read is no length at all`() {
        assertNull(FilmLength.of(0))
        // What a player reports before it has finished preparing.
        assertNull(FilmLength.of(-1))
    }

    @Test
    fun `the bar says how far through the film she is`() {
        assertEquals(0f, FilmLength.watched(0, 10_000), 0.0001f)
        assertEquals(0.5f, FilmLength.watched(5_000, 10_000), 0.0001f)
        assertEquals(1f, FilmLength.watched(10_000, 10_000), 0.0001f)
    }

    @Test
    fun `a bar is never drawn longer than its track`() {
        // Both of these are states a real VideoView reports: a duration it does not know yet, and
        // a position past the end at the moment the film completes.
        assertEquals(0f, FilmLength.watched(3_000, -1), 0.0001f)
        assertEquals(0f, FilmLength.watched(3_000, 0), 0.0001f)
        assertEquals(1f, FilmLength.watched(10_400, 10_000), 0.0001f)
    }
}
