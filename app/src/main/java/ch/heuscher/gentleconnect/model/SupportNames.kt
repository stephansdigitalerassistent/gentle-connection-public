package ch.heuscher.gentleconnect.model

/**
 * The names the help button uses for people, which are not the address book's full names.
 *
 * An address book says `Johann "Papa Opa" Hartmann`. On her screen that wrapped a button over
 * three lines and fell off the edge of the faces row; in a message to the family it reads like a
 * form. So her screen uses what the contact is actually called — the nickname when there is one —
 * and the family's messages use the first name, which every one of them recognises.
 *
 * Pure, so it is tested on the JVM.
 */
object SupportNames {

    private val quoted = Regex("[\"“„”«»]([^\"“„”«»]+)[\"“„”«»]")

    /** `Johann "Papa Opa" Hartmann` → `Papa Opa`; `Anna Muster` → `Anna`. */
    fun shortName(name: String): String =
        nickname(name) ?: firstName(name)

    /** `Johann "Papa Opa" Hartmann` → `Johann`; a name that is only a nickname keeps it. */
    fun firstName(name: String): String {
        val withoutNickname = quoted.replace(name, " ").trim()
        return withoutNickname.split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
            ?: nickname(name)
            ?: name.trim()
    }

    private fun nickname(name: String): String? =
        quoted.find(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `Johann, Eli und Fabi` — [and] is the language's own word. */
    fun join(names: List<String>, and: String): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " $and " + names.last()
    }
}
