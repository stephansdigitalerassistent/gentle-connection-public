package ch.heuscher.gentleconnect.ui

import androidx.lifecycle.ViewModel
import ch.heuscher.gentleconnect.data.support.SupportManager
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.model.SupportMember
import ch.heuscher.gentleconnect.model.SupportRequest
import ch.heuscher.gentleconnect.model.TouchSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The support button: whether it is there, what it is doing, who answers it — and the
 * restlessness that can bring it forward on its own.
 *
 * The request itself belongs to [SupportManager], which outlives any screen; this only shows its
 * state and passes on her presses. Restlessness is the shared [Restlessness], because the signs
 * of it come from more than this screen.
 */
class SupportViewModel(
    /** Null in tests and anywhere without a context: the button is then simply never there. */
    private val supportManager: SupportManager?,
    private val restlessness: Restlessness
) : ViewModel() {

    val supportButtonState: StateFlow<SupportButtonState> =
        supportManager?.buttonState ?: MutableStateFlow(SupportButtonState.None)

    val currentSupportRequest: StateFlow<SupportRequest?> =
        supportManager?.currentRequest ?: MutableStateFlow(null)

    val supportMembers: StateFlow<List<SupportMember>> =
        supportManager?.members ?: MutableStateFlow(emptyList())

    fun triggerSupportRequest(screenName: String, isReAlert: Boolean = false) {
        supportManager?.triggerSupportRequest(screenName, isReAlert)
    }

    /** She opened the support button and did not confirm. Counted as a sign of restlessness too. */
    fun logSupportOpenedNotConfirmed() {
        supportManager?.logSupportOpenedNotConfirmed()
        restlessness.recordSupportOpenedNotConfirmed()
    }

    val isRestless: StateFlow<Boolean> get() = restlessness.isRestless
    val restlessSnapshotRequests: SharedFlow<AgitationTrigger> get() = restlessness.restlessSnapshotRequests

    fun recordTouch(sample: TouchSample) = restlessness.recordTouch(sample)
    fun recordScreenChange(screen: String, now: Long = System.currentTimeMillis()) = restlessness.recordScreenChange(screen, now)
    fun recordBackWithoutEffect(now: Long = System.currentTimeMillis()) = restlessness.recordBackWithoutEffect(now)
    fun recordScreenOn(now: Long = System.currentTimeMillis()) = restlessness.recordScreenOn(now)
    fun recordHinge(closed: Boolean, now: Long = System.currentTimeMillis()) = restlessness.recordHinge(closed, now)
    fun clearRestlessness() = restlessness.clearRestlessness()
}
