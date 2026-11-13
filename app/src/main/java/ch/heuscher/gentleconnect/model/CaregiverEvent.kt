package ch.heuscher.gentleconnect.model

/**
 * Something that did not work, written down for whoever looks after her.
 *
 * Her screen carries no error copy at all — something she cannot do is simply not offered, with no
 * explanation, because an explanation lands on the one person who did not cause the fault and
 * cannot fix it. That decision only holds if the reason reaches somebody else, and until now it
 * reached nobody: it went to `logcat`, which no daughter, no neighbour and no carer is ever going
 * to attach a laptop to read.
 *
 * These are the events that log is made of.
 */
enum class CaregiverEventKind {
    /** A message she wrote did not leave the phone. The one she can also see, as an unfinished message. */
    MESSAGE_NOT_SENT,

    /** A message to several people reached some of them. See [CaregiverEvent.count] of [CaregiverEvent.total]. */
    GROUP_MESSAGE_PART_SENT,

    /** Several people cannot be answered because nobody has told us who they are. */
    GROUP_MEMBERSHIP_UNKNOWN,

    /** Several people cannot be answered because she is no longer among them. */
    GROUP_NO_LONGER_MEMBER,

    /** Several people cannot be answered because there is nobody in it but her. */
    GROUP_NOBODY_LEFT,

    /** The messenger connection failed. Not a disconnection: those are ordinary and constant. */
    MESSENGER_CONNECTION_FAILED,

    /** Asking which of her contacts can be reached failed, so no new people became reachable. */
    CONTACT_SYNC_FAILED,

    /** Contacts whose telephone number could not be read, so they were never looked up at all. */
    CONTACTS_NOT_UNDERSTOOD,

    /**
     * A call from somebody not in her address book was ended, because a caregiver asked for that.
     *
     * Logged so the switch can never be silent. Without this line, a surgery ringing back from an
     * unsaved number produces nothing anywhere except "we tried to call her and nobody answered",
     * and the person best placed to work out why is the person who turned the switch on and has
     * long since forgotten.
     *
     * Carries no number and no name: this log is published to the caregiver website, and who rang
     * her is not something to put there. The number is in the telephone's own list of calls, on
     * the phone, at the same minute.
     */
    CALL_FROM_STRANGER_ENDED,

    /**
     * An incoming MMS picture message arrived and was dropped because this app does not support MMS.
     *
     * Logged and alerted so a caregiver knows why a family member's cellular photo was not received,
     * allowing them to advise the sender to use WhatsApp, Threema, or email instead.
     */
    MMS_DROPPED_UNSUPPORTED,

    /**
     * Inbound messenger payloads arrived from the bridge but were dropped because internal buffers were full.
     *
     * Something arrived for her and the app could not hold on to it, so it is not in her conversation
     * and never will be.
     */
    MESSAGES_LOST_ON_ARRIVAL,

    /**
     * An incoming text message (SMS) could not be written to the telephony database.
     *
     * Because this app holds the SMS role, Android does not store incoming texts itself; what fails
     * to store here is lost permanently and exists nowhere else on the phone.
     */
    SMS_NOT_STORED,

    /**
     * A WhatsApp call arrived and could not be answered.
     *
     * Not a fault of this app's and not something anybody can fix on the phone: WhatsApp's
     * companion protocol delivers the ringing to a linked device but carries no audio, so a call
     * that arrives here can only be heard about — see
     * [ch.heuscher.gentleconnect.model.CallCourtesy]. It is logged because the thing to do about
     * it is social rather than technical: whoever reads this log is the person who can tell the
     * family to ring her number, and a count of these is the evidence for saying so.
     *
     * Carries no name. The log is published to the caregiver website, and she is told who rang on
     * the phone itself, at the same minute.
     */
    WHATSAPP_CALL_UNANSWERED,

    /**
     * A message was deleted from local storage on this phone by a caregiver in Advanced Mode.
     *
     * Logged so that a missing message is never a mystery to another caregiver reviewing the phone.
     * Carries only the anonymised counterpart name; the message content is not stored in the log.
     */
    MESSAGE_DELETED_ON_PHONE,

    /**
     * An entire conversation was deleted from local storage on this phone by a caregiver.
     *
     * Logged for auditability across multiple caregivers. Carries the anonymised contact or group
     * name as seen on her screen.
     */
    CONVERSATION_DELETED_ON_PHONE,

    /**
     * A telephone number was added to the system blocked list by a caregiver in Advanced Mode.
     *
     * Logged without the number itself because this log is published to the caregiver website.
     */
    NUMBER_BLOCKED,

    /**
     * A conversation was muted on this phone by a caregiver in Advanced Mode.
     *
     * Logged so a caregiver wondering why arrival chimes stopped for a contact can see that
     * muting was explicitly turned on.
     */
    CONVERSATION_MUTED,

    /**
     * A call could not be placed while roaming because the person has no Threema address.
     */
    CALL_FAILED_NOT_FROM_ABROAD,

    /**
     * A call could not be placed because there is no cellular voice service and Threema is not connected.
     */
    CALL_FAILED_NO_CONNECTION,

    /**
     * An incoming Threema call from an unknown identity was rejected silently.
     */
    THREEMA_CALL_FROM_STRANGER_ENDED,

    /**
     * The support dialog was opened, but neither confirmed nor dismissed before timing out.
     */
    SUPPORT_OPENED_NOT_CONFIRMED,

    /**
     * Signs of acute restlessness or agitation were detected (burst tapping, repeated redialing,
     * unconfirmed support loops, or nocturnal activity).
     */
    RESTLESSNESS_DETECTED,

    /**
     * Her Gmail can no longer be read: the read-only grant was withdrawn, or the account left the
     * phone. Nothing new reaches her Post screen until somebody grants it again on the phone.
     *
     * Only a person at the phone can fix this — the consent screen cannot be shown from the
     * background — and without this line her post would simply stop, with nothing anywhere to say so.
     */
    MAIL_ACCESS_LOST
}


/**
 * One line of the caregiver's log.
 *
 * [subject] is a person or a group **as she sees them named on her screen**, and never an identity
 * — see [ch.heuscher.gentleconnect.data.CaregiverLog.anonymise], which is what guarantees it.
 * [occurrences] is how many times this same thing happened in a row, so that a messenger failing
 * every thirty seconds stays one line instead of burying this morning's real fault.
 */
data class CaregiverEvent(
    val kind: CaregiverEventKind,
    val at: Long,
    val subject: String? = null,
    /** How many of [total] were reached, for the events that count something. */
    val count: Int = 0,
    val total: Int = 0,
    val occurrences: Int = 1
)
