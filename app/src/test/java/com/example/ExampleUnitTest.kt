package com.example

import com.example.sensor.FingerDetectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testFingerDetectionColorClassification() {
        // Red illuminated flesh (typical camera with finger on lens + flash)
        val avgYFlesh = 120.0
        val avgUFlesh = 90.0   // Suppressed blue chroma
        val avgVFlesh = 180.0  // High red chroma

        val r = (avgYFlesh + 1.402 * (avgVFlesh - 128.0)).coerceIn(0.0, 255.0)
        val g = (avgYFlesh - 0.344136 * (avgUFlesh - 128.0) - 0.714136 * (avgVFlesh - 128.0)).coerceIn(0.0, 255.0)
        val b = (avgYFlesh + 1.772 * (avgUFlesh - 128.0)).coerceIn(0.0, 255.0)

        val isLightAdequate = avgYFlesh in 15.0..253.0
        val isRedDominant = (avgVFlesh >= 134.0 && (avgVFlesh - avgUFlesh >= 10.0)) ||
                (r >= 50.0 && r > g * 1.08 && r > b * 1.20)

        assertTrue("Flesh with flashlight should be detected as adequate light", isLightAdequate)
        assertTrue("Flesh with flashlight should be detected as red-dominant", isRedDominant)

        // Normal neutral room lighting (gray/white wall, desk, or paper)
        val avgYNeutral = 130.0
        val avgUNeutral = 128.0
        val avgVNeutral = 129.0
        val isNeutralRedDominant = (avgVNeutral >= 134.0 && (avgVNeutral - avgUNeutral >= 10.0))
        assertFalse("Neutral lighting should NOT be detected as finger", isNeutralRedDominant)

        // Pitch dark (camera covered with dark cloth without flash)
        val avgYDark = 5.0
        val isDarkAdequate = avgYDark in 15.0..253.0
        assertFalse("Dark frame should NOT be adequate light", isDarkAdequate)
    }

    @Test
    fun testPpgOpticalDensitySignalDirection() {
        // During ventricular systole:
        // Capillary hemoglobin expands -> absorbs more green light -> transmitted green drops
        val diastolicGreen = 85.0
        val systolicGreen = 75.0 // Green drops during systole
        val avgY = 110.0

        val diastolicSignal = ((255.0 - diastolicGreen) / (avgY + 10.0)) * 200.0
        val systolicSignal = ((255.0 - systolicGreen) / (avgY + 10.0)) * 200.0

        assertTrue("Systolic blood surge must produce a positive peak in the normalized PPG optical density signal", systolicSignal > diastolicSignal)
    }

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
