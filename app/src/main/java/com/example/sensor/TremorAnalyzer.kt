package com.example.sensor

import com.example.sensor.SignalMath.movingAverage
import com.example.sensor.SignalMath.sampleRateHz
import com.example.sensor.SignalMath.samples
import com.example.sensor.SignalMath.variance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * @property amplitudeRms tremor acceleration (3 to 15 Hz band), m/s² RMS over the three axes.
 * @property dominantHz frequency carrying the most tremor energy.
 */
data class TremorResult(val amplitudeRms: Double, val dominantHz: Double)

/** Hand tremor from a short accelerometer recording of the phone held at arm's length. */
object TremorAnalyzer {

    const val MIN_SPAN_MS = 5_000L

    /** Below this the sensor cannot resolve tremor up to 15 Hz. */
    const val MIN_SAMPLE_RATE_HZ = 30.0

    private const val BAND_LOW_HZ = 3.0
    private const val BAND_HIGH_HZ = 15.0
    private const val FREQ_STEP_HZ = 0.1

    /** Subtracting this moving average removes arm drift and gravity (below ~3 Hz). */
    private const val HIGH_PASS_SECONDS = 0.3

    fun analyze(x: List<Double>, y: List<Double>, z: List<Double>, timestampsMs: List<Long>): TremorResult? {
        require(x.size == timestampsMs.size && y.size == x.size && z.size == x.size) {
            "axes and timestamps must have the same size"
        }
        if (timestampsMs.size < 2 || timestampsMs.last() - timestampsMs.first() < MIN_SPAN_MS) return null
        val rate = sampleRateHz(timestampsMs)
        if (rate < MIN_SAMPLE_RATE_HZ) return null

        val w = samples(HIGH_PASS_SECONDS, rate)
        val axes = listOf(x, y, z).map { a ->
            val low = movingAverage(a, w)
            a.indices.map { a[it] - low[it] }
        }

        val rms = sqrt(timestampsMs.indices.sumOf { i -> axes.sumOf { it[i] * it[i] } } / timestampsMs.size)

        // Frequency from the dominant axis (the vector magnitude would double the frequency).
        val main = axes.maxBy { variance(it) }
        val t0 = timestampsMs.first()
        val seconds = timestampsMs.map { (it - t0) / 1000.0 }
        val steps = ((BAND_HIGH_HZ - BAND_LOW_HZ) / FREQ_STEP_HZ).toInt()
        val dominant = (0..steps).map { BAND_LOW_HZ + it * FREQ_STEP_HZ }.maxBy { f ->
            // Power at f, computed on the real timestamps (sensor timing is irregular).
            var re = 0.0
            var im = 0.0
            for (i in main.indices) {
                val phase = 2 * PI * f * seconds[i]
                re += main[i] * cos(phase)
                im -= main[i] * sin(phase)
            }
            re * re + im * im
        }
        return TremorResult(rms, dominant)
    }
}
