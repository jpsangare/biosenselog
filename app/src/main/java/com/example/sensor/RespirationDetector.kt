package com.example.sensor

import com.example.sensor.SignalMath.median
import com.example.sensor.SignalMath.movingAverage
import com.example.sensor.SignalMath.sampleRateHz
import com.example.sensor.SignalMath.samples
import com.example.sensor.SignalMath.variance
import kotlin.math.sqrt

/**
 * Breathing rate from a slow body signal: chest accelerometer (phone lying on the chest)
 * or the baseline of a fingertip PPG recording, which breathing modulates.
 */
object RespirationDetector {

    /** Shorter recordings hold too few breaths for a rate. */
    const val MIN_SPAN_MS = 25_000L

    private val VALID_RATE = 6.0..40.0 // breaths per minute
    private val VALID_BREATH_MS = 1500.0..10_000.0
    private const val MIN_BREATHS = 3

    /** Breath-to-breath intervals vary more than this in noise, not in real breathing. */
    private const val MAX_INTERVAL_VARIATION = 0.35

    /** Minimum self-similarity one breath later; separates breathing from smoothed noise. */
    private const val MIN_PERIODICITY = 0.5

    /**
     * Smoothing length. Applied twice, it cancels the heartbeat ripple (~1 Hz) in PPG while
     * keeping normal breathing (0.1 to 0.5 Hz).
     */
    private const val SMOOTH_SECONDS = 1.2
    private const val BASELINE_SECONDS = 10.0
    private const val PEAK_THRESHOLD = 0.30

    /** @return breaths per minute, or null when no regular breathing is found. */
    fun estimate(values: List<Double>, timestampsMs: List<Long>): Double? {
        require(values.size == timestampsMs.size) { "values and timestamps must have the same size" }
        if (values.size < 20 || timestampsMs.last() - timestampsMs.first() < MIN_SPAN_MS) return null
        val rate = sampleRateHz(timestampsMs)
        val s = breathingBand(values, rate)

        val maxS = s.max()
        if (maxS <= 0.0) return null
        val threshold = maxS * PEAK_THRESHOLD
        val half = samples(VALID_BREATH_MS.start / 2000.0, rate)
        // Skip the edges, where the filters see only part of their window.
        val margin = samples(SMOOTH_SECONDS, rate)

        val peakTimes = mutableListOf<Long>()
        for (i in margin until s.size - margin) {
            if (s[i] < threshold) continue
            val from = maxOf(0, i - half)
            val to = minOf(s.size - 1, i + half)
            if ((from..to).any { s[it] > s[i] }) continue
            val t = timestampsMs[i]
            if (peakTimes.isEmpty() || t - peakTimes.last() >= VALID_BREATH_MS.start) peakTimes.add(t)
        }

        val intervals = peakTimes.zipWithNext { a, b -> (b - a).toDouble() }.filter { it in VALID_BREATH_MS }
        if (intervals.size < MIN_BREATHS - 1) return null

        val mean = intervals.average()
        if (sqrt(variance(intervals)) / mean > MAX_INTERVAL_VARIATION) return null

        val period = median(intervals)
        if (autocorrelation(s, samples(period / 1000.0, rate)) < MIN_PERIODICITY) return null

        val breathsPerMinute = 60_000.0 / period
        return breathsPerMinute.takeIf { it in VALID_RATE }
    }

    /** Normalised autocorrelation at [lag] samples: ~1 for a repeating wave, ~0 for smoothed noise. */
    private fun autocorrelation(s: List<Double>, lag: Int): Double {
        if (lag <= 0 || lag >= s.size) return 0.0
        val mean = s.average()
        var num = 0.0
        var den = 0.0
        for (i in s.indices) {
            val d = s[i] - mean
            den += d * d
            if (i + lag < s.size) num += d * (s[i + lag] - mean)
        }
        return if (den == 0.0) 0.0 else num / den * s.size / (s.size - lag)
    }

    /**
     * Multi-axis variant for the accelerometer: breathing shows mostly on one axis depending
     * on how the phone lies, so the axis with the strongest breathing-band signal is used.
     */
    fun estimateFromAxes(axes: List<List<Double>>, timestampsMs: List<Long>): Double? {
        if (timestampsMs.size < 20) return null
        val rate = sampleRateHz(timestampsMs)
        val best = axes.maxByOrNull { variance(breathingBand(it, rate)) } ?: return null
        return estimate(best, timestampsMs)
    }

    private fun breathingBand(values: List<Double>, rateHz: Double): List<Double> {
        val baseline = movingAverage(values, samples(BASELINE_SECONDS, rateHz))
        val detrended = values.indices.map { values[it] - baseline[it] }
        val w = samples(SMOOTH_SECONDS, rateHz)
        return movingAverage(movingAverage(detrended, w), w)
    }
}
