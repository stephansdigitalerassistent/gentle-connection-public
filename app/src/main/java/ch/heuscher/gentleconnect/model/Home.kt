package ch.heuscher.gentleconnect.model

/**
 * What her home screen shows, and how much of it.
 *
 * Two rules, together in one place because they are the same rule wearing two hats: home is short,
 * and home is people. The tabs hold everything — Call the whole address book, Message the whole
 * flat list including the machine messages home filters out.
 *
 * Both halves were found by looking at the phone. The one card at the top of the screen said
 * "ParkApp wrote a message" while the message from her family sat below the fold: companies write
 * far more often than her children do, so recency alone hands them the screen. And the favourites
 * were a sideways-scrolling strip that fitted three of eight faces, with the four people she can
 * actually write to all off the right-hand edge.
 *
 * Pure, because CI here runs JVM unit tests only, and these are exactly the kind of rules that
 * quietly stop holding.
 */
object Home {

    /**
     * How many rows each of the lists on her home screen gets.
     *
     * Two. It was five once, then three, and each cut came from the same measurement on the real
     * phone: five favourites filled the screen on their own and cut the word "Messages" in half at
     * the bottom edge, and half a word at the bottom of a screen does not read as "there is more
     * below" — it reads as the screen having ended there. Three had the same fault once the
     * calendar arrived above it and started using its own lines.
     *
     * The same number for every section so the screen stays a screen rather than becoming
     * something to work through, and so no one section can quietly grow and push the others off.
     *
     * With a bound this tight, *which* two is not tidiness but the whole thing: one busy day of
     * automated messages and her daughter's message never reaches the home screen at all. What
     * does not fit is not lost — each section says so, and says where the rest is.
     */
    const val LIST_LENGTH = 2

    /**
     * The people on her home screen, in the order somebody chose for her.
     *
     * Not re-sorted here. The order comes from the caregiver's settings, and a home screen that
     * reshuffled itself by who wrote most recently would move her son's face out from under her
     * thumb between one week and the next.
     */
    fun favourites(contacts: List<Contact>): List<Contact> = contacts.take(LIST_LENGTH)

    /**
     * The messages her home screen lists — people only, unread first, newest first within that.
     *
     * Unread first because an unopened message from her son should not be pushed under the fold by
     * an opened one from her daughter. Within each half the incoming order is kept, which is
     * newest first.
     */
    fun messages(messages: List<Message>): List<Message> {
        val fromPeople = messages.filter { it.senderKind == SenderKind.PERSON && !it.isStatus }
        val (unread, read) = fromPeople.partition { it.isUnread }
        // One row per person — see [PictureFlood.oneRowPerSender]. With two rows in total, twelve
        // pictures from one afternoon would otherwise be the whole of her home screen, and the
        // message from her daughter would never reach it. The Message tab still has every one.
        return PictureFlood.oneRowPerSender(unread + read).take(LIST_LENGTH)
    }

    /**
     * The pictures her home screen shows — newest first, and only a few.
     *
     * Not sorted by unread, unlike everything else here. A status is not a thing to get through:
     * nobody is waiting for her to look, and ordering by "not yet seen" would turn a shelf of
     * photographs into a list of chores. Newest first is what she would expect of pictures.
     */
    fun statuses(statuses: List<Message>): List<Message> =
        statuses.sortedByDescending { it.timestamp }.take(LIST_LENGTH)

    /**
     * The one message worth putting a card at the top of the screen for, or none.
     *
     * Unread, from a person, newest. Returning null is a real answer and the common one: an empty
     * top of screen is quiet, and quiet is what she should get when nobody has written.
     *
     * Never a status. This card says "%s wrote a message", and about a picture posted to two
     * hundred contacts that is simply untrue — see [StatusBroadcast]. It was the loudest thing on
     * her screen saying something false, to the one person here least able to contradict it.
     */
    fun worthANudge(messages: List<Message>): Message? =
        messages.firstOrNull { it.isUnread && it.senderKind == SenderKind.PERSON && !it.isStatus }

    /**
     * Whether the Message button at the bottom of every screen should be marked.
     *
     * Deliberately the same question as [worthANudge], so that the mark and the card at the top of
     * home never disagree — and so that the mark is about somebody having written, not about the
     * flat list having something unopened in it. Marking on the second would make the mark
     * permanent: her phone receives messages from parking apps and pharmacies that she will never
     * open, and a mark that cannot be cleared is a mark that stops meaning anything.
     *
     * A yes or a no, never a number. A count is a demand — four things to get through — and it
     * would be the only place in this app that asks her to work through anything. This says the
     * one thing she needs to decide whether to press the button: somebody wrote.
     */
    fun marksTheMessageTab(messages: List<Message>): Boolean = worthANudge(messages) != null

    /**
     * The letters her home screen lists — unread first, and only a few.
     *
     * Unread first for the same reason the messages are: a letter she has not opened should not
     * be pushed under the fold by one she has. The incoming order is kept within each half.
     *
     * The home screen used to show a count here rather than letters — "3 new letters" — which
     * tells her there is something without telling her whether it is worth crossing the room for.
     */
    fun mails(mails: List<Mail>): List<Mail> {
        val (unread, read) = mails.partition { it.isUnread }
        return (unread + read).take(LIST_LENGTH)
    }

    /**
     * Whether a section has more than it is showing, and so needs to say where the rest is.
     *
     * A "more" that appears when there is nothing more is a door onto the same room. This is the
     * one question each section asks before drawing it.
     */
    fun hasMore(total: Int): Boolean = total > LIST_LENGTH

    /**
     * Which of her favourites a missed call was from, so the card can offer to ring them back.
     *
     * **By number, not by name.** Matching on the name was the same string compared from two
     * places that clean it differently: the address book holds «Laura "Lala Lulu" Hartmann»
     * and the telephone's log holds «Laura Hartmann», so they were never equal and the card never
     * offered to ring anybody back. In one real address book eight of fifteen favourites had a
     * nickname in quotes — which is to say the button was missing for exactly the people she
     * rings most, and silently, because a card with no button looks like a card that never had
     * one.
     *
     * A number does not have that problem. It is not spelled, not translated, and not shortened
     * for a small screen, and [CallHistory.sameNumber] already knows that the same telephone is
     * written `+41 79 …` in one place and `079 …` in the other.
     *
     * Null when nobody matches, which is an ordinary answer and not a failure: a call from the
     * surgery is a call she cannot return with one press, and the card then takes her to her list
     * of calls instead. Null too when the number is missing — a caller who withheld it, or a
     * missed call that reached her as a message rather than through the telephone's own log.
     */
    fun callBack(favourites: List<Contact>, nudge: Nudge.MissedCallNudge): Contact? {
        if (nudge.number.isBlank()) return null
        return favourites.firstOrNull {
            it.phoneNumber.isNotBlank() && CallHistory.sameNumber(it.phoneNumber, nudge.number)
        }
    }

    /**
     * Whether ringing [phoneNumber] is her answer to the card at the top of the screen.
     *
     * The card's own button was the only thing that could take a missed call away, so the way she
     * actually rings her daughter back — pressing her daughter's face, which is right there under
     * the card and is the button she has pressed every day for a year — left the card standing.
     * She would speak to her, hang up, and come home to a phone still saying the call had been
     * missed. That is the thing [MissedCallNotice] exists to stop, and which button she happened
     * to press is not something the phone should have an opinion about.
     *
     * By number, and by [CallHistory.sameNumber] specifically, for the same reason [callBack]
     * matches that way: the telephone's log and the address book never spell one telephone the
     * same. The number is asked of the call being placed rather than of the contact, so ringing
     * the same person from her list of calls, from the address book or from the card all count.
     *
     * False for anything that is not a missed call, and false when either number is missing —
     * a withheld caller must not be "answered" by the next call she happens to make.
     */
    fun answeredBy(nudge: Nudge?, phoneNumber: String?): Boolean {
        val missed = nudge as? Nudge.MissedCallNudge ?: return false
        if (missed.number.isBlank()) return false
        val ringing = phoneNumber?.trim().orEmpty()
        if (ringing.isBlank()) return false
        return CallHistory.sameNumber(ringing, missed.number)
    }
}
