package ch.heuscher.gentleconnect.model

/**
 * How long the phone has been out of touch.
 *
 * The one fault the phone cannot report is its own absence. Everything in [CaregiverEventKind]
 * is something that went wrong *while the app was running* — but the failures that matter most
 * to somebody who is not in the room are the ones where nothing runs at all: the phone is off,
 * the flat has no internet, the launcher was killed and never came back, or a messenger session
 * expired and took the whole connection with it. None of those can write a log entry.
 *
 * So the phone says "still here" on a timer, and silence is read as the fault. This is the
 * inverse of every other event: it is inferred by the reader from an absence, not sent.
 */
enum class PhoneSilence {
    /** No heartbeat has ever arrived. A phone that was claimed and then never started again. */
    NEVER_HEARD,

    /** Heard from recently enough that nothing is wrong. */
    HEARD,

    /** Nothing for [CaregiverAlerts.SILENCE_THRESHOLD_MS]. Somebody should look. */
    QUIET
}

/**
 * Which faults follow the caregiver out of the house.
 *
 * The caregiver log already exists and is already the right log — it is written on the phone,
 * anonymised on the way in, and read on the phone behind the five-tap gesture. Its limit is that
 * you have to be holding the phone. Everything here is about the subset that has to travel: what
 * gets published to the caregiver website, and therefore what is allowed to interrupt somebody
 * who is somewhere else.
 *
 * Two rules govern the choice, and they pull in opposite directions. A log nobody reads is worth
 * nothing, and an alert that fires for things nobody can act on trains its reader to ignore it —
 * at which point the one that mattered arrives and is dismissed with the rest. So the bar is not
 * "did something go wrong" but "would somebody who cares about her want to know today".
 *
 * Pure, and therefore the only part of this feature CI can test.
 */
object CaregiverAlerts {

    /**
     * How often the phone says it is still here.
     *
     * Fifteen minutes is frequent enough that [SILENCE_THRESHOLD_MS] can be a number of hours
     * rather than a number of days, and rare enough to be invisible: ~96 writes a day against a
     * free-tier allowance of 20,000, and a document write on a connection that is already open.
     *
     * Fifteen minutes of the world's time. That has only been true since the beat became an alarm
     * — see [ch.heuscher.gentleconnect.data.HeartbeatAlarm] — and the arithmetic above quietly
     * depended on it: while it was a coroutine `delay` this was closer to two hours, and ~12
     * writes a day.
     */
    const val HEARTBEAT_INTERVAL_MS = 15L * 60 * 1000

    /**
     * How long silence has to last before it means something.
     *
     * Deliberately far longer than [HEARTBEAT_INTERVAL_MS], because a missed heartbeat is not a
     * fault. A phone charging overnight on a shelf sits in Doze; the flat's router reboots; she
     * carries it out of coverage. Twelve hours is chosen so that the ordinary quiet part of a
     * night can pass in full without anybody's phone buzzing, and so that a caregiver reading
     * this in the morning is reading about a real absence rather than about Android's power
     * management. A false alarm here is expensive in a way a late alarm is not: it is the alarm
     * that teaches people to stop looking.
     */
    const val SILENCE_THRESHOLD_MS = 12L * 60 * 60 * 1000

    /**
     * How long a fault stays on the website after it happens.
     *
     * The log on the phone keeps [ch.heuscher.gentleconnect.data.CaregiverLog.MAX_ENTRIES] and
     * keeps them for as long as they last, because somebody visiting after a month should be able
     * to read backwards. This list is the opposite: it exists to say *what is wrong now*, and a
     * fortnight-old connection failure that fixed itself is not that.
     */
    const val RECENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How many faults travel.
     *
     * A short list, because this is a summary and not the log. If more than ten notifiable things
     * happened in a week the top ten say the same thing the full list would.
     */
    const val MAX_ALERTS = 10

    /**
     * How long before the same fault is allowed to interrupt somebody again.
     *
     * A messenger that cannot connect retries on a timer, and the log's own collapsing turns that
     * into one line that keeps being re-stamped — correct for a log, and a machine gun for a
     * notification. The website holds one of these per [signature], so a connection failing all
     * afternoon reaches its reader once.
     *
     * Mirrored in `caregiver-portal/app.js`, which is where it is applied: the decision is the
     * phone's to state and the browser's to enforce, because only the browser knows what it has
     * already shown to whom.
     */
    const val NOTIFY_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /**
     * Whether this kind of fault is worth telling somebody who is not in the room.
     *
     * A `when` with no `else`, so a new kind cannot be added without somebody deciding this. The
     * three that travel are the three where something either failed for her or stopped working
     * altogether; the rest are true, are worth reading on the phone, and are not worth a
     * notification:
     *
     *  - the three group cases describe a group she cannot answer, and no caregiver action fixes
     *    any of them — membership arrives on its own when somebody next writes to the group, and
     *    the other two are simply facts about a group she is no longer really in;
     *  - a failed contact sync takes nothing away: everybody who was reachable still is, and the
     *    worst case is that somebody new stays unreachable until the next sweep;
     *  - unreadable telephone numbers are a property of her address book that has been true since
     *    long before this app, and will be equally true tomorrow.
     */
    fun worthNotifying(kind: CaregiverEventKind): Boolean = when (kind) {
        // She wrote to somebody and it did not go. She can see this one too, as a message left
        // visibly unfinished, and this is what lets a daughter ring up and say why.
        CaregiverEventKind.MESSAGE_NOT_SENT -> true

        // Some of a group got it. She is told it did not send, which is the safe lie; the true
        // version — that four of six have it — only exists here.
        CaregiverEventKind.GROUP_MESSAGE_PART_SENT -> true

        // Nothing can arrive and nothing can leave. The single most important line in this file.
        CaregiverEventKind.MESSENGER_CONNECTION_FAILED -> true

        // Messages arrived from the bridge while internal buffers were full and were dropped.
        // Her family wrote to her and she will never see it.
        CaregiverEventKind.MESSAGES_LOST_ON_ARRIVAL -> true

        // An incoming text message could not be written to the database. Because this app holds
        // the SMS role, Android keeps no copy itself; bank codes or family texts are gone permanently.
        CaregiverEventKind.SMS_NOT_STORED -> true

        CaregiverEventKind.GROUP_MEMBERSHIP_UNKNOWN -> false
        CaregiverEventKind.GROUP_NO_LONGER_MEMBER -> false
        CaregiverEventKind.GROUP_NOBODY_LEFT -> false
        CaregiverEventKind.CONTACT_SYNC_FAILED -> false
        CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD -> false

        // A stranger's call ended. Nothing failed — this is the switch doing exactly what somebody
        // asked it to do, and on a phone that gets three cold calls a day it would fire three
        // times a day and drown the three lines above that mean something is broken. It belongs in
        // the log, where somebody wondering why a call never arrived will go looking for it.
        CaregiverEventKind.CALL_FROM_STRANGER_ENDED -> false

        // An incoming MMS picture message arrived and was dropped because this app does not support MMS.
        // Alerting caregivers lets them know why a photo was not received and advise family members.
        CaregiverEventKind.MMS_DROPPED_UNSUPPORTED -> true

        // A WhatsApp call nobody could answer. It belongs in the log and not in somebody's
        // pocket: the caller has already been told to use her number, she has already been shown
        // the missed call, and a push for every one of these would be a push every time a
        // relative forgets — which is the habit this is trying to change, not an emergency.
        CaregiverEventKind.WHATSAPP_CALL_UNANSWERED -> false

        // Deliberate actions taken by a caregiver in Advanced Mode. Nothing failed — these are
        // intentional management actions, so they are preserved in the log for auditability but
        // do not raise external caregiver push alerts.
        CaregiverEventKind.MESSAGE_DELETED_ON_PHONE -> false
        CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE -> false
        CaregiverEventKind.NUMBER_BLOCKED -> false
        CaregiverEventKind.CONVERSATION_MUTED -> false
        CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD -> false
        CaregiverEventKind.CALL_FAILED_NO_CONNECTION -> false
        CaregiverEventKind.THREEMA_CALL_FROM_STRANGER_ENDED -> false
        CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED -> false
        CaregiverEventKind.RESTLESSNESS_DETECTED -> false

        // Her post has stopped, and only somebody holding the phone can start it again. Retried
        // every sync, so it collapses into one line; the website's cooldown keeps it to one push.
        CaregiverEventKind.MAIL_ACCESS_LOST -> true
    }


    /**
     * The faults that travel, newest first.
     *
     * Takes the log as it stands and narrows it: notifiable kinds only, nothing older than
     * [RECENT_WINDOW_MS], at most [MAX_ALERTS]. The input is already newest-first and already
     * collapsed and already anonymised — this adds no rules of its own beyond those three,
     * because every rule applied twice is a rule that can disagree with itself.
     *
     * An event stamped in the future is kept rather than dropped. Clocks on a phone nobody
     * administers do move, and a fault is not less real for having a bad timestamp.
     */
    fun toAlerts(events: List<CaregiverEvent>, now: Long): List<CaregiverEvent> =
        events.asSequence()
            .filter { worthNotifying(it.kind) }
            .filter { now - it.at <= RECENT_WINDOW_MS }
            .take(MAX_ALERTS)
            .toList()

    /**
     * What makes two alerts "the same fault" for the purpose of not saying it twice.
     *
     * Kind and subject, matching [ch.heuscher.gentleconnect.data.CaregiverLog.collapses] — the
     * counts and the time are left out on purpose, so that a connection failing at 14:02 and
     * again at 14:03 is one thing that is going wrong and not two.
     *
     * Lower-cased for the same reason the log compares case-insensitively: the same person can
     * reach the subject line spelled two ways depending on which path named them.
     */
    fun signature(event: CaregiverEvent): String =
        "${event.kind.name}|${event.subject?.lowercase().orEmpty()}"

    /**
     * Whether the published list needs rewriting.
     *
     * The log emits on every change, including the ones this filters out, and every write here
     * costs a Firestore write and wakes every caregiver's browser. Compared by content rather
     * than by identity, and including [CaregiverEvent.at] and [CaregiverEvent.occurrences]
     * deliberately: a fault happening *again* is news even when the line is the same, because it
     * is the difference between "this broke this morning" and "this is still broken".
     */
    fun changed(previous: List<CaregiverEvent>?, next: List<CaregiverEvent>): Boolean =
        previous != next

    /**
     * How to read the last heartbeat.
     *
     * [lastSeenAt] is null when no heartbeat has ever been recorded — a device document that was
     * created and claimed by a phone that has not run since.
     *
     * A heartbeat from the future reads as [PhoneSilence.HEARD]. It is the safe direction: the
     * alternative is a phone with a fast clock reporting itself missing while it is working.
     */
    fun silence(lastSeenAt: Long?, now: Long): PhoneSilence = when {
        lastSeenAt == null -> PhoneSilence.NEVER_HEARD
        now - lastSeenAt >= SILENCE_THRESHOLD_MS -> PhoneSilence.QUIET
        else -> PhoneSilence.HEARD
    }
}
