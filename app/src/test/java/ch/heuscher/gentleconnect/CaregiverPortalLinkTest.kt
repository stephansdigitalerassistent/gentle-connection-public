package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.CaregiverPortalLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverPortalLinkTest {

    @Test
    fun `the link carries the code so nobody has to retype it`() {
        assertEquals(
            "https://example-project.web.app/#code=Q0227S",
            CaregiverPortalLink.forProject("example-project", "Q0227S")
        )
    }

    @Test
    fun `the code travels in the fragment and never in the query`() {
        // A query string reaches the server: Hosting logs it, a Referer can carry it onward, and
        // anything in front of the origin sees it. A fragment never leaves the browser.
        val link = CaregiverPortalLink.forProject("example-project", "Q0227S")!!
        assertTrue(link.contains("#code="))
        assertFalse(link.contains("?"))
    }

    @Test
    fun `no code still gives a usable address`() {
        // Before the phone has generated one. The caregiver can sign in and type it.
        assertEquals(
            "https://example-project.web.app",
            CaregiverPortalLink.forProject("example-project", null)
        )
        assertEquals(
            "https://example-project.web.app",
            CaregiverPortalLink.forProject("example-project", "   ")
        )
    }

    @Test
    fun `a malformed code is left out rather than shipped in a link that fails on arrival`() {
        assertEquals(
            "https://example-project.web.app",
            CaregiverPortalLink.forProject("example-project", "TOO-LONG-CODE")
        )
        assertEquals(
            "https://example-project.web.app",
            CaregiverPortalLink.forProject("example-project", "AB#123")
        )
    }

    @Test
    fun `the code is normalised the way the phone prints it`() {
        assertEquals(
            "https://example-project.web.app/#code=Q0227S",
            CaregiverPortalLink.forProject("example-project", " q0227s ")
        )
    }

    @Test
    fun `a fork gets its own address rather than this one's`() {
        assertEquals(
            "https://someone-elses-project.web.app/#code=ABC123",
            CaregiverPortalLink.forProject("someone-elses-project", "ABC123")
        )
    }

    @Test
    fun `no firebase project means no link at all`() {
        // The dialog offers nothing rather than a button that opens a page that does not exist.
        assertNull(CaregiverPortalLink.forProject(null, "Q0227S"))
        assertNull(CaregiverPortalLink.forProject("", "Q0227S"))
        assertNull(CaregiverPortalLink.forProject("   ", "Q0227S"))
    }

    @Test
    fun `well formed is six characters of the alphabet the phone generates`() {
        assertTrue(CaregiverPortalLink.isWellFormed("Q0227S"))
        assertTrue(CaregiverPortalLink.isWellFormed("ABCDEF"))
        assertTrue(CaregiverPortalLink.isWellFormed("012345"))
        assertFalse(CaregiverPortalLink.isWellFormed(""))
        assertFalse(CaregiverPortalLink.isWellFormed("Q0227"))
        assertFalse(CaregiverPortalLink.isWellFormed("Q0227SS"))
        assertFalse(CaregiverPortalLink.isWellFormed("q0227s"))
        assertFalse(CaregiverPortalLink.isWellFormed("Q022 S"))
    }

    @Test
    fun `both ends agree on the key without either writing it down twice`() {
        assertEquals("code", CaregiverPortalLink.CODE_FRAGMENT_KEY)
    }
}
