package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.model.StepDays
import ch.heuscher.gentleconnect.model.StepState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Listens to the hardware step counter sensor and persists daily totals.
 *
 * Kept alive in [ch.heuscher.gentleconnect.service.MessengerService] so that counting continues
 * while the phone sleeps in her pocket and midnight is handled properly. The listener uses
 * a 60-second batching latency ([SensorManager.SENSOR_DELAY_NORMAL] with 60s max report latency)
 * to minimise battery consumption: the home screen does not need real-time step-by-step updates.
 *
 * If the device has no step counter hardware or permission is not granted, this remains quiet
 * and unavailable.
 */
class StepCounter(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var registered: Boolean = false

    private val _state = MutableStateFlow<StepState?>(loadState())
    val state: StateFlow<StepState?> = _state.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /**
     * Starts listening for step events if hardware and permissions allow.
     *
     * Idempotent: safe to call repeatedly (e.g. on service start and after permission grant).
     */
    @Synchronized
    fun start() {
        if (registered) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACTIVITY_RECOGNITION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            val ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
            if (ok) {
                sensorManager = sm
                registered = true
                _available.value = true
            } else {
                registered = false
                _available.value = false
            }
        } catch (e: Exception) {
            registered = false
            _available.value = false
        }
    }

    /**
     * Stops listening and marks the counter as unavailable.
     */
    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            // Nothing to do about it
        } finally {
            registered = false
            _available.value = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return
        val raw = event.values[0].toLong()
        val now = System.currentTimeMillis()
        val (today, previousDay) = dayBounds(now)
        val newState = StepDays.advance(_state.value, raw, today, previousDay)
        _state.value = newState
        persist(newState)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Step counters report accuracy changes occasionally; nothing to do with step totals.
    }

    private fun loadState(): StepState? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DAY) || !prefs.contains(KEY_LAST_RAW)) {
            null
        } else {
            val day = prefs.getLong(KEY_DAY, 0L)
            val today = prefs.getLong(KEY_TODAY, 0L)
            val yesterday = if (prefs.contains(KEY_YESTERDAY)) {
                val y = prefs.getLong(KEY_YESTERDAY, -1L)
                if (y >= 0L) y else null
            } else {
                null
            }
            val lastRaw = prefs.getLong(KEY_LAST_RAW, 0L)
            StepState(day = day, today = today, yesterday = yesterday, lastRaw = lastRaw)
        }
    } catch (e: Exception) {
        null
    }

    private fun persist(stepState: StepState) {
        try {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DAY, stepState.day)
                .putLong(KEY_TODAY, stepState.today)
                .putLong(KEY_LAST_RAW, stepState.lastRaw)
            if (stepState.yesterday != null) {
                editor.putLong(KEY_YESTERDAY, stepState.yesterday)
            } else {
                editor.remove(KEY_YESTERDAY)
            }
            editor.apply()
        } catch (e: Exception) {
            // Purely local failure; nothing to disturb her about.
        }
    }

    companion object {
        private const val PREFS = "gentle_connection_steps"
        private const val KEY_DAY = "day"
        private const val KEY_TODAY = "today"
        private const val KEY_YESTERDAY = "yesterday"
        private const val KEY_LAST_RAW = "lastRaw"

        /**
         * Calculates start of today and start of previous day in milliseconds.
         */
        fun dayBounds(now: Long): Pair<Long, Long> {
            val today = startOfDay(now)
            val previousDay = startOfDay(today - 12 * 60 * 60 * 1000L)
            return Pair(today, previousDay)
        }

        private fun startOfDay(instant: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = instant
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
