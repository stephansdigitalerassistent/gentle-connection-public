package ch.heuscher.gentleconnect.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoButtonPlacementTest {

    @Test
    fun `sane maps known values and trims whitespace`() {
        assertEquals(PhotoButtonPlacement.NONE, PhotoButtonPlacement.sane("none"))
        assertEquals(PhotoButtonPlacement.NONE, PhotoButtonPlacement.sane(" NONE "))
        assertEquals(PhotoButtonPlacement.CLOCK, PhotoButtonPlacement.sane("clock"))
        assertEquals(PhotoButtonPlacement.CLOCK, PhotoButtonPlacement.sane(" Clock "))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane("card"))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane(" CARD "))
        assertEquals(PhotoButtonPlacement.FACES, PhotoButtonPlacement.sane("faces"))
        assertEquals(PhotoButtonPlacement.FACES, PhotoButtonPlacement.sane(" FACES "))
        assertEquals(PhotoButtonPlacement.BOTTOM, PhotoButtonPlacement.sane("bottom"))
        assertEquals(PhotoButtonPlacement.BOTTOM, PhotoButtonPlacement.sane(" BOTTOM "))
    }

    @Test
    fun `sane defaults to card on null blank or unknown values`() {
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane(null))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane(""))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane("   "))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane("top"))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane("header"))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.sane("unknown"))
    }

    @Test
    fun `effective keeps placement when payment card is not shown`() {
        assertEquals(PhotoButtonPlacement.NONE, PhotoButtonPlacement.effective(PhotoButtonPlacement.NONE, false))
        assertEquals(PhotoButtonPlacement.CLOCK, PhotoButtonPlacement.effective(PhotoButtonPlacement.CLOCK, false))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.effective(PhotoButtonPlacement.CARD, false))
        assertEquals(PhotoButtonPlacement.FACES, PhotoButtonPlacement.effective(PhotoButtonPlacement.FACES, false))
        assertEquals(PhotoButtonPlacement.BOTTOM, PhotoButtonPlacement.effective(PhotoButtonPlacement.BOTTOM, false))
    }

    @Test
    fun `effective falls back from clock to card when payment card is shown`() {
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.effective(PhotoButtonPlacement.CLOCK, true))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.effective(" CLOCK ", true))
    }

    @Test
    fun `effective preserves other placements when payment card is shown`() {
        assertEquals(PhotoButtonPlacement.NONE, PhotoButtonPlacement.effective(PhotoButtonPlacement.NONE, true))
        assertEquals(PhotoButtonPlacement.CARD, PhotoButtonPlacement.effective(PhotoButtonPlacement.CARD, true))
        assertEquals(PhotoButtonPlacement.FACES, PhotoButtonPlacement.effective(PhotoButtonPlacement.FACES, true))
        assertEquals(PhotoButtonPlacement.BOTTOM, PhotoButtonPlacement.effective(PhotoButtonPlacement.BOTTOM, true))
    }
}
