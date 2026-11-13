package ch.heuscher.gentleconnect.data

import android.content.Context

/**
 * Which pictures she has opened in the viewer, remembered only on this phone.
 *
 * Purely local. This never calls any bridge, message store, or read-receipt function —
 * whoever posted a status must never learn she looked at it. The promise the Look screen
 * makes is that looking is silent, and this is where that promise is kept.
 */
class SeenPictures(private val context: Context) {

    fun load(): Set<String> = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    fun save(ids: Set<String>) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY, HashSet(ids))
                .apply()
        } catch (e: Exception) {
            // Nothing to do about it, and nothing worth telling her.
        }
    }

    private companion object {
        const val PREFS = "gentle_connection_seen_pictures"
        const val KEY = "ids"
    }
}
