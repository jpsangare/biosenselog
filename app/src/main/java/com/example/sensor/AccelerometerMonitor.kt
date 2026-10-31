package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class AccelerometerMonitor(
    context: Context,
    private val onVibrationUpdate: (rms: Double, waveform: List<Float>) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // HP / LP filter gravity variables
    private var gx = 0.0f
    private var gy = 0.0f
    private var gz = 0.0f
    private val alpha = 0.95f // Gravity tracking filter alpha

    // Waveform history
    private val waveformBuffer = mutableListOf<Float>()
    private val maxBufferSize = 100

    private var rmsValue = 0.0

    // Moving average of squared deviation
    private var squareSum = 0.0f
    private val beta = 0.92f // Moving RMS alpha

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        waveformBuffer.clear()
        gx = 0f
        gy = 0f
        gz = 0f
        squareSum = 0f
        rmsValue = 0.0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val sx = event.values[0]
        val sy = event.values[1]
        val sz = event.values[2]

        // Track gravity vector using dynamic low pass
        gx = alpha * gx + (1 - alpha) * sx
        gy = alpha * gy + (1 - alpha) * sy
        gz = alpha * gz + (1 - alpha) * sz

        // Subtract gravity to get dynamic AC vibrations
        val acX = sx - gx
        val acY = sy - gy
        val acZ = sz - gz

        // Compute instantaneous vibration square-magnitude
        val instantSq = acX * acX + acY * acY + acZ * acZ

        // Apply exponential moving average to squared deviation
        squareSum = beta * squareSum + (1 - beta) * instantSq

        // Calculate dynamic RMS (Root-Mean-Square) amplitude
        val currentRMS = sqrt(squareSum.toDouble())
        rmsValue = currentRMS

        // Add the major Z-axis AC deviation to waveform buffer for real-time SCG visualization
        waveformBuffer.add(acZ)
        if (waveformBuffer.size > maxBufferSize) {
            waveformBuffer.removeAt(0)
        }

        onVibrationUpdate(currentRMS, waveformBuffer.toList())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for simple vibration detection
    }
}
