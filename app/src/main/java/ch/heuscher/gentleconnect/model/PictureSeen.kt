package ch.heuscher.gentleconnect.model

/**
 * Which pictures she has seen. Purely local, never reports back to the poster.
 */
object PictureSeen {
    fun unseenAny(messages: List<Message>, seen: Set<String>): Boolean {
        return messages.any { it.id !in seen }
    }

    /**
     * What she has seen, with [added] added and anything long gone dropped.
     *
     * seen + added when present is empty (the feed has not loaded yet — never wipe); otherwise
     * (seen ∩ present) + added, so the set cannot grow for ever as statuses expire.
     *
     * Takes many at once because closing the viewer marks a whole album — thirty-five ids, one
     * write. See markPicturesSeen in the view model.
     */
    fun pruned(seen: Set<String>, added: Collection<String>, present: Set<String>): Set<String> {
        if (present.isEmpty()) return seen + added
        return seen.intersect(present) + added
    }

    fun pruned(seen: Set<String>, added: String, present: Set<String>): Set<String> =
        pruned(seen, listOf(added), present)
}
