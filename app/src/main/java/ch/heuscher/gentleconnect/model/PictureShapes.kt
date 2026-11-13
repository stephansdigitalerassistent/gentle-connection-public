package ch.heuscher.gentleconnect.model

/**
 * The shape a picture turned out to be, remembered from the last time it was on the screen.
 *
 * A photograph is read off the disk on a background thread, and until that read finishes there is
 * nothing to draw and the card holding it is only as tall as its title. That is fine going down a
 * list for the first time. It is not fine coming back to one: the list is put back at the card she
 * left and at how far into that card she had scrolled, and if the card is momentarily a tenth of
 * its real height, the rest of that scroll runs on into the cards below it. She opened a letter,
 * pressed back, and landed two pictures further down her post with the letter she had just read
 * gone off the top of the screen.
 *
 * So the shape is kept even though the picture is not. A shape is one number; the picture it
 * describes is several megabytes, and this app is a launcher that has to survive being in the
 * background — see the note on the frame cache in MessagePhoto for what caching whole bitmaps
 * would cost. Knowing the shape is enough to hold the space open while the picture is fetched
 * again, which is all that was needed for her to land where she was.
 *
 * Bounded anyway. Four years of imported history is a lot of paths, and an unbounded map of them
 * is a leak that grows for as long as the phone is on, however small each entry is.
 */
object PictureShapes {

    /**
     * How many pictures are remembered. Generous, because an entry is a string and a float rather
     * than an image, and because the value of this is entirely in the picture she scrolled past a
     * minute ago still being known.
     */
    const val KEEP = 512

    private val shapes = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>) =
            size > KEEP
    }

    /**
     * What was drawn, so that the same picture can be given the same room next time.
     *
     * The ratio wanted here is the one it was *drawn* at — [PhotoShape.ratioFor], not the raw
     * proportions of the file. A photograph too tall or too wide to show whole is drawn at the
     * nearest limit, and reserving the shape it was taken in rather than the shape it appears in
     * would leave exactly the gap this exists to close.
     */
    fun note(key: String, ratio: Float) {
        if (key.isBlank() || ratio <= 0f || !ratio.isFinite()) return
        synchronized(shapes) { shapes[key] = ratio }
    }

    /** The shape that picture was last drawn in, or null if it has never been on the screen. */
    fun of(key: String): Float? {
        if (key.isBlank()) return null
        return synchronized(shapes) { shapes[key] }
    }

    /**
     * Which picture a card is showing.
     *
     * A film is drawn from its own frame once it has landed and from the still that came in the
     * message before that — two files, one picture, and one shape between them. The film's path
     * names the pair whenever there is a film, so that the space held open for the still is the
     * space the frame will want.
     */
    fun keyFor(path: String?, videoPath: String?): String =
        videoPath?.takeIf { it.isNotBlank() } ?: path?.takeIf { it.isNotBlank() }.orEmpty()

    /** For tests, which must not inherit what an earlier one looked at. */
    fun forget() {
        synchronized(shapes) { shapes.clear() }
    }
}
