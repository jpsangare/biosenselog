package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today's steps, accumulated from the step counter sensor's running total.
 *
 * @property day the day these steps belong to, as yyyy-MM-dd.
 * @property lastCounter sensor total at the previous reading (null before the first one).
 */
data class StepState(val day: String, val todaySteps: Long, val lastCounter: Long?)

object DailyStepAccumulator {
    /**
     * Adds the steps since the previous reading. The sensor total restarts at 0 after a reboot.
     * Steps walked while the app was not reading are attributed to the day of the next reading.
     */
    fun update(previous: StepState?, counter: Long, today: String): StepState {
        val last = previous?.lastCounter
        val delta = when {
            last == null -> 0L
            counter >= last -> counter - last
            else -> counter // reboot
        }
        val base = if (previous != null && previous.day == today) previous.todaySteps else 0L
        return StepState(today, base + delta, counter)
    }
}

/** Reads the step counter sensor and keeps today's total across app restarts. */
class StepCounterMonitor(
    context: Context,
    private val onStepsToday: (Long) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = context.getSharedPreferences("step_counter", Context.MODE_PRIVATE)

    val isAvailable: Boolean get() = stepSensor != null

    fun start() {
        val sensor = stepSensor ?: return
        val saved = load()
        onStepsToday(if (saved?.day == today()) saved.todaySteps else 0L)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val state = DailyStepAccumulator.update(load(), event.values[0].toLong(), today())
        save(state)
        onStepsToday(state.todaySteps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun load(): StepState? {
        val day = prefs.getString(KEY_DAY, null) ?: return null
        val last = prefs.getLong(KEY_LAST, -1L).takeIf { it >= 0 }
        return StepState(day, prefs.getLong(KEY_TODAY, 0L), last)
    }

    private fun save(state: StepState) {
        prefs.edit {
            putString(KEY_DAY, state.day)
            putLong(KEY_TODAY, state.todaySteps)
            putLong(KEY_LAST, state.lastCounter ?: -1L)
        }
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_TODAY = "today_steps"
        private const val KEY_LAST = "last_counter"

        fun isAvailable(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
}
