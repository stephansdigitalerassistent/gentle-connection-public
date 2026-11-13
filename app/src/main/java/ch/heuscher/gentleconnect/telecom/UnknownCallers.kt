package ch.heuscher.gentleconnect.telecom

import android.content.Context

/**
 * Ending a call from somebody she does not know, when a caregiver has asked for that.
 *
 * Off unless deliberately switched on, on the caregiver website — see
 * [ch.heuscher.gentleconnect.model.RemoteSettings.blockUnknownCallers] for why it is a judgement
 * about one person's life rather than a sensible default. What it stops is the caller who talks
 * her through moving money. What it also stops is the surgery ringing back from a line nobody
 * saved.
 *
 * **The call is ended, not silenced.** A silenced call still lands in her list of calls as a
 * missed one, with a number beside it and a green telephone next to that, and the whole point is
 * that she is never in a position to ring it back.
 *
 * Note what this does *not* do: it does not touch the ringtone. The phone still rings for
 * everybody else exactly as Android decides — see [GentlePhoneCallService], which deliberately
 * leaves ringing to the platform. Ending one call is a single, visible act; taking over the
 * ringer would put every call at the mercy of this app's audio code.
 */
object UnknownCallers {

    /**
     * Whether this call should be ended before the phone is allowed to ring.
     *
     * Pure, and separated from everything that reads a preference or a content provider, because
     * it is the decision that hangs up on people. The three cases:
     *
     * - not switched on — never end anything, whoever is calling;
     * - a withheld or empty number — ended. There is nobody to recognise, and a caller who
     *   deliberately hides their number is exactly who the switch was turned on for;
     * - a number the address book does not know — ended.
     *
     * @param inAddressBook whether the number belongs to somebody she has saved. Passed in rather
     *   than looked up here so the decision can be tested without a telephone or a contacts
     *   database.
     */
    fun shouldEnd(blocking: Boolean, number: String?, inAddressBook: Boolean): Boolean {
        if (!blocking) return false
        if (number.isNullOrBlank()) return true
        return !inAddressBook
    }

    /**
     * The switch, as the telephone can read it the instant a call arrives.
     *
     * Mirrored into preferences by [ch.heuscher.gentleconnect.data.FirestoreSettingsRepository]
     * whenever the website's settings arrive, rather than read from Firestore here. A ringing
     * telephone cannot wait for a network round trip, and the process answering the call may have
     * been started by the call itself, with no settings loaded and possibly no signal — a state in
     * which asking the network would mean either a long pause or a wrong answer.
     *
     * Absent reads as off, which is the safe direction: a phone that has never heard from the
     * website rings for everybody, as it always did.
     */
    fun isOn(context: Context): Boolean = try {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SWITCH, false)
    } catch (e: Exception) {
        false
    }

    /**
     * Whether blocked calls should be listed in her recent calls.
     */
    fun showsInCallLog(context: Context): Boolean = try {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SWITCH_CALL_LOG, false)
    } catch (e: Exception) {
        false
    }

    /**
     * Whether a notification should be shown when an unknown caller is blocked.
     */
    fun notifies(context: Context): Boolean = try {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SWITCH_NOTIFY, false)
    } catch (e: Exception) {
        false
    }

    /**
     * Whether a call record of [callType] should be included in her recent calls.
     *
     * Pure and testable without Android Context or ContentProvider. All rejected calls
     * ([REJECTED_TYPE]) are shown in the call history; platform-blocked calls ([BLOCKED_TYPE])
     * are included when [showBlockedInCallLog] is enabled.
     */
    fun shouldIncludeInCallLog(callType: Int, showBlockedInCallLog: Boolean): Boolean {
        if (callType == BLOCKED_TYPE) {
            return showBlockedInCallLog
        }
        return true
    }

    /** Remember what the website said, for the next call — which may be before the next sync. */
    fun remember(
        context: Context,
        blocking: Boolean,
        showInCallLog: Boolean = false,
        notify: Boolean = false
    ) {
        try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SWITCH, blocking)
                .putBoolean(SWITCH_CALL_LOG, showInCallLog)
                .putBoolean(SWITCH_NOTIFY, notify)
                .apply()
        } catch (e: Exception) {
            // The phone goes on ringing for everybody. Failing safe is the whole rule here.
        }
    }

    const val REJECTED_TYPE = 5
    const val BLOCKED_TYPE = 6

    /** The same file the rest of the app's device-level preferences live in. */
    private const val PREFERENCES = "gentle_connection_prefs"
    private const val SWITCH = "blockUnknownCallers"
    private const val SWITCH_CALL_LOG = "showBlockedCallsInCallLog"
    private const val SWITCH_NOTIFY = "notifyBlockedCalls"
}
