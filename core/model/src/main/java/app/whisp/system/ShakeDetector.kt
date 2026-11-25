// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Pure threshold & debounce state machine for gentle bedside shake detection. Requires two peaks
 * above [GRAVITY_THRESHOLD] within [DOUBLE_SHAKE_WINDOW_MS] and enforces a [COOLDOWN_MS] lockout so
 * accidental pillow adjustments never spam sleep timer extensions.
 */
class ShakeStateMachine(
    private val gravityThreshold: Float = GRAVITY_THRESHOLD,
    private val doubleShakeWindowMs: Long = DOUBLE_SHAKE_WINDOW_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    companion object {
        const val GRAVITY_THRESHOLD = 2.35f
        const val DOUBLE_SHAKE_WINDOW_MS = 650L
        const val COOLDOWN_MS = 5_000L
        private const val MIN_PEAK_GAP_MS = 120L
    }

    private var lastPeakMs = 0L
    private var lastTriggerMs = 0L
    private var peakCount = 0

    fun onAcceleration(ax: Float, ay: Float, az: Float, nowMs: Long): Boolean {
        val gForce = sqrt(ax * ax + ay * ay + az * az) / SensorManager.GRAVITY_EARTH
        if (gForce < gravityThreshold) return false
        if (lastTriggerMs > 0L && nowMs - lastTriggerMs < cooldownMs) return false
        if (lastPeakMs > 0L && nowMs - lastPeakMs < MIN_PEAK_GAP_MS) return false
        if (lastPeakMs > 0L && nowMs - lastPeakMs <= doubleShakeWindowMs) {
            peakCount++
        } else {
            peakCount = 1
        }
        lastPeakMs = nowMs
        if (peakCount >= 2) {
            peakCount = 0
            lastPeakMs = 0L
            lastTriggerMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        lastPeakMs = 0L
        lastTriggerMs = 0L
        peakCount = 0
    }
}

/**
 * Registers the accelerometer ONLY while a sleep timer is actively running and the user enabled
 * shake-to-extend, ensuring zero sensor wakeups during normal playback or standby.
 */
class SleepShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stateMachine = ShakeStateMachine()
    private var registered = false

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val triggered =
                    stateMachine.onAcceleration(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        SystemClock.elapsedRealtime(),
                    )
                if (triggered) onShake()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

    fun setActive(active: Boolean) {
        if (active == registered) return
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        if (active) {
            stateMachine.reset()
            registered =
                runCatching {
                        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                    }
                    .getOrDefault(false)
        } else {
            runCatching { manager.unregisterListener(listener) }
            stateMachine.reset()
            registered = false
        }
    }

    fun release() = setActive(false)
}
