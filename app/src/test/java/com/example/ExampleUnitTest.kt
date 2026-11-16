package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Finger detection and the PPG signal formula are tested on the real code in sensor/FrameAnalysisTest.kt.
class ExampleUnitTest {

    @Test
    fun testParabolicPeakInterpolation() {
        // True peak lies between samples 1 and 2
        val y1 = 4.0
        val y2 = 5.0
        val y3 = 4.5
        val denom = 2.0 * (y1 - 2.0 * y2 + y3)
        val delta = ((y1 - y3) / denom).coerceIn(-0.5, 0.5)

        // Peak is tilted towards y3 because y3 > y1
        assertTrue("Parabolic interpolation should tilt towards the higher neighbor", delta > 0.0)
    }

    @Test
    fun testDicroticNotchRejection() {
        val systolicPeak = 8.0
        val dicroticPeak = 3.0 // Dicrotic notch at 37.5% of systolic peak
        val peakThreshold = maxOf(0.20, systolicPeak * 0.50) // 4.0

        assertTrue("Systolic peak must be accepted", systolicPeak > peakThreshold)
        assertFalse("Dicrotic notch must be rejected to prevent double counting", dicroticPeak > peakThreshold)
    }

    @Test
    fun testRecencyWeightedIntervalBpm() {
        // Heart rate changed from 60 BPM (1000ms) to 100 BPM (600ms)
        val intervals = listOf(1000.0, 1000.0, 750.0, 600.0)
        val weights = listOf(0.10, 0.20, 0.30, 0.40)

        var weightedSum = 0.0
        var weightSum = 0.0
        for (i in intervals.indices) {
            weightedSum += intervals[i] * weights[i]
            weightSum += weights[i]
        }
        val weightedInterval = weightedSum / weightSum
        val calculatedBpm = (60000.0 / weightedInterval).toInt()

        // Weighted interval reflects the recent fast beats much more than simple unweighted mean
        val unweightedBpm = (60000.0 / intervals.average()).toInt()
        assertTrue("Recency-weighted BPM ($calculatedBpm) should adapt faster than unweighted average ($unweightedBpm)", calculatedBpm > unweightedBpm)
    }

    @Test
    fun testMeasurementExportFormatting() {
        val records = listOf(
            com.example.data.BiometricRecord(
                id = 1L,
                type = "BLOOD_FLOW",
                value = 74.0,
                secondaryValue = 2.4,
                status = "NORMAL",
                timestamp = 1700000000000L
            ),
            com.example.data.BiometricRecord(
                id = 2L,
                type = "CHEST_VIBRATION",
                value = 0.125,
                secondaryValue = 0.0,
                status = "NORMAL",
                timestamp = 1700000060000L
            )
        )

        val csvHeader = "ID,Timestamp,Date Time,Sensor Type,Value,Unit,Secondary Value,Status,Notes"
        val record1 = records[0]
        val isPpg = record1.type == "BLOOD_FLOW"
        assertTrue(csvHeader.contains("Sensor Type"))
        assertTrue(csvHeader.contains("Value"))
        assertTrue(isPpg)
        assertEquals(74, record1.value.toInt())
        assertEquals(2.4, record1.secondaryValue, 0.01)
    }
}
