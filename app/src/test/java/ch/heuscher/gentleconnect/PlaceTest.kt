package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How appointment locations are tidied and cut down for the home screen.
 */
class PlaceTest {

    @Test
    fun `null or blank gives null`() {
        assertNull(Place.shorten(null))
        assertNull(Place.shorten(""))
        assertNull(Place.shorten("   "))
        assertNull(Place.shorten("\n\t\n  "))
    }

    @Test
    fun `a Google Meet link is not a place and returns null`() {
        assertNull(Place.shorten("meet.google.com/abc-def"))
        assertNull(Place.shorten("  meet.google.com/xyz-123  "))
    }

    @Test
    fun `a link with a scheme is not a place`() {
        assertNull(Place.shorten("https://meet.google.com/abc-def"))
        assertNull(Place.shorten("http://zoom.us/j/123456789"))
        assertNull(Place.shorten("https://teams.microsoft.com/l/meetup-join/123"))
    }

    @Test
    fun `a link starting with www is not a place`() {
        assertNull(Place.shorten("www.zoom.us"))
        assertNull(Place.shorten("WWW.GOOGLE.COM"))
    }

    @Test
    fun `first non-blank line is taken when there are line breaks`() {
        assertEquals(
            "Praxis Dr. Müller",
            Place.shorten("Praxis Dr. Müller\n2. Stock\nBahnhofstrasse 12")
        )
        assertEquals(
            "Praxis Dr. Müller",
            Place.shorten("\n  \nPraxis Dr. Müller\nBahnhofstrasse 12")
        )
    }

    @Test
    fun `worked example with practice name, street, zip and country`() {
        assertEquals(
            "Praxis Dr. Müller, Bahnhofstrasse 12",
            Place.shorten("Praxis Dr. Müller, Bahnhofstrasse 12, 8001 Zürich, Schweiz")
        )
    }

    @Test
    fun `worked example with street, zip and country`() {
        assertEquals(
            "Bahnhofstrasse 12, 8001 Zürich",
            Place.shorten("Bahnhofstrasse 12, 8001 Zürich, Schweiz")
        )
    }

    @Test
    fun `trailing country names are dropped in all four national languages and English`() {
        val countries = listOf(
            "Schweiz", "Suisse", "Svizzera", "Switzerland",
            "Deutschland", "Germany",
            "Österreich", "Austria",
            "SCHWEIZ", "switzerland", "deutschland"
        )
        for (country in countries) {
            assertEquals(
                "Bahnhofstrasse 12",
                Place.shorten("Bahnhofstrasse 12, $country")
            )
        }
    }

    @Test
    fun `a location that is only a country returns null`() {
        assertNull(Place.shorten("Schweiz"))
        assertNull(Place.shorten("Switzerland"))
        assertNull(Place.shorten("Deutschland"))
        assertNull(Place.shorten("Österreich"))
        assertNull(Place.shorten(" , Schweiz, "))
    }

    @Test
    fun `at most the first two parts are kept`() {
        assertEquals(
            "Spital Bethanien, Toblerstrasse 51",
            Place.shorten("Spital Bethanien, Toblerstrasse 51, 8044 Zürich")
        )
    }

    @Test
    fun `a word with a full stop in the middle is a place, not a host`() {
        // The rule that throws links away used to throw these away too: one word, one dot, no
        // space. An appointment at her dentist would have arrived with no place on it at all.
        assertEquals("St.Gallen", Place.shorten("St.Gallen"))
        assertEquals("Bahnhofstr.", Place.shorten("Bahnhofstr."))
        assertEquals("Dr.Müller", Place.shorten("Dr.Müller"))
    }

    @Test
    fun `a bare host with a national domain is still a link`() {
        assertNull(Place.shorten("spital-bethanien.ch"))
        assertNull(Place.shorten("meet.google.com"))
    }

    @Test
    fun `a single part place is kept intact`() {
        assertEquals("Praxis Dr. Müller", Place.shorten("Praxis Dr. Müller"))
        assertEquals("Zürich", Place.shorten("Zürich"))
        assertEquals("St. Gallen", Place.shorten("St. Gallen"))
    }
}
