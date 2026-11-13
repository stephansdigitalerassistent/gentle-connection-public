package ch.heuscher.gentleconnect.model

/**
 * What to do about a WhatsApp call that nobody here can answer.
 *
 * WhatsApp calls reach this phone twice over. They ring in the WhatsApp app itself, which is
 * installed because it has to be — it is the primary device this app's companion session hangs
 * off — and its ringing screen is one she has never been taught and cannot work. And they arrive
 * here as signalling, through the bridge, which can hear a call and cannot carry one: the audio
 * runs over Meta's own relay protocol and no client outside their own implements it.
 *
 * So the call fails. The only question is whether it fails silently. Left alone, a daughter
 * ringing on WhatsApp gets ringing and then nothing, concludes her mother is out, and tries again
 * tomorrow; her mother never learns that anybody called. Two things are done about that, and
 * neither of them is an attempt to answer:
 *
 *  - **She is told.** The call becomes a missed call on her home screen, with the green button
 *    that rings that person back on the telephone.
 *  - **The caller is told**, once, if her address book knows who they are — see
 *    [ch.heuscher.gentleconnect.data.ArrivalPipeline]. That notice is what turns a call that
 *    failed into a call that comes back on the number, and it is the whole reason any of this
 *    is worth doing.
 *
 * Pure, and tested, because both of those go out on their own with nobody watching.
 */
object CallCourtesy {

    /**
     * How long one person is left alone after being told.
     *
     * A caller who is not getting through calls again, and again, and then tries a video call:
     * three notices in four minutes is a phone shouting at her daughter. Long enough to cover
     * one evening of trying, short enough that somebody who falls back into the habit next week
     * is reminded rather than met with silence.
     */
    const val QUIET_MS = 6 * 60 * 60 * 1000L

    /**
     * How long a call can ring before it stops being a missed call.
     *
     * The backstop for the case the reasons below do not cover. WhatsApp stops ringing well
     * inside a minute; anything that was up for longer than this was a conversation somebody
     * had — she does pick up occasionally, by accident or because somebody was with her — and
     * writing "calls do not arrive here" to a person who has just spent ten minutes talking to
     * her is the one failure of this feature that would be actively confusing.
     */
    const val LONGEST_RING_MS = 2 * 60 * 1000L

    /**
     * The words WhatsApp uses for a call that ended because it was taken, rather than missed.
     *
     * Deliberately a list of what is *known* to mean answered, with everything else — including
     * a word this build has never seen — counting as missed. The other way round would make the
     * feature disappear silently the day WhatsApp renames a reason, and a notice that quietly
     * stops being sent is indistinguishable from one that was never built.
     *
     * The reasons themselves are not documented anywhere outside Meta's own clients. The bridge
     * logs every one it sees (`[wa] call ended reason=…`), which is how this list gets shorter or
     * longer.
     */
    val TAKEN_REASONS = setOf("accept", "accepted", "connected", "answered", "accept_elsewhere")

    /**
     * The reason the bridge gives for a call it turned away itself, when a caregiver has asked
     * for WhatsApp calls not to ring — see
     * [ch.heuscher.gentleconnect.model.RemoteSettings.rejectWhatsAppCalls]. Not a word WhatsApp
     * uses and not in [TAKEN_REASONS]: a call turned away is a call nobody took, and it is told
     * about exactly like one that rang out.
     */
    const val REJECTED_HERE = "rejected_by_phone"

    /**
     * Whether a call that has ended went unanswered.
     *
     * [answeredElsewhere] is WhatsApp itself saying the call was picked up on one of her devices,
     * which is the reliable half of this; [reason] and [rangForMs] are the two guesses that cover
     * the case where that never arrives.
     */
    fun missed(reason: String, answeredElsewhere: Boolean, rangForMs: Long): Boolean {
        if (answeredElsewhere) return false
        if (reason.trim().lowercase() in TAKEN_REASONS) return false
        return rangForMs <= LONGEST_RING_MS
    }

    /**
     * Whether to write to whoever rang.
     *
     * Three noes, each of them deliberate:
     *
     *  - **Somebody her address book does not know.** A reply tells a stranger that this number
     *    is live and answered by somebody, which is exactly what the people who dial numbers all
     *    day are paying to find out. It also cannot be acted on: a person she has no relationship
     *    with has no reason to be given her telephone number back.
     *  - **A call to several people at once.** The notice is addressed to a person; sent to a
     *    group it is an announcement to eight people that one of them cannot take calls.
     *  - **Somebody already told**, inside [QUIET_MS].
     *
     * [toldLastAt] is zero for somebody never written to, which is the first call anybody makes.
     */
    fun worthTelling(knownCaller: Boolean, group: Boolean, toldLastAt: Long, now: Long): Boolean {
        if (!knownCaller || group) return false
        return now - toldLastAt > QUIET_MS
    }
}
