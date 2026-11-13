package ch.heuscher.gentleconnect.model

import java.text.Normalizer
import java.util.Locale

/**
 * Finding one message, or one letter, among thousands.
 *
 * Off unless a caregiver switches it on, and that is the whole design of it. A search box is a
 * text field, and a text field on a screen she is not looking for one on is a thing to be
 * frightened of pressing — the keyboard appears, the list she was reading disappears behind it,
 * and there is no obvious way back. For most people using this phone the list is short enough to
 * scroll and search is a cost with no benefit.
 *
 * But some conversations run to thousands of messages, and "the address my daughter sent me in
 * the spring" is then genuinely unreachable by scrolling. That is a judgement about one
 * particular person, which is why it belongs to whoever looks after her rather than to a default
 * — the same reasoning as `RemoteSettings.blockUnknownCallers`.
 *
 * Pure, and therefore the part of this that CI can actually check.
 */
object Searching {

    /**
     * Whether [haystack] answers [query].
     *
     * Every word of the query must appear somewhere, in any order and in any of the fields. That
     * is what makes "anna arzt" find a message from Anna about her doctor without her having to
     * remember which word came first — she is typing what she remembers, not composing a query.
     *
     * Case and accents are ignored, because "Zurich" must find "Zürich". Somebody hunting for a
     * message from Jürg should not have to produce an umlaut on a keyboard she finds difficult,
     * and the message she is looking for is the one place the accent is guaranteed to be right.
     */
    fun matches(query: String, vararg haystack: String?): Boolean {
        val words = normalise(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true

        val text = haystack.filterNotNull().joinToString(" ") { normalise(it) }
        return words.all { text.contains(it) }
    }

    /**
     * The messages worth showing for [query]. An empty query hides nothing.
     *
     * **The name on the row is searched, not only the name of whoever wrote.** The message list
     * is one row per conversation, and a group's row is titled with the group — so searching
     * "Familie" found nothing at all while "Familie Hartmann" was sitting there on the screen,
     * unless whoever wrote last happened to be called Familie. A search that cannot find what is
     * written in front of her is worse than no search: it answers "there is no such message"
     * about a message she is looking at. [Message.displayName] is that title, and [senderName]
     * stays beside it so that a person is still findable inside a group they wrote to.
     *
     * The address goes in as well, exactly as it does for her letters: a number is what a
     * caregiver has to hand when the person it belongs to is in nobody's address book.
     */
    fun messages(query: String, messages: List<Message>): List<Message> {
        if (query.isBlank()) return messages
        return messages.filter {
            matches(query, it.displayName, it.senderName, it.senderAddress, it.text)
        }
    }

    /**
     * The letters worth showing for [query].
     *
     * The whole letter is searched and not only what is on the screen: the sentence she remembers
     * is very often the one below the fold, and a search that only looked at the snippet would
     * answer "there is no such letter" about a letter that is sitting there.
     */
    fun mails(query: String, mails: List<Mail>): List<Mail> {
        if (query.isBlank()) return mails
        return mails.filter {
            matches(query, it.senderName, it.senderAddress, it.subject, it.snippet, it.body)
        }
    }

    /** The Look screen items worth showing for [query]. An empty query hides nothing. */
    fun lookItems(query: String, items: List<LookItem>): List<LookItem> {
        if (query.isBlank()) return items
        return items.filter { item ->
            when (item) {
                is LookItem.Letter -> matches(
                    query,
                    item.mail.senderName,
                    item.mail.senderAddress,
                    item.mail.subject,
                    item.mail.snippet,
                    item.mail.body
                )
                // Every picture of the burst, not just the newest. What she remembers is the
                // caption on one of them, and it is as likely to be the ninth as the first.
                is LookItem.Picture -> item.messages.any { picture ->
                    matches(query, picture.senderName, picture.text)
                }
            }
        }
    }

    /**
     * Lower case, and accents taken off.
     *
     * `Normalizer` splits an accented letter into the letter and its accent, so stripping the
     * combining marks afterwards turns "Zürich" into "zurich" without a table of substitutions.
     * The German ß is left alone deliberately: it lower-cases to itself, and somebody typing
     * "strasse" for "Straße" is a nicety this does not need to get right to be useful.
     */
    private fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
