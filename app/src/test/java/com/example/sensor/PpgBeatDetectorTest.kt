package com.example.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PpgBeatDetectorTest {

    /** Same window the ViewModel analyses: 90 frames at ~30 fps (≈3 s). */
    private val frames = 90
    private val frameMs = 33L

    /**
     * Fingertip-like red-channel signal: DC level ~140 with a systolic wave and a
     * dicrotic second harmonic, optional respiratory drift and random noise.
     */
    private fun signal(
        bpm: Double,
        amplitude: Double = 12.0,
        dicrotic: Double = 3.5,
        drift: Double = 0.0,
        noise: Double = 0.0,
        seed: Int = 42
    ): Pair<List<Double>, List<Long>> {
        val rnd = Random(seed)
        val f = bpm / 60.0
        val t = List(frames) { it * frameMs }
        val y = t.map { ms ->
            val s = ms / 1000.0
            140.0 + amplitude * sin(2 * PI * f * s) +
                dicrotic * sin(4 * PI * f * s + 0.8) +
                drift * sin(2 * PI * 0.25 * s) +
                (rnd.nextDouble() - 0.5) * 2 * noise
        }
        return y to t
    }

    private fun assertBpm(expected: Double, values: List<Double>, times: List<Long>, tolerance: Double = 2.0) {
        val result = PpgBeatDetector.analyze(values, times)
        assertNotNull("expected a pulse at $expected BPM", result)
        assertEquals(expected, result!!.bpm, tolerance)
    }

    @Test fun detects60Bpm() { val (y, t) = signal(60.0); assertBpm(60.0, y, t) }

    @Test fun detects90Bpm() { val (y, t) = signal(90.0); assertBpm(90.0, y, t) }

    @Test fun detects120Bpm() { val (y, t) = signal(120.0); assertBpm(120.0, y, t) }

    @Test fun dicroticWaveIsNotCountedAsASecondBeat() {
        // A strong dicrotic wave must not double the measured rate.
        val (y, t) = signal(72.0, dicrotic = 5.0)
        assertBpm(72.0, y, t)
    }

    @Test fun toleratesNoiseAndRespiratoryDrift() {
        val (y, t) = signal(80.0, drift = 6.0, noise = 1.0)
        assertBpm(80.0, y, t, tolerance = 4.0)
    }

    @Test fun intervalsMatchTheBeatPeriod() {
        val (y, t) = signal(75.0)
        val result = PpgBeatDetector.analyze(y, t)!!
        assertTrue(result.beatIntervalsMs.isNotEmpty())
        result.beatIntervalsMs.forEach { assertEquals(800.0, it, 40.0) }
    }

    @Test fun flatSignalHasNoPulse() {
        val (y, t) = signal(70.0, amplitude = 0.0, dicrotic = 0.0)
        assertNull(PpgBeatDetector.analyze(y, t))
    }

    @Test fun tooFewSamplesHasNoPulse() {
        val (y, t) = signal(70.0)
        assertNull(PpgBeatDetector.analyze(y.take(11), t.take(11)))
    }

    @Test fun perfusionIsWithinClampRange() {
        val (y, t) = signal(70.0)
        val p = PpgBeatDetector.analyze(y, t)!!.perfusionPercent
        assertTrue("perfusion $p out of range", p in 0.8..6.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedInputsAreRejected() {
        PpgBeatDetector.analyze(listOf(1.0, 2.0), listOf(0L))
    }
}
