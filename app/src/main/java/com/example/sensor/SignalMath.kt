package com.example.sensor

import kotlin.math.roundToInt

/** Small signal helpers shared by the analysers. Inputs are oldest first. */
internal object SignalMath {

    /** Centred moving average over [window] samples (shrinks at the edges). */
    fun movingAverage(y: List<Double>, window: Int): List<Double> {
        if (window <= 1 || y.isEmpty()) return y
        val half = window / 2
        val prefix = DoubleArray(y.size + 1)
        for (i in y.indices) prefix[i + 1] = prefix[i] + y[i]
        return y.indices.map { i ->
            val start = maxOf(0, i - half)
            val end = minOf(y.size - 1, i + half)
            (prefix[end + 1] - prefix[start]) / (end - start + 1)
        }
    }

    /** Mean sampling rate in Hz, or 0 when the recording has no duration. */
    fun sampleRateHz(timestampsMs: List<Long>): Double {
        if (timestampsMs.size < 2) return 0.0
        val span = timestampsMs.last() - timestampsMs.first()
        return if (span <= 0) 0.0 else (timestampsMs.size - 1) * 1000.0 / span
    }

    fun samples(seconds: Double, rateHz: Double): Int = maxOf(1, (seconds * rateHz).roundToInt())

    fun variance(y: List<Double>): Double {
        if (y.size < 2) return 0.0
        val mean = y.average()
        return y.sumOf { (it - mean) * (it - mean) } / (y.size - 1)
    }

    fun median(y: List<Double>): Double {
        val s = y.sorted()
        return (s[(s.size - 1) / 2] + s[s.size / 2]) / 2.0
    }
}
