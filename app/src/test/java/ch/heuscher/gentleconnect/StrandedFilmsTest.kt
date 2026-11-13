package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.StrandedFilms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a video message's film would be, read off where its still is. */
class StrandedFilmsTest {

    private val media = "/data/user/0/ch.heuscher.gentleconnect/files/whatsapp/media"

    @Test
    fun `the film is the still's name with the film's extension`() {
        assertEquals(
            "$media/3AAE7D03373FFCEC92BF.mp4",
            StrandedFilms.filmBeside("$media/3AAE7D03373FFCEC92BF.jpg")
        )
    }

    @Test
    fun `an upper-case extension is still a still`() {
        assertEquals("$media/A.mp4", StrandedFilms.filmBeside("$media/A.JPG"))
    }

    @Test
    fun `nothing that is not a still has a film beside it`() {
        assertNull(StrandedFilms.filmBeside(null))
        assertNull(StrandedFilms.filmBeside(""))
        assertNull(StrandedFilms.filmBeside(".jpg"))
        assertNull(StrandedFilms.filmBeside("$media/A.ogg"))
        assertNull(StrandedFilms.filmBeside("$media/A.mp4"))
    }
}
