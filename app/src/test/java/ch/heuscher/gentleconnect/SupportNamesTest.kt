package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportNames
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportNamesTest {

    @Test
    fun `her screen uses the nickname the contact is saved with`() {
        assertEquals("Papa Opa", SupportNames.shortName("Johann \"Papa Opa\" Hartmann"))
        assertEquals("Eli", SupportNames.shortName("Eliane \"Eli\" Hartmann"))
    }

    @Test
    fun `without a nickname her screen uses the first name`() {
        assertEquals("Anna", SupportNames.shortName("Anna Muster"))
        assertEquals("Jana", SupportNames.shortName("Jana"))
    }

    @Test
    fun `the family's messages use the first name, not the nickname`() {
        assertEquals("Johann", SupportNames.firstName("Johann \"Papa Opa\" Hartmann"))
        assertEquals("Julia", SupportNames.firstName("Julia Hartmann-Weber"))
        assertEquals("Nono", SupportNames.firstName("\"Nono\""))
    }

    @Test
    fun `names are joined the way a sentence lists people`() {
        assertEquals("", SupportNames.join(emptyList(), "und"))
        assertEquals("Eli", SupportNames.join(listOf("Eli"), "und"))
        assertEquals("Eli und Fabi", SupportNames.join(listOf("Eli", "Fabi"), "und"))
        assertEquals("Johann, Eli und Fabi", SupportNames.join(listOf("Johann", "Eli", "Fabi"), "und"))
    }
}
