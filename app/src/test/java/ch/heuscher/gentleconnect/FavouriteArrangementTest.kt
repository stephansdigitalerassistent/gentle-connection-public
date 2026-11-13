package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.FavouriteArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteArrangementTest {

    private fun contact(id: String, name: String) =
        Contact(id = id, name = name, phoneNumber = "", photoUri = null, order = 0, isFavorite = true)

    private val claireLise = contact("c1", "Claire-Lise")
    private val karolina = contact("c2", "Karolina Alder")
    private val eliane = contact("c3", "Eliane Hartmann")

    private val starred = listOf(claireLise, karolina, eliane)

    @Test
    fun `the caregiver's order wins`() {
        val arranged = FavouriteArrangement.arrange(starred, listOf("c3", "c1", "c2"))
        assertEquals(listOf("Eliane Hartmann", "Claire-Lise", "Karolina Alder"), arranged.map { it.name })
    }

    @Test
    fun `order is renumbered from one so the rest of the app can just sort`() {
        val arranged = FavouriteArrangement.arrange(starred, listOf("c3", "c1", "c2"))
        assertEquals(listOf(1, 2, 3), arranged.map { it.order })
    }

    @Test
    fun `no arrangement yet means the address book's own order`() {
        val arranged = FavouriteArrangement.arrange(starred, emptyList())
        assertEquals(listOf("Claire-Lise", "Karolina Alder", "Eliane Hartmann"), arranged.map { it.name })
    }

    @Test
    fun `somebody newly starred is appended, never inserted at the top`() {
        // The top of her home screen is the most valuable space in the app. A face arriving there
        // because a relative starred a contact — with nobody having decided it — is a change she
        // cannot account for, and the top is where a stranger would do the most harm.
        val newlyStarred = contact("c4", "Werner")
        val arranged = FavouriteArrangement.arrange(
            starred + newlyStarred,
            listOf("c3", "c1", "c2")
        )
        assertEquals("Werner", arranged.last().name)
        assertEquals(4, arranged.size)
    }

    @Test
    fun `unstarring on the phone removes them, with no second step on the website`() {
        val arranged = FavouriteArrangement.arrange(listOf(claireLise, eliane), listOf("c3", "c1", "c2"))
        assertEquals(listOf("Eliane Hartmann", "Claire-Lise"), arranged.map { it.name })
    }

    @Test
    fun `an order full of people who are gone leaves the starred list intact`() {
        val arranged = FavouriteArrangement.arrange(starred, listOf("gone1", "gone2"))
        assertEquals(listOf("Claire-Lise", "Karolina Alder", "Eliane Hartmann"), arranged.map { it.name })
    }

    @Test
    fun `nobody starred is nobody on the home screen`() {
        assertTrue(FavouriteArrangement.arrange(emptyList(), listOf("c1")).isEmpty())
    }

    @Test
    fun `a duplicated id does not draw the same person twice`() {
        // The order list is written by a browser and read here. Two identical faces that do the
        // same thing are not a choice she can make.
        val arranged = FavouriteArrangement.arrange(starred, listOf("c2", "c2", "c1"))
        assertEquals(listOf("Karolina Alder", "Claire-Lise", "Eliane Hartmann"), arranged.map { it.name })
        assertEquals(3, arranged.size)
    }

    @Test
    fun `everyone arranged is marked a favourite`() {
        val notMarked = claireLise.copy(isFavorite = false)
        val arranged = FavouriteArrangement.arrange(listOf(notMarked), listOf("c1"))
        assertTrue(arranged.single().isFavorite)
    }

    @Test
    fun `the stored order is ids and nothing else`() {
        // Storing names and numbers beside each id would be a second copy of her address book:
        // stale the moment somebody fixes a spelling, and needing protection all over again.
        assertEquals(listOf("c1", "c2", "c3"), FavouriteArrangement.orderOf(starred))
    }

    @Test
    fun `moving someone up and down`() {
        val order = listOf("c1", "c2", "c3")
        assertEquals(listOf("c2", "c1", "c3"), FavouriteArrangement.move(order, 1, -1))
        assertEquals(listOf("c1", "c3", "c2"), FavouriteArrangement.move(order, 1, 1))
    }

    @Test
    fun `the arrows at the ends do nothing rather than something surprising`() {
        val order = listOf("c1", "c2", "c3")
        assertEquals(order, FavouriteArrangement.move(order, 0, -1))
        assertEquals(order, FavouriteArrangement.move(order, 2, 1))
        assertEquals(order, FavouriteArrangement.move(order, 9, -1))
    }
}
