package ch.heuscher.gentleconnect.model

/**
 * Tidies the place an appointment happens at down to what her screen can hold.
 *
 * An appointment location in Google Calendar is whatever someone typed into a text field or
 * whatever Google Meet automatically attached. On a home screen that gives an appointment three
 * lines at most, printing "https://meet.google.com/abc-def-ghi" or a four-line postal address
 * complete with country code is worse than printing nothing: a link cannot be tapped here, and
 * three lines of Swiss postal routing crowd out the name of the place she is actually going to.
 *
 * She knows which country she is in, so countries are dropped; a link is rejected entirely;
 * and a multi-part address is cut down to at most the first two parts — usually the practice or
 * building name and the street.
 */
object Place {

    private val COUNTRIES = setOf(
        "schweiz",
        "suisse",
        "svizzera",
        "switzerland",
        "deutschland",
        "germany",
        "österreich",
        "austria"
    )

    /**
     * Shortens a raw location string to at most two meaningful components, or null if it contains
     * no usable physical place.
     */
    fun shorten(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        // Take the first non-blank line if the field contains line breaks.
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        // Links and virtual meetings are not physical places. Printing an unclickable Meet, Zoom
        // or Teams URL on her screen is useless noise.
        if (isLink(firstLine)) return null

        val parts = firstLine.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        if (parts.isEmpty()) return null

        // Drop a trailing country part. She knows which country she is in.
        if (parts.last().lowercase() in COUNTRIES) {
            parts.removeAt(parts.lastIndex)
        }

        if (parts.isEmpty()) return null

        return parts.take(2).joinToString(", ").ifBlank { null }
    }

    /**
     * Whether this is a link rather than a place she can walk into.
     *
     * A host is recognised by what it ends in and not merely by having a full stop in the middle
     * of one word, which is the whole difference between "meet.google.com/abc-def" and
     * "St.Gallen". Both are a single word with a dot in it; only one of them is a meeting, and
     * throwing the other away would leave the appointment with no place on it at all.
     */
    private fun isLink(text: String): Boolean {
        if (text.contains("://")) return true
        if (text.startsWith("www.", ignoreCase = true)) return true
        return HOST.matches(text)
    }

    private val HOST = Regex(
        "^[\\w-]+(\\.[\\w-]+)*\\.(com|ch|de|at|li|eu|org|net|io|us|app|info)(/.*)?$",
        RegexOption.IGNORE_CASE
    )
}
