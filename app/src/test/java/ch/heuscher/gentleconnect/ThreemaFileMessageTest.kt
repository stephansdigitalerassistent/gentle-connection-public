package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.threema.ThreemaFileMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope a forwarded photograph travels in.
 *
 * Worth testing letter by letter because every way of getting it wrong is silent: the message
 * sends, Threema accepts it, and the person at the other end sees an attachment they cannot open
 * — or nothing at all. There is no error anywhere in that chain.
 */
class ThreemaFileMessageTest {

    private val blob = "0123456789abcdef0123456789abcdef"
    private val key = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    @Test
    fun `a photograph names its blob, its key and its size`() {
        val body = ThreemaFileMessage.body(
            blobId = blob,
            thumbnailBlobId = null,
            key = key,
            mime = "image/jpeg",
            name = "bild.jpg",
            size = 20_480L
        )

        assertTrue(body.contains(""""b":"$blob""""))
        assertTrue(body.contains(""""k":"$key""""))
        assertTrue(body.contains(""""m":"image/jpeg""""))
        assertTrue(body.contains(""""n":"bild.jpg""""))
        assertTrue(body.contains(""""s":20480"""))
    }

    /** No still, no `t`: an empty blob name would be a name the recipient goes and fetches. */
    @Test
    fun `a message with no still does not claim to have one`() {
        val body = ThreemaFileMessage.body(blob, null, key, "image/jpeg", "bild.jpg", 1L)
        assertFalse(body.contains(""""t":"""))

        val withStill = ThreemaFileMessage.body(blob, "aaaa", key, "video/mp4", "video.mp4", 1L)
        assertTrue(withStill.contains(""""t":"aaaa""""))
    }

    /**
     * A photograph must arrive as a photograph.
     *
     * `j` is what the recipient's client lays the message out by: 1 draws the picture in the
     * conversation, 0 draws a paperclip and a file name.
     */
    @Test
    fun `pictures, films and voices are drawn rather than filed`() {
        listOf("image/jpeg", "video/mp4", "audio/mp4").forEach { mime ->
            val body = ThreemaFileMessage.body(blob, null, key, mime, "x", 1L)
            assertTrue("$mime should be shown inline", body.contains(""""j":1"""))
        }

        val document = ThreemaFileMessage.body(blob, null, key, "application/pdf", "x.pdf", 1L)
        assertTrue(document.contains(""""j":0"""))
    }

    @Test
    fun `words she wrote under it are carried, and silence is not`() {
        val silent = ThreemaFileMessage.body(blob, null, key, "image/jpeg", "bild.jpg", 1L)
        assertFalse(silent.contains(""""d":"""))

        val captioned = ThreemaFileMessage.body(
            blob, null, key, "image/jpeg", "bild.jpg", 1L, caption = "Grüsse"
        )
        assertTrue(captioned.contains(""""d":"Grüsse""""))
    }

    /** A quotation mark in a caption must not end the JSON string it is sitting in. */
    @Test
    fun `a caption cannot break the message it is written in`() {
        val body = ThreemaFileMessage.body(
            blob, null, key, "image/jpeg", "bild.jpg", 1L, caption = "sagte \"hallo\"\nund ging"
        )
        assertTrue(body.contains("""\"hallo\""""))
        assertTrue(body.contains("""\n"""))
    }

    @Test
    fun `the body goes on the wire as the bytes of its own text`() {
        assertEquals("7b7d", ThreemaFileMessage.hex("{}"))
        // Two bytes for one letter, because the wire carries UTF-8 and not characters.
        assertEquals("c3bc", ThreemaFileMessage.hex("ü"))
    }

    @Test
    fun `what the file is comes from what it is called`() {
        assertEquals("image/jpeg", ThreemaFileMessage.mimeOf("/data/threema-media/abc.jpg"))
        assertEquals("audio/mp4", ThreemaFileMessage.mimeOf("/data/threema-media/abc.m4a"))
        assertEquals("video/mp4", ThreemaFileMessage.mimeOf("/data/threema-media/abc.mp4"))
        // Not guessed. The recipient's phone looks inside the file anyway.
        assertEquals("application/octet-stream", ThreemaFileMessage.mimeOf("/data/x.bin"))
    }

    /**
     * The name that goes out is not the name it has here.
     *
     * On this phone an attachment is called after the message it arrived in, which would tell the
     * recipient which of her messages this came out of and mean nothing to them besides.
     */
    @Test
    fun `the file goes out under a plain name`() {
        assertEquals(
            "bild.jpg",
            ThreemaFileMessage.nameFor("/data/threema-media/threema.1a2b3c.jpg", "image/jpeg")
        )
        assertEquals(
            "sprachnachricht.m4a",
            ThreemaFileMessage.nameFor("/data/threema-media/threema.1a2b3c.m4a", "audio/mp4")
        )
    }

    /** `FILE = 0x17` in Threema's own `common.proto`, and the bridge reads it as that byte. */
    @Test
    fun `a file message is type twenty-three`() {
        assertEquals(0x17, ThreemaFileMessage.MESSAGE_TYPE)
    }
}
