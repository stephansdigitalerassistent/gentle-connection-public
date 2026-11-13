package ch.heuscher.gentleconnect.model

/**
 * Whether there is anything here for the voice to say.
 *
 * Read out of her phone: of 558 picture statuses with no caption at all, a good many of the rest
 * carry nothing but a face — "[Foto] 😊", "[Foto] 🥰", "[Foto] 🎉🎉🎉". A caption like that belongs on
 * the screen, where she sees what her daughter meant by it, and has no business on a Read-aloud
 * button: pressing it gets "Luki posted this." and a silence where the little picture was, which is
 * a button that seems broken.
 *
 * So the button asks for letters or digits. Punctuation and emoji alone are not words, in any
 * language this phone speaks — `isLetterOrDigit` is Unicode's own answer, so Greek, Cyrillic and
 * the accented letters of her own German all count, and only the pictures do not.
 */
object SpokenWords {

    fun worthSaying(text: String): Boolean = text.any { it.isLetterOrDigit() }
}
