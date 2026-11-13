package ch.heuscher.gentleconnect.model

/**
 * How loudly a letter is drawn.
 *
 * Not *whether* — every letter is shown now. This is the difference between a letter from her
 * daughter and a circular from a supermarket, which is a difference she can see at a glance in
 * a paper letterbox and could not see here at all.
 */
enum class MailProminence {
    /** From somebody in her address book, or on the caregiver allowlist. Drawn at full strength. */
    FROM_SOMEONE_SHE_KNOWS,

    /** From anybody else. Shown, always — but quietly, and never on her home screen. */
    FROM_A_STRANGER
}

/**
 * The rules for showing every letter without letting the post bury the letters.
 *
 * The filter this replaces was a gate: mail from an address that was not in her contacts was
 * fetched, stored, and never displayed. That is the one failure she cannot detect — a letter
 * that never appears leaves nothing to ask about — and it was silently deciding that the
 * surgery, the pension office and the neighbour who moved were nobody.
 *
 * Showing everything trades that for the opposite failure: noise, on a screen whose entire
 * purpose is not being noisy. So "show everything" is not the whole rule. The rest of it is
 * here, and it is two decisions:
 *
 *  - **Her Post screen shows every letter**, strangers included, in date order, with the ones
 *    from people she knows drawn at full strength and the rest drawn quietly.
 *  - **Her home screen shows only letters from people she know.** The home screen has room for
 *    a handful, chosen unread-first — so a single morning of newsletters would take every slot
 *    and push a letter from her son off a screen she may never scroll past. The Post screen is
 *    one tap away and holds everything.
 *
 * Pure, because this is the decision and CI cannot test the drawing.
 */
object MailPresentation {

    /** Whether this letter is from somebody she knows. */
    fun prominenceOf(mail: Mail): MailProminence =
        if (mail.isFromKnownSender) MailProminence.FROM_SOMEONE_SHE_KNOWS
        else MailProminence.FROM_A_STRANGER

    /**
     * Everything, for her Post screen — unless a caregiver has asked for only the letters from
     * people she knows ([RemoteSettings.mailOnlyKnownSenders]).
     *
     * Kept as a named function rather than left implicit, so that "the Post screen hides
     * nothing by default" is a statement in the code with a test against it, rather than the
     * absence of a filter that somebody could reintroduce without noticing what it used to cost.
     */
    fun forPostScreen(mails: List<Mail>, onlyKnownSenders: Boolean = false): List<Mail> =
        if (onlyKnownSenders) mails.filter { it.isFromKnownSender } else mails

    /**
     * Only the letters from people she knows, for her home screen.
     *
     * Ordering is left to [Home.mails], which puts unread first — this only decides who is
     * eligible for those few slots.
     */
    fun forHomeScreen(mails: List<Mail>): List<Mail> =
        mails.filter { it.isFromKnownSender }

    /**
     * Whether a letter should make her home screen look like something is waiting.
     *
     * A stranger's letter is never urgent by definition here: nobody she knows sent it. Letting
     * an unread newsletter drive the same emphasis as an unread letter from her daughter would
     * make the emphasis mean nothing within a week.
     */
    fun callsForAttention(mail: Mail): Boolean = mail.isUnread && mail.isFromKnownSender

    /** How many letters are waiting that she would actually want to know about. */
    fun attentionCount(mails: List<Mail>): Int = mails.count(::callsForAttention)
}
