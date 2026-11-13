package ch.heuscher.gentleconnect.data

/**
 * What several people are called on her screen.
 *
 * **Never "group", never "Threema group", never a messenger's name.** Several people are several
 * people, and the word "group" would be one more thing to recognise for no gain — she has never
 * needed it to understand that her family writes to her together.
 *
 * In order of preference: the name the group gave itself, which is usually already human
 * ("Familie"); otherwise the members' names as she knows them.
 */
object GroupDescription {

    /** Past this many, names stop being a list she reads and start being a wall she skips. */
    private const val MAX_NAMED = 3

    /** How many names survive the cutoff, so that the count that follows means something. */
    private const val NAMED_BESIDE_COUNT = 2

    /**
     * @param groupName what the group calls itself, if anything.
     * @param memberNames the members she would recognise, **already without her own name** — she
     *   is not one of the people a conversation is described by.
     * @param conjunction "and" / "und".
     * @param others how "%d others" reads in her language.
     * @return null when there is nothing truthful to say — a group with no name whose members we
     *   have never been told. The caller falls back to whoever wrote, rather than to a placeholder
     *   she would have to interpret.
     */
    fun of(
        groupName: String?,
        memberNames: List<String>,
        conjunction: String,
        others: (Int) -> String
    ): String? {
        groupName?.trim()?.ifEmpty { null }?.let { return it }

        val names = memberNames.mapNotNull { it.trim().ifEmpty { null } }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names.first()
            names.size <= MAX_NAMED -> {
                val leading = names.dropLast(1).joinToString(", ")
                "$leading $conjunction ${names.last()}"
            }
            else -> {
                val leading = names.take(NAMED_BESIDE_COUNT).joinToString(", ")
                "$leading $conjunction ${others(names.size - NAMED_BESIDE_COUNT)}"
            }
        }
    }
}
