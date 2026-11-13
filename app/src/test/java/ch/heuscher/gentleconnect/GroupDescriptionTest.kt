package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.GroupDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupDescriptionTest {

    private fun describe(name: String?, vararg members: String) = GroupDescription.of(
        groupName = name,
        memberNames = members.toList(),
        conjunction = "and"
    ) { count -> "$count others" }

    @Test
    fun `a group that named itself keeps its name`() {
        assertEquals("Familie", describe("Familie", "Anna", "Peter", "Ruth"))
    }

    @Test
    fun `otherwise it is the people in it`() {
        assertEquals("Anna", describe(null, "Anna"))
        assertEquals("Anna and Peter", describe(null, "Anna", "Peter"))
        assertEquals("Anna, Peter and Ruth", describe(null, "Anna", "Peter", "Ruth"))
    }

    @Test
    fun `past three, two names and a count`() {
        // A list of names is something she reads; a wall of them is something she skips, and the
        // conversation then has no name she can recognise at all.
        assertEquals("Anna, Peter and 3 others", describe(null, "Anna", "Peter", "Ruth", "Eva", "Max"))
        assertEquals("Anna, Peter and 2 others", describe(null, "Anna", "Peter", "Ruth", "Eva"))
    }

    @Test
    fun `a group we have been told nothing about is not given a placeholder`() {
        // Null, so the caller falls back to whoever wrote. "Unknown group" would be a phrase she
        // has to interpret, about a fault she did not cause.
        assertNull(describe(null))
        assertNull(describe("   ", "  ", ""))
    }

    @Test
    fun `an empty name is not a name`() {
        assertEquals("Anna and Peter", describe("", "Anna", "Peter"))
        assertEquals("Anna and Peter", describe("   ", "Anna", "Peter"))
    }

    @Test
    fun `blank members do not become anonymous participants`() {
        assertEquals("Anna and Peter", describe(null, "Anna", "  ", "Peter"))
    }

    @Test
    fun `the words come from outside, so both languages read properly`() {
        val german = GroupDescription.of(
            groupName = null,
            memberNames = listOf("Anna", "Peter", "Ruth", "Eva", "Max"),
            conjunction = "und"
        ) { count -> "$count weitere" }

        assertEquals("Anna, Peter und 3 weitere", german)
    }
}
