package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.TapZone
import ch.heuscher.gentleconnect.model.ViewerNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerNavigationTest {

    @Test
    fun `zone falls back to NONE for non-positive width`() {
        assertEquals(TapZone.NONE, ViewerNavigation.zoneOf(10f, 0f))
        assertEquals(TapZone.NONE, ViewerNavigation.zoneOf(10f, -10f))
    }

    @Test
    fun `left third is PREVIOUS`() {
        assertEquals(TapZone.PREVIOUS, ViewerNavigation.zoneOf(29f, 90f))
        assertEquals(TapZone.PREVIOUS, ViewerNavigation.zoneOf(-10f, 90f))
    }

    @Test
    fun `middle third is NONE`() {
        assertEquals(TapZone.NONE, ViewerNavigation.zoneOf(30f, 90f))
        assertEquals(TapZone.NONE, ViewerNavigation.zoneOf(45f, 90f))
        assertEquals(TapZone.NONE, ViewerNavigation.zoneOf(60f, 90f))
    }

    @Test
    fun `right third is NEXT`() {
        assertEquals(TapZone.NEXT, ViewerNavigation.zoneOf(61f, 90f))
        assertEquals(TapZone.NEXT, ViewerNavigation.zoneOf(100f, 90f))
    }

    @Test
    fun `target clamps to bounds and does not wrap`() {
        val count = 5
        
        // Normal bounds
        assertEquals(1, ViewerNavigation.target(2, TapZone.PREVIOUS, count))
        assertEquals(3, ViewerNavigation.target(2, TapZone.NEXT, count))
        assertEquals(2, ViewerNavigation.target(2, TapZone.NONE, count))

        // Left bound
        assertEquals(0, ViewerNavigation.target(0, TapZone.PREVIOUS, count))
        
        // Right bound
        assertEquals(4, ViewerNavigation.target(4, TapZone.NEXT, count))
    }
    
    @Test
    fun `target with zero count returns zero`() {
        assertEquals(0, ViewerNavigation.target(0, TapZone.NEXT, 0))
    }
}
