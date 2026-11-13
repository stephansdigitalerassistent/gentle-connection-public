package ch.heuscher.gentleconnect.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide state for caregiver Advanced Mode.
 *
 * Patterned after [ch.heuscher.gentleconnect.telecom.CallAudio] and
 * [ch.heuscher.gentleconnect.telecom.OngoingCall] as a process-wide singleton.
 *
 * ## Why this mode exists
 *
 * The regular screens are deliberately stripped down for an elderly user with cognitive decline:
 * no error copy, one action per row, no selectable text handles, and no way to wander into the
 * broader Android OS. Advanced Mode exists for a visiting caregiver or family member who needs to
 * perform ordinary phone tasks (copying addresses, forwarding photos, viewing technical details,
 * deleting items on this device, or muting conversations).
 *
 * ## Why it times out
 *
 * Leaving the phone in Advanced Mode is the primary hazard of this design: selectable text handles
 * appearing under her thumb and system share sheets leading to external apps (like Gmail or Chrome)
 * break the cognitive safety boundary. The [IDLE_TIMEOUT_MS] idle timer guarantees that even if a
 * caregiver forgets to tap the band or leaves the flat, the phone silently returns to its safe,
 * protected state on its own.
 *
 * ## Why it must not survive process death
 *
 * Advanced Mode is an ephemeral session. It survives Compose navigation across screens within the
 * app session, but must **never** be persisted to disk or survive process restart: a phone that
 * reboots or restarts in Advanced Mode would strand her in an unfamiliar, complex interface.
 */
object AdvancedMode {

    /**
     * How long the mode stays active without any caregiver interaction.
     * Five minutes gives ample time to copy, share, or check technical logs, but reliably expires
     * if the phone is set down on the table.
     */
    const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private val _isOn = MutableStateFlow(false)

    /** Whether Advanced Mode is currently active across the app. */
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    private var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var timeoutJob: Job? = null

    /**
     * Enables Advanced Mode and starts the idle timeout timer.
     */
    fun enable() {
        _isOn.value = true
        restartTimeout()
    }

    /**
     * Disables Advanced Mode immediately and cancels any running timeout.
     */
    fun disable() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isOn.value = false
    }

    /**
     * Toggles Advanced Mode state.
     */
    fun toggle() {
        if (_isOn.value) {
            disable()
        } else {
            enable()
        }
    }

    /**
     * Restarts the idle countdown. Must be called by every advanced action or sheet interaction.
     */
    fun touch() {
        if (_isOn.value) {
            restartTimeout()
        }
    }

    private fun restartTimeout() {
        timeoutJob?.cancel()
        timeoutJob = coroutineScope.launch {
            delay(IDLE_TIMEOUT_MS)
            _isOn.value = false
        }
    }

    /**
     * For unit testing: sets a custom scope (e.g. TestScope).
     */
    internal fun setScopeForTesting(testScope: CoroutineScope) {
        coroutineScope = testScope
    }

    /**
     * For unit testing: resets state to clean baseline.
     */
    internal fun resetForTesting() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isOn.value = false
        coroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
