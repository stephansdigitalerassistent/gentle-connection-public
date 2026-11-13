package ch.heuscher.gentleconnect.watch

/**
 * Everything the watch and the phone say to each other, in one file both of them read.
 *
 * This is the watch's copy of `app/.../data/WearMessages.kt`. The two are separate Gradle
 * modules and cannot share code, so these strings are duplicated by hand — which is exactly why
 * they are gathered in one file at each end rather than spelled out at each call site.
 * A path typed differently at the two ends does not fail: the message is simply delivered to
 * nobody, the watch sits there having done nothing, and there is no error anywhere to find. Keep
 * the watch's copy of this file in step, and change both at once.
 */
object WearMessages {

    /**
     * The faces, phone → watch. A data item rather than a message: data items are held by the
     * Wear layer and handed over the moment the watch reconnects, so a watch that was out of
     * range while she starred somebody new still gets them. A message would simply be lost.
     */
    const val FACES_PATH = "/gentle_connection/faces"

    /** Watch → phone: ring this person. Payload is [callPayload]. */
    const val CALL_PATH = "/gentle_connection/call"

    /** Watch → phone: make a noise, I have lost you. No payload. */
    const val FIND_PHONE_PATH = "/gentle_connection/find_phone"

    /**
     * Phone → watch: the call tap could not be placed (no connection, abroad without Threema,
     * or no valid number/identity). The watch vibrates distinctly twice so she knows immediately
     * without any confusing spoken error messages from a phone that might be in another room.
     */
    const val CALL_FAILED_PATH = "/gentle_connection/call_failed"

    /** Key of the list of people inside the faces data item. */
    const val FACES_KEY = "faces"

    /**
     * Bumped on every send so the Wear layer sees the item as changed.
     *
     * Without it, re-sending an identical list is a no-op and a watch that was wiped and re-paired
     * never receives the faces at all — the phone believes it has already sent them.
     */
    const val UPDATED_AT_KEY = "updated_at"

    const val FACE_ID = "id"
    const val FACE_NAME = "name"
    const val FACE_NUMBER = "number"
    const val FACE_THREEMA_ID = "threema_id"
    const val FACE_PHOTO = "photo"

    /**
     * A person to ring, as one string.
     *
     * The number and the Threema id travel together because the phone routes the call itself —
     * see the phone's CallPlacer. The watch is not told which channel will be used and has no business
     * knowing: whether a call goes over Threema depends on whether the phone is roaming, which is
     * a fact about the phone.
     */
    fun callPayload(phoneNumber: String?, threemaId: String?): String =
        "${phoneNumber.orEmpty()}$SEPARATOR${threemaId.orEmpty()}"

    /** [callPayload] read back: number first, Threema id second, either possibly empty. */
    fun readCallPayload(payload: String): Pair<String?, String?> {
        val parts = payload.split(SEPARATOR)
        return parts.getOrNull(0)?.ifBlank { null } to parts.getOrNull(1)?.ifBlank { null }
    }

    /**
     * A character no telephone number and no Threema id can contain.
     *
     * Deliberately not a space or a dash. Her address book holds `+41 79 123 45 67`, punctuation
     * and all, and splitting that on a space would ring `+41`.
     */
    private const val SEPARATOR = "\u0000"
}
