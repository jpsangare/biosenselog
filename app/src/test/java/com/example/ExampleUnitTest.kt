package com.example

import com.example.sensor.FingerDetectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

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

        val isLightAdequate = avgYFlesh in 18.0..252.0
        val isRedDominant = (avgVFlesh >= 136.0 && avgUFlesh <= 128.0 && (avgVFlesh - avgUFlesh >= 14.0)) ||
                (r >= 60.0 && r > g * 1.12 && r > b * 1.30)

        assertTrue("Flesh with flashlight should be detected as adequate light", isLightAdequate)
        assertTrue("Flesh with flashlight should be detected as red-dominant", isRedDominant)

        // Normal neutral room lighting (gray/white wall, desk, or paper)
        val avgYNeutral = 130.0
        val avgUNeutral = 128.0
        val avgVNeutral = 129.0
        val isNeutralRedDominant = (avgVNeutral >= 136.0 && avgUNeutral <= 128.0 && (avgVNeutral - avgUNeutral >= 14.0))
        assertFalse("Neutral lighting should NOT be detected as finger", isNeutralRedDominant)

        // Pitch dark (camera covered with dark cloth without flash)
        val avgYDark = 5.0
        val isDarkAdequate = avgYDark in 18.0..252.0
        assertFalse("Dark frame should NOT be adequate light", isDarkAdequate)
    }

    @Test
    fun testPpgGreenAbsorptionPulseDirection() {
        // As blood volume surges during ventricular systole:
        // Capillary hemoglobin absorbs more green light -> transmitted green drops
        val diastolicGreen = 85.0
        val systolicGreen = 82.0 // Green drops during systole

        val diastolicSignal = 255.0 - diastolicGreen
        val systolicSignal = 255.0 - systolicGreen

        assertTrue("Systolic blood surge must produce a positive peak in the PPG optical absorption signal", systolicSignal > diastolicSignal)
    }

    @Test
    fun testHeartRateBpmCalculationFromIntervals() {
        // 75 BPM corresponds to 800ms inter-beat interval
        val intervalMs = 800.0
        val calculatedBpm = (60000.0 / intervalMs).toInt()
        assertEquals(75, calculatedBpm)

        // 60 BPM corresponds to 1000ms inter-beat interval
        val intervalMs60 = 1000.0
        val calculatedBpm60 = (60000.0 / intervalMs60).toInt()
        assertEquals(60, calculatedBpm60)
    }
}
