package ch.heuscher.gentleconnect.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-local persistence of conversations a caregiver has muted on this phone.
 *
 * ## Why muting is app-local
 *
 * Muting does not leave a group, block a sender on the network, or hide messages: a muted
 * conversation still arrives in the background and is still fully visible when she opens her
 * messages. What muting removes is the arrival chime and screen wake for that specific
 * conversation (see [ch.heuscher.gentleconnect.service.Attention] and
 * [ch.heuscher.gentleconnect.data.ArrivalPipeline]).
 *
 * It is persisted in [SharedPreferences] rather than on Firestore because it is a caregiver's
 * local decision for this hardware unit, matching the quiet-hours local enforcement.
 */
class MutedConversationsRepository(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _muted = MutableStateFlow<Set<String>>(readMuted())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    /**
     * Follows the file, not only this instance's own writes. The home screen mutes and the arrival
     * pipeline asks, and when each held its own copy the pipeline went on announcing a muted
     * conversation until the process restarted. Held in a field because [SharedPreferences] keeps
     * its listeners weakly.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_MUTED_IDS) _muted.value = readMuted()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun isMuted(conversationId: String): Boolean = conversationId in _muted.value

    fun mute(conversationId: String) {
        if (conversationId.isBlank()) return
        val updated = _muted.value + conversationId
        _muted.value = updated
        persist(updated)
    }

    fun unmute(conversationId: String) {
        if (conversationId.isBlank()) return
        val updated = _muted.value - conversationId
        _muted.value = updated
        persist(updated)
    }

    /**
     * Toggles muting for [conversationId]. Returns true if the conversation is now muted.
     */
    fun toggleMute(conversationId: String): Boolean {
        return if (isMuted(conversationId)) {
            unmute(conversationId)
            false
        } else {
            mute(conversationId)
            true
        }
    }

    private fun readMuted(): Set<String> = try {
        prefs.getStringSet(KEY_MUTED_IDS, emptySet())?.toSet().orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    private fun persist(ids: Set<String>) {
        try {
            prefs.edit().putStringSet(KEY_MUTED_IDS, HashSet(ids)).apply()
        } catch (e: Exception) {
            // Nothing to do about it; in-memory state stands.
        }
    }

    companion object {
        private const val PREFS_NAME = "gentle_connection_muted_conversations"
        private const val KEY_MUTED_IDS = "muted_conversation_ids"
    }
}
