package ch.heuscher.gentleconnect.telecom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import ch.heuscher.gentleconnect.util.UiTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a folding phone is shut, during a call.
 *
 * Read from the platform hinge-angle sensor, which needs no permission — a book-style folding phone lists
 * it as `hinge_angle`, type 36, where Samsung's own folding-state sensors want a Samsung-only
 * permission. On a phone without a hinge there is no such sensor, [present] is false and [closed]
 * stays null, which leaves the choice to her ([CallAudioPolicy.offersChoice]).
 *
 * Written for a book-style folding phone like hers, where shut is the ordinary telephone with the
 * earpiece on the outside. A flip phone shut is a phone in a pocket, and would be routed the wrong
 * way round by the same rule.
 */
class FoldHinge(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }

    /** Whether this phone folds at all. */
    val present: Boolean get() = sensor != null

    private val _closed = MutableStateFlow<Boolean?>(null)

    /** Null until the first reading arrives after [start], and always on a phone without a hinge. */
    val closed: StateFlow<Boolean?> = _closed.asStateFlow()

    fun start() {
        _closed.value = null
        // An on-change sensor reports its current angle as soon as it is registered, so the
        // first reading arrives with the call rather than with the first fold.
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (sensor != null) sensorManager?.unregisterListener(this)
        _closed.value = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor != sensor) return
        val was = _closed.value
        val now = CallAudioPolicy.hingeClosed(event.values[0], was)
        if (now != was) {
            UiTrace.event(if (now) "fold shut → earpiece" else "fold open → loudspeaker")
            _closed.value = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
