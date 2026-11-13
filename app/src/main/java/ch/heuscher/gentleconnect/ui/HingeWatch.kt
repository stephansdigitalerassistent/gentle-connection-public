package ch.heuscher.gentleconnect.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import ch.heuscher.gentleconnect.telecom.CallAudioPolicy
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * Monitors the folding phone's hinge state to detect fold cycles and restlessness.
 *
 * Uses the permission-free [Sensor.TYPE_HINGE_ANGLE] sensor, using [CallAudioPolicy.hingeClosed]
 * for hysteresis. On non-folding devices, the sensor is absent and no events are emitted.
 */
class HingeWatch(
    context: Context,
    private val onChange: (closed: Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }

    private var closed: Boolean? = null

    fun start() {
        closed = null
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (sensor != null) {
            sensorManager?.unregisterListener(this)
        }
        closed = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor != sensor) return
        val was = closed
        val now = CallAudioPolicy.hingeClosed(event.values[0], was)
        if (now != was) {
            closed = now
            UiTrace.event(if (now) "hinge shut" else "hinge open")
            onChange(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
