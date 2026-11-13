package ch.heuscher.gentleconnect.telecom

import ch.heuscher.gentleconnect.model.PersonName

/**
 * What the telephone is doing, in the four states she can tell apart.
 *
 * Android's own call states run to a dozen and distinguish things like "selecting a SIM" and
 * "pulling the call from another device". None of those are a different situation for her, and a
 * screen that showed them would be a screen that says something new and unexplained at the worst
 * possible moment. Everything the platform reports collapses into one of these four.
 */
enum class CallStage {
    /** Somebody is calling and has not been answered yet. */
    RINGING,

    /** She called somebody, and it has not been picked up yet. */
    DIALLING,

    /** The two of them are talking. */
    TALKING,

    /** Finished, for any reason: hung up, rejected, out of credit, no signal. */
    OVER
}

/**
 * One call, as the screen needs it.
 *
 * Deliberately holds no reference to the platform's `Call` object. Everything that decides what
 * she sees is in here and can be checked without a telephone network, and the one place that
 * talks to Telecom keeps that to itself.
 */
data class PhoneCall(
    val number: String,
    val name: String?,
    val photoUri: String?,
    val stage: CallStage,
    val isIncoming: Boolean,
    /**
     * Whether the address book has finished being asked about this number.
     *
     * Not the same as having a name: a caller who is genuinely not in her contacts is identified
     * too, and the answer for a withheld number arrives without any query at all. What this says
     * is that the answer has settled and will not change under whoever is looking at it.
     *
     * The ringing screen needs it because a caller she knows gets different circles from one she
     * does not, and the lookup finishes a moment after the call arrives. Drawing first and
     * rearranging afterwards would move the circle she is already reaching for.
     */
    val identified: Boolean = false
) {
    /**
     * Whether the green circle belongs on the screen.
     *
     * Only an incoming call that is still ringing can be answered. An outgoing call that has not
     * connected yet also has a "not talking yet" feel to it, and showing a green circle there
     * would offer her a way to answer her own call.
     */
    val canAnswer: Boolean get() = isIncoming && stage == CallStage.RINGING
}

/**
 * Who to put on the screen, given what the network was willing to say.
 *
 * Three cases, and the difference between them matters to her more than it looks:
 * - a name from the address book — the ordinary case, and the only one that answers "should I
 *   pick this up?";
 * - no name but a number — she may still recognise it, and a caregiver certainly will;
 * - nothing at all, because the caller withheld it. This is *not* the same as a number she does
 *   not know, and it must not be shown as an empty face or a blank line: an unexplained silent
 *   screen with a green circle on it is the one thing most likely to get a scam call answered.
 *
 * @param unknownCaller the translated words for a withheld number, passed in rather than looked up
 *   here so that this stays a decision and not a screen.
 */
fun PhoneCall.whoToShow(unknownCaller: String): String {
    // Through [PersonName] like every other screen that prints who somebody is. Her address book
    // carries nicknames in quotes — `Tobias "Mü" Müller` — and this screen was the one place
    // showing them, so the same person was called one thing in her conversations and another when
    // he rang. It is also the longest form of every name, which is what pushed this one onto two
    // lines in the first place.
    val knownName = PersonName.forHer(name)
    if (!knownName.isNullOrEmpty()) return knownName
    val dialled = number.trim()
    if (dialled.isNotEmpty()) return dialled
    return unknownCaller
}
