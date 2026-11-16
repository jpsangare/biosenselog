package com.example.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HrvCalculatorTest {

    @Test fun constantIntervalsHaveZeroVariability() {
        val hrv = HrvCalculator.compute(List(8) { 800.0 })!!
        assertEquals(0.0, hrv.rmssdMs, 1e-9)
        assertEquals(0.0, hrv.sdnnMs, 1e-9)
    }

    @Test fun alternatingIntervalsGiveKnownValues() {
        // Successive differences are all ±40 ms → RMSSD = 40.
        val intervals = listOf(780.0, 820.0, 780.0, 820.0, 780.0, 820.0)
        val hrv = HrvCalculator.compute(intervals)!!
        assertEquals(40.0, hrv.rmssdMs, 1e-9)
        // Sample standard deviation of six values ±20 around 800.
        assertEquals(21.9089, hrv.sdnnMs, 1e-3)
    }

    @Test fun missedBeatIsRejectedAsArtifact() {
        // 1600 ms = one missed beat; it must not inflate the variability.
        val intervals = listOf(800.0, 810.0, 790.0, 1600.0, 800.0, 805.0, 795.0)
        val hrv = HrvCalculator.compute(intervals)!!
        assertEquals(6, hrv.beatCount)
        assert(hrv.rmssdMs < 20.0) { "RMSSD ${hrv.rmssdMs} polluted by artifact" }
    }

    @Test fun tooFewIntervalsGiveNoResult() {
        assertNull(HrvCalculator.compute(listOf(800.0, 810.0, 790.0, 805.0)))
    }

    @Test fun tooFewCleanIntervalsGiveNoResult() {
        assertNull(HrvCalculator.compute(listOf(800.0, 810.0, 1600.0, 400.0, 1500.0, 790.0)))
    }

    @Test fun oneMinuteRecordingYieldsMostBeatsDespiteAMotionSpike() {
        // 60 s at 30 fps, 70 BPM (~70 beats), with a large motion artifact around t = 20 s.
        val f = 70.0 / 60.0
        val t = List(1800) { it * 33L }
        val y = t.mapIndexed { i, ms ->
            val s = ms / 1000.0
            val spike = if (i in 600..615) 150.0 else 0.0
            140.0 + 12.0 * sin(2 * PI * f * s) + 3.5 * sin(4 * PI * f * s + 0.8) + spike
        }
        val intervals = PpgBeatDetector.beatIntervalsInWindows(y, t)
        assert(intervals.size >= 50) { "only ${intervals.size} intervals from ~70 beats" }
        assertNotNull(HrvCalculator.compute(intervals))
    }

    @Test fun worksOnDetectorOutputFromATenSecondRecording() {
        // 10 s at 30 fps, 70 BPM: the detector must yield enough intervals for HRV.
        val f = 70.0 / 60.0
        val t = List(300) { it * 33L }
        val y = t.map { ms ->
            val s = ms / 1000.0
            140.0 + 12.0 * sin(2 * PI * f * s) + 3.5 * sin(4 * PI * f * s + 0.8)
        }
        val intervals = PpgBeatDetector.analyze(y, t)!!.beatIntervalsMs
        val hrv = HrvCalculator.compute(intervals)
        assertNotNull("expected HRV from ${intervals.size} intervals", hrv)
        assert(hrv!!.rmssdMs < 25.0) { "steady synthetic pulse gave RMSSD ${hrv.rmssdMs}" }
    }
}
