package com.example.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class RespirationDetectorTest {

    private fun times(seconds: Int, rateHz: Int) = List(seconds * rateHz) { it * 1000L / rateHz }

    @Test fun chestAccelerometerAt15BreathsPerMinute() {
        // Phone flat on the chest at ~16 Hz: breathing tilts it, mostly visible on the y axis.
        val rnd = Random(1)
        val t = times(60, 16)
        val x = t.map { 0.3 + (rnd.nextDouble() - 0.5) * 0.02 }
        val y = t.map { 0.4 + 0.06 * sin(2 * PI * 0.25 * it / 1000.0) + (rnd.nextDouble() - 0.5) * 0.02 }
        val z = t.map { 9.8 + (rnd.nextDouble() - 0.5) * 0.02 }
        assertEquals(15.0, RespirationDetector.estimateFromAxes(listOf(x, y, z), t)!!, 1.0)
    }

    @Test fun fingertipPpgBaselineAt12BreathsPerMinute() {
        // 30 fps camera: 70 BPM heartbeat plus a small breathing-driven baseline change.
        for (heartBpm in listOf(50.0, 70.0, 95.0)) {
            val t = times(60, 30)
            val y = t.map {
                val s = it / 1000.0
                140.0 + 12.0 * sin(2 * PI * heartBpm / 60.0 * s) + 2.0 * sin(2 * PI * 0.2 * s)
            }
            val rate = RespirationDetector.estimate(y, t)
            assertNotNull("no breathing found at $heartBpm BPM", rate)
            assertEquals("at $heartBpm BPM", 12.0, rate!!, 1.0)
        }
    }

    @Test fun fastBreathingAt24PerMinute() {
        val t = times(60, 16)
        val y = t.map { 0.1 * sin(2 * PI * 0.4 * it / 1000.0) }
        assertEquals(24.0, RespirationDetector.estimate(y, t)!!, 1.5)
    }

    @Test fun tenSecondsIsTooShort() {
        val t = times(10, 16)
        val y = t.map { sin(2 * PI * 0.25 * it / 1000.0) }
        assertNull(RespirationDetector.estimate(y, t))
    }

    @Test fun randomNoiseIsNotBreathing() {
        val rnd = Random(7)
        val t = times(60, 16)
        val y = t.map { rnd.nextDouble() - 0.5 }
        assertNull(RespirationDetector.estimate(y, t))
    }
}

class TremorAnalyzerTest {

    private fun times(seconds: Int, rateHz: Int) = List(seconds * rateHz) { it * 1000L / rateHz }

    @Test fun physiologicalTremorAt10Hz() {
        val t = times(15, 50)
        val x = t.map { 0.1 * sin(2 * PI * 10.0 * it / 1000.0) + 0.5 * sin(2 * PI * 0.3 * it / 1000.0) }
        val y = t.map { 0.0 }
        val z = t.map { 9.81 }
        val r = TremorAnalyzer.analyze(x, y, z, t)!!
        assertEquals(10.0, r.dominantHz, 0.3)
        assertEquals(0.1 / kotlin.math.sqrt(2.0), r.amplitudeRms, 0.015) // sine RMS, drift removed
    }

    @Test fun slowLargeTremorAt5Hz() {
        val t = times(15, 50)
        val x = t.map { 0.0 }
        val y = t.map { 0.6 * sin(2 * PI * 5.0 * it / 1000.0) }
        val z = t.map { 9.81 }
        assertEquals(5.0, TremorAnalyzer.analyze(x, y, z, t)!!.dominantHz, 0.3)
    }

    @Test fun lowSampleRateIsRejected() {
        val t = times(15, 16)
        val a = t.map { sin(2 * PI * 5.0 * it / 1000.0) }
        assertNull(TremorAnalyzer.analyze(a, a, a, t))
    }

    @Test fun shortRecordingIsRejected() {
        val t = times(3, 50)
        val a = t.map { 0.0 }
        assertNull(TremorAnalyzer.analyze(a, a, a, t))
    }
}

class DailyStepAccumulatorTest {

    @Test fun firstReadingStartsAtZero() {
        assertEquals(0L, DailyStepAccumulator.update(null, 5000, "2026-11-15").todaySteps)
    }

    @Test fun stepsAccumulateDuringTheDay() {
        val s1 = DailyStepAccumulator.update(null, 5000, "2026-11-15")
        val s2 = DailyStepAccumulator.update(s1, 5120, "2026-11-15")
        assertEquals(120L, s2.todaySteps)
    }

    @Test fun newDayStartsFromTheStepsSinceLastReading() {
        val s1 = StepState("2026-11-15", 8000, 20_000)
        val s2 = DailyStepAccumulator.update(s1, 20_050, "2026-11-16")
        assertEquals(50L, s2.todaySteps)
        assertEquals("2026-11-16", s2.day)
    }

    @Test fun rebootResetOfTheSensorKeepsTheTotal() {
        val s1 = StepState("2026-11-15", 3000, 20_000)
        val s2 = DailyStepAccumulator.update(s1, 40, "2026-11-15")
        assertEquals(3040L, s2.todaySteps)
    }
}
