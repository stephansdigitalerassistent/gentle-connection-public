package ch.heuscher.gentleconnect.model

/**
 * Where the film of a video message would be, given where its still is.
 *
 * Both bridges write a video message as two files under one name — the still WhatsApp packs into
 * the message as `<id>.jpg` at once, the film as `<id>.mp4` once it has downloaded — and tell the
 * app about each with its own event. Read off the Fold on 2026-10-19: four films from Hans-Peter
 * Jud were on disk beside their stills while their messages had no film at all, so she saw a
 * thumbnail with nothing to press. The film's event never reached the database. The file did, and
 * the file is what this goes by: `ConversationRepository.attachStrandedFilms` attaches it at
 * start, whatever happened to the event.
 *
 * Only the name is worked out here. Whether the film is really there is the caller's question,
 * and the answer is nearly always no: most stills belong to photographs, which have no film.
 */
object StrandedFilms {

    private const val STILL = ".jpg"
    private const val FILM = ".mp4"

    /** The film beside [stillPath], or null when that is not a still a bridge would have written. */
    fun filmBeside(stillPath: String?): String? {
        val still = stillPath?.trim().orEmpty()
        if (still.length <= STILL.length || !still.endsWith(STILL, ignoreCase = true)) return null
        return still.dropLast(STILL.length) + FILM
    }
}
