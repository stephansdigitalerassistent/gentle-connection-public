package ch.heuscher.gentleconnect.ui

import android.content.Context
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.CaregiverLog
import ch.heuscher.gentleconnect.model.AgitationPolicy
import ch.heuscher.gentleconnect.model.AgitationTracker
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.QuietHours
import ch.heuscher.gentleconnect.model.RestlessSnapshotThrottle
import ch.heuscher.gentleconnect.model.TouchSample
import ch.heuscher.gentleconnect.util.RestlessSnapshots
import ch.heuscher.gentleconnect.util.UiTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persistence abstraction for learned quiet hours history.
 */
interface QuietHoursStore {
    fun load(): String?
    fun save(encoded: String)
}

/**
 * Whether she seems restless right now — tapping without effect, calling the same person over and
 * over, opening the support button without confirming — and what to do about it.
 *
 * Shared rather than owned by one screen's view model, because the signs come from several: a
 * tap anywhere, a call placed from the home screen or the phone book, the support button. One
 * tracker sees all of them, which is the only way "three calls to the same number in a minute"
 * can be noticed at all. See [AgitationTracker] for the rules themselves.
 */
class Restlessness(
    private val scope: CoroutineScope,
    private val quietHoursStore: QuietHoursStore? = null,
    /** Writes a fault down for the caregiver. Never waited for. */
    private val recordFault: (CaregiverEvent) -> Unit
) {

    private val quietHours = QuietHours.decode(quietHoursStore?.load())
    private val agitationTracker = AgitationTracker(quietHours)
    private val _isRestless = MutableStateFlow(false)
    val isRestless: StateFlow<Boolean> = _isRestless.asStateFlow()

    private val snapshotThrottle = RestlessSnapshotThrottle()
    private val _restlessSnapshotRequests = MutableSharedFlow<AgitationTrigger>(extraBufferCapacity = 1)
    val restlessSnapshotRequests: SharedFlow<AgitationTrigger> = _restlessSnapshotRequests.asSharedFlow()

    private var pendingLogJob: Job? = null

    fun recordTouch(sample: TouchSample) {
        val epochDay = LocalDate.now().toEpochDay()
        val hour = Instant.ofEpochMilli(sample.upAt).atZone(ZoneId.systemDefault()).hour
        if (quietHours.record(epochDay, hour)) {
            quietHoursStore?.save(quietHours.encode())
        }
        val trigger = agitationTracker.onTouch(sample, hour)
        handleAgitationTrigger(trigger, sample.upAt)
    }

    fun recordScreenChange(screen: String, now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onScreenChange(screen, now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun recordBackWithoutEffect(now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onBackWithoutEffect(now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun recordScreenOn(now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onScreenOn(now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun recordHinge(closed: Boolean, now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onHinge(closed, now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun recordGoal(goal: String, now: Long = System.currentTimeMillis()) {
        UiTrace.event("goal $goal")
        val resolved = agitationTracker.onGoal(now)
        _isRestless.value = false
        if (resolved) {
            pendingLogJob?.cancel()
            pendingLogJob = null
            RestlessSnapshots.markResolved(goal, now)
        }
    }

    fun recordCallAttempt(target: String, now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onCallAttempt(target, now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun recordSupportOpenedNotConfirmed(now: Long = System.currentTimeMillis()) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val trigger = agitationTracker.onSupportOpenedNotConfirmed(now, hour)
        handleAgitationTrigger(trigger, now)
    }

    fun clearRestlessness() {
        agitationTracker.clear()
        _isRestless.value = false
    }

    private fun handleAgitationTrigger(trigger: AgitationTrigger?, now: Long) {
        val restlessNow = agitationTracker.isRestless(now)
        if (_isRestless.value != restlessNow) {
            _isRestless.value = restlessNow
        }
        if (restlessNow) {
            scope.launch {
                delay(AgitationPolicy.ACTIVE_DURATION_MS)
                _isRestless.value = agitationTracker.isRestless(System.currentTimeMillis())
            }
        }
        if (trigger != null && snapshotThrottle.shouldCapture(now)) {
            _restlessSnapshotRequests.tryEmit(trigger)
        }
        // The caregiver entry waits RESOLVE_MS: when she reaches a goal in that time recordGoal
        // cancels it. The throttle is asked only once the wait is over, so a resolved episode
        // does not use up the 10 minutes and silence a real one shortly after.
        if (trigger != null) {
            pendingLogJob?.cancel()
            pendingLogJob = scope.launch {
                delay(AgitationPolicy.RESOLVE_MS)
                if (!agitationTracker.shouldLogToCaregiver(now)) return@launch
                val screen = UiTrace.screen.takeIf { it.isNotBlank() && it != "-" }
                val subject = if (screen != null) "${trigger.label} · $screen" else trigger.label
                recordFault(
                    CaregiverEvent(
                        kind = CaregiverEventKind.RESTLESSNESS_DETECTED,
                        at = now,
                        subject = CaregiverLog.anonymise(subject)
                    )
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: Restlessness? = null

        /** The process's one tracker, so the signs from every screen are counted together. */
        fun shared(context: Context): Restlessness = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences("restlessness", Context.MODE_PRIVATE)
                val store = object : QuietHoursStore {
                    override fun load(): String? = prefs.getString("quiet_hours", null)
                    override fun save(encoded: String) {
                        prefs.edit().putString("quiet_hours", encoded).apply()
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                Restlessness(scope, store) { event ->
                    scope.launch(Dispatchers.IO) { runCatching { AppGraph.caregiverLog(app).record(event) } }
                }.also { instance = it }
            }
        }
    }
}
