package com.example.sensor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Short-term heart rate variability from beat-to-beat intervals.
 *
 * @property rmssdMs root mean square of successive interval differences (parasympathetic activity).
 * @property sdnnMs standard deviation of the intervals (overall variability).
 * @property beatCount number of intervals kept after artifact rejection.
 */
data class HrvMetrics(
    val rmssdMs: Double,
    val sdnnMs: Double,
    val beatCount: Int
)

object HrvCalculator {

    /** Fewer clean intervals than this gives a meaningless HRV estimate. */
    const val MIN_INTERVALS = 5

    /** Intervals deviating more than this from the median are missed or extra beats. */
    private const val MAX_DEVIATION_FROM_MEDIAN = 0.20

    /**
     * @param intervalsMs beat-to-beat intervals, oldest first.
     * @return the metrics, or null when too few clean intervals remain.
     */
    fun compute(intervalsMs: List<Double>): HrvMetrics? {
        if (intervalsMs.size < MIN_INTERVALS) return null

        val sorted = intervalsMs.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
        val clean = intervalsMs.filter { abs(it - median) <= median * MAX_DEVIATION_FROM_MEDIAN }
        if (clean.size < MIN_INTERVALS) return null

        val successiveDiffs = clean.zipWithNext { a, b -> b - a }
        val rmssd = sqrt(successiveDiffs.sumOf { it * it } / successiveDiffs.size)

        val mean = clean.average()
        val sdnn = sqrt(clean.sumOf { (it - mean) * (it - mean) } / (clean.size - 1))

        return HrvMetrics(rmssd, sdnn, clean.size)
    }
}
