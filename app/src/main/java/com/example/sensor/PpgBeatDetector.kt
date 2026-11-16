package com.example.sensor

/**
 * Result of analysing a window of fingertip PPG samples.
 *
 * @property bpm recency-weighted heart rate over the latest beats.
 * @property perfusionPercent pulse amplitude relative to the mean signal level, clamped to 0.8..6 %.
 * @property beatIntervalsMs accepted beat-to-beat intervals, oldest first.
 */
data class PpgBeatAnalysis(
    val bpm: Double,
    val perfusionPercent: Double,
    val beatIntervalsMs: List<Double>
)

/** Pure heart-beat detection on a short window of camera PPG samples. */
object PpgBeatDetector {

    /** Minimum peak-to-peak variation (after detrending) considered a real pulse. */
    private const val MIN_PEAK_TO_PEAK = 0.35

    /** Refractory period: peaks closer than this are dicrotic notches or noise (max ~166 BPM). */
    private const val REFRACTORY_MS = 360.0

    /** Physiological interval limits: 375 ms (160 BPM) to 1350 ms (44 BPM). */
    private val VALID_INTERVAL_MS = 375.0..1350.0
    private val VALID_BPM = 44.0..160.0

    /**
     * @param values raw red-channel samples, oldest first.
     * @param timestampsMs capture time of each sample, same size as [values].
     * @return the analysis, or null when no valid pulse can be measured in the window.
     */
    fun analyze(values: List<Double>, timestampsMs: List<Long>): PpgBeatAnalysis? {
        require(values.size == timestampsMs.size) { "values and timestamps must have the same size" }
        if (values.size < 12) return null

        val smoothed = smooth(detrend(values))
        val maxL = smoothed.max()
        val peakToPeak = maxL - smoothed.min()
        if (peakToPeak < MIN_PEAK_TO_PEAK) return null

        val peakTimes = findPeakTimes(smoothed, timestampsMs, maxL)
        val intervals = peakTimes.zipWithNext { a, b -> b - a }.filter { it in VALID_INTERVAL_MS }
        if (intervals.isEmpty()) return null

        val bpm = 60000.0 / recencyWeightedMean(intervals)
        if (bpm !in VALID_BPM) return null

        val mean = values.average().coerceAtLeast(1.0)
        val perfusion = (peakToPeak / mean * 100.0).coerceIn(0.8, 6.0)
        return PpgBeatAnalysis(bpm, perfusion, intervals)
    }

    /**
     * Beat intervals of a long recording, analysed in consecutive windows of [windowSize] samples
     * so a single motion artifact only affects its own window's peak threshold.
     * The one interval straddling each window boundary is lost.
     */
    fun beatIntervalsInWindows(
        values: List<Double>,
        timestampsMs: List<Long>,
        windowSize: Int = 300
    ): List<Double> {
        require(values.size == timestampsMs.size) { "values and timestamps must have the same size" }
        return values.indices.chunked(windowSize).flatMap { idx ->
            val range = idx.first()..idx.last()
            analyze(values.slice(range), timestampsMs.slice(range))?.beatIntervalsMs.orEmpty()
        }
    }

    /** Moving-baseline subtraction to remove slow respiratory drift. */
    private fun detrend(y: List<Double>): List<Double> {
        val half = minOf(16, y.size / 2) / 2
        return y.indices.map { i ->
            val start = maxOf(0, i - half)
            val end = minOf(y.size - 1, i + half)
            var sum = 0.0
            for (j in start..end) sum += y[j]
            y[i] - sum / (end - start + 1)
        }
    }

    /** 3-point binomial smoothing [0.25, 0.5, 0.25] against high-frequency sensor noise. */
    private fun smooth(d: List<Double>): List<Double> = d.indices.map { i ->
        val prev = d[maxOf(0, i - 1)]
        val next = d[minOf(d.size - 1, i + 1)]
        0.25 * prev + 0.5 * d[i] + 0.25 * next
    }

    /**
     * Systolic peaks above 50 % of the maximum (rejects the dicrotic wave), refined with
     * sub-frame parabolic interpolation and filtered by the refractory period.
     */
    private fun findPeakTimes(s: List<Double>, t: List<Long>, maxL: Double): List<Double> {
        val threshold = maxOf(0.20, maxL * 0.50)
        val times = mutableListOf<Double>()
        for (i in 2 until s.size - 2) {
            val c = s[i]
            val isPeak = c > threshold &&
                c >= s[i - 1] && c >= s[i - 2] && c >= s[i + 1] && c >= s[i + 2]
            if (!isPeak) continue

            val denom = 2.0 * (s[i - 1] - 2.0 * c + s[i + 1])
            val delta = if (denom != 0.0) ((s[i - 1] - s[i + 1]) / denom).coerceIn(-0.5, 0.5) else 0.0
            val exact = t[i] + delta * (t[i] - t[i - 1])
            if (times.isEmpty() || exact - times.last() >= REFRACTORY_MS) times.add(exact)
        }
        return times
    }

    /** Weighted mean of the last (up to 4) intervals; the latest beat weighs most for fast response. */
    private fun recencyWeightedMean(intervals: List<Double>): Double {
        val recent = intervals.takeLast(4)
        val weights = when (recent.size) {
            1 -> listOf(1.0)
            2 -> listOf(0.35, 0.65)
            3 -> listOf(0.20, 0.30, 0.50)
            else -> listOf(0.10, 0.20, 0.30, 0.40)
        }
        return recent.indices.sumOf { recent[it] * weights[it] } / weights.sum()
    }
}
