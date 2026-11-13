package ch.heuscher.gentleconnect.data

import android.content.Context
import android.content.SharedPreferences
import ch.heuscher.gentleconnect.model.MissedCallNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone must remember across launcher restarts about what it has already put in front
 * of her: how far back missed calls have been dealt with, and the newest arrival already
 * announced out loud.
 *
 * On disk rather than in memory, because the launcher is restarted often — by Android reclaiming
 * memory, by a crash, by an update — and a card she has already answered or a message she has
 * already heard must not be repeated after a restart.
 *
 * Kept strictly to state that has no other home on disk: messages carry their own read flag in
 * the database, but missed-call acknowledgement and the arrival announcement watermark belong
 * to this device's interaction history.
 *
 * Observable, and kept in step with the file rather than with whoever wrote it. A missed call is
 * dealt with in three places — the card on the home screen, the "Call back" in the notification,
 * and the telephone noticing she rang them back — and the home screen used to hold its own copy
 * that only learnt about the other two when it was asked to read the file again. Every instance
 * now follows the file, so the one [ch.heuscher.gentleconnect.data.AppGraph] hands out and any
 * other one agree. Both values only ever move forward.
 */
class NudgeMemory(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _missedCallsDealtWithUpTo = MutableStateFlow(prefs.getLong(KEY_MISSED_CALLS, 0L))

    /** The moment up to which missed calls have been answered, or 0 if none ever have. */
    val missedCallsDealtWithUpTo: StateFlow<Long> = _missedCallsDealtWithUpTo.asStateFlow()

    private val _announcedUpTo = MutableStateFlow(prefs.getLong(KEY_ANNOUNCED_UP_TO, 0L))

    /** The receipt timestamp of the newest arrival already announced, or 0 if none ever has been. */
    val announcedUpTo: StateFlow<Long> = _announcedUpTo.asStateFlow()

    /**
     * Held in a field because [SharedPreferences] keeps its listeners weakly: registered as a
     * lambda and nothing else, it would be collected and the memory would stop following the file.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        when (key) {
            KEY_MISSED_CALLS -> _missedCallsDealtWithUpTo.value =
                MissedCallNotice.dealtWith(changed.getLong(KEY_MISSED_CALLS, 0L), _missedCallsDealtWithUpTo.value)
            KEY_ANNOUNCED_UP_TO -> _announcedUpTo.value =
                maxOf(changed.getLong(KEY_ANNOUNCED_UP_TO, 0L), _announcedUpTo.value)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * She has dealt with the missed call at [callAt], however she did it. Never moves backwards —
     * see [MissedCallNotice.dealtWith] — so an older call cannot put a newer card back up.
     */
    fun dealtWithMissedCall(callAt: Long) {
        val moment = MissedCallNotice.dealtWith(callAt, _missedCallsDealtWithUpTo.value)
        _missedCallsDealtWithUpTo.value = moment
        prefs.edit().putLong(KEY_MISSED_CALLS, moment).apply()
    }

    /** Remembers that everything that arrived up to [moment] has been announced. */
    fun rememberAnnouncedUpTo(moment: Long) {
        val newest = maxOf(moment, _announcedUpTo.value)
        _announcedUpTo.value = newest
        prefs.edit().putLong(KEY_ANNOUNCED_UP_TO, newest).apply()
    }

    private companion object {
        const val PREFS_NAME = "nudge_memory"
        const val KEY_MISSED_CALLS = "missed_calls_dealt_with_up_to"
        const val KEY_ANNOUNCED_UP_TO = "announced_up_to"
    }
}
