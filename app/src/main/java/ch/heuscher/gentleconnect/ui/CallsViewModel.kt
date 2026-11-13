package ch.heuscher.gentleconnect.ui

import android.content.Context
import android.os.Build
import android.provider.BlockedNumberContract
import androidx.lifecycle.ViewModel
import ch.heuscher.gentleconnect.data.CallLogRepository
import ch.heuscher.gentleconnect.model.CallRecord
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Her list of calls, and blocking a number from it.
 *
 * Placing a call stays with the home screen's view model: ringing somebody back is what takes the
 * missed-call card off the home screen, and the card is that view model's. This is the list.
 */
class CallsViewModel(
    /** Null where there is no call log to read — in tests. The list then stays empty. */
    private val callLogRepository: CallLogRepository?,
    /** Writes a fault down for the caregiver. Never waited for. */
    private val recordFault: (CaregiverEvent) -> Unit
) : ViewModel() {

    /**
     * The telephone's record of calls, for the screen that shows it.
     *
     * Pulled rather than kept open. No cursor is held on the provider between reads — a launcher
     * that keeps one for days is a launcher holding a lock on something the dialer wants. It is
     * read when she opens the screen, which is the only moment anybody is looking, and again
     * whenever the telephone writes to the log while she is still there. See [watchRecentCalls].
     */
    private val _recentCalls = MutableStateFlow<List<CallRecord>>(emptyList())
    val recentCalls: StateFlow<List<CallRecord>> = _recentCalls.asStateFlow()

    /** Whether the telephone lets this app read that record at all. */
    private val _canReadCalls = MutableStateFlow(false)
    val canReadCalls: StateFlow<Boolean> = _canReadCalls.asStateFlow()

    /**
     * Read the record of calls, and keep reading it for as long as the caller is interested.
     *
     * Suspends until whoever called gives up — the screen leaving, in practice, which cancels
     * this and takes the observer down with it.
     *
     * It has to be a watch and not a single read, because of *when* the row for a call is
     * written: at the end of the call, by the telephone, and by then she has already come back
     * from it to the list she started it from. One read on the way in would have shown her a
     * record of calls with the call she had just made missing from it — and nothing she could
     * do on that screen would have brought it in, because the screen was never opened again.
     *
     * Read one after another rather than each in its own coroutine, so two reads racing cannot
     * leave the older answer on her screen.
     */
    suspend fun watchRecentCalls() {
        val repo = callLogRepository ?: return
        _canReadCalls.value = repo.hasReadCallLogPermission()
        _recentCalls.value = repo.recentCalls()
        repo.changes().collect {
            _recentCalls.value = repo.recentCalls()
        }
    }

    /**
     * Adds [number] to the system blocked list using [BlockedNumberContract].
     * Logs [CaregiverEventKind.NUMBER_BLOCKED].
     */
    fun blockNumber(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        val digitsOnly = number.filter { it.isDigit() || it == '+' }
        if (digitsOnly.isBlank()) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                BlockedNumberContract.canCurrentUserBlockNumbers(context)
            ) {
                val values = android.content.ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, digitsOnly)
                }
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
                recordFault(CaregiverEvent(kind = CaregiverEventKind.NUMBER_BLOCKED, at = System.currentTimeMillis()))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
