package com.example.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Synthetic YUV_420_888 frames in the two memory layouts phone cameras commonly use. */
private object Frames {
    const val W = 64
    const val H = 48

    /**
     * A frame where the centre half has the given Y/U/V and the edges have [edge] values.
     * [interleavedUv] = true: U and V share one buffer with pixel stride 2 (most phones).
     */
    fun make(
        y: Int, u: Int, v: Int,
        edge: Triple<Int, Int, Int> = Triple(y, u, v),
        interleavedUv: Boolean = false
    ): Triple<YuvPlane, YuvPlane, YuvPlane> {
        fun inCentre(row: Int, col: Int, w: Int, h: Int) =
            row in (h / 4) until (h * 3 / 4) && col in (w / 4) until (w * 3 / 4)

        val yBuf = ByteBuffer.allocate(W * H)
        for (row in 0 until H) for (col in 0 until W) {
            yBuf.put(row * W + col, (if (inCentre(row, col, W, H)) y else edge.first).toByte())
        }
        val cw = W / 2
        val ch = H / 2
        return if (interleavedUv) {
            // ... V U V U ... in one buffer; U plane starts one byte after V.
            val uv = ByteBuffer.allocate(cw * ch * 2)
            for (row in 0 until ch) for (col in 0 until cw) {
                val centre = inCentre(row, col, cw, ch)
                uv.put(row * W + col * 2, (if (centre) v else edge.third).toByte())
                uv.put(row * W + col * 2 + 1, (if (centre) u else edge.second).toByte())
            }
            val uBuf = uv.duplicate().apply { position(1) }.slice()
            Triple(YuvPlane(yBuf, W, 1), YuvPlane(uBuf, W, 2), YuvPlane(uv, W, 2))
        } else {
            val uBuf = ByteBuffer.allocate(cw * ch)
            val vBuf = ByteBuffer.allocate(cw * ch)
            for (row in 0 until ch) for (col in 0 until cw) {
                val centre = inCentre(row, col, cw, ch)
                uBuf.put(row * cw + col, (if (centre) u else edge.second).toByte())
                vBuf.put(row * cw + col, (if (centre) v else edge.third).toByte())
            }
            Triple(YuvPlane(yBuf, W, 1), YuvPlane(uBuf, cw, 1), YuvPlane(vBuf, cw, 1))
        }
    }

    fun averages(y: Int, u: Int, v: Int, edge: Triple<Int, Int, Int> = Triple(y, u, v), interleavedUv: Boolean = false): YuvAverages {
        val (yp, up, vp) = make(y, u, v, edge, interleavedUv)
        return YuvSampler.centreAverages(yp, up, vp, W, H)!!
    }
}

class YuvSamplerTest {

    @Test fun averagesAUniformFrame() {
        val avg = Frames.averages(y = 120, u = 90, v = 180)
        assertEquals(120.0, avg.y, 0.01)
        assertEquals(90.0, avg.u, 0.01)
        assertEquals(180.0, avg.v, 0.01)
    }

    @Test fun readsInterleavedChromaLikeMostPhones() {
        val avg = Frames.averages(y = 120, u = 90, v = 180, interleavedUv = true)
        assertEquals(90.0, avg.u, 0.01)
        assertEquals(180.0, avg.v, 0.01)
    }

    @Test fun ignoresTheFrameEdges() {
        // Bright white light leaking around the finger at the edges must not count.
        val avg = Frames.averages(y = 120, u = 90, v = 180, edge = Triple(250, 128, 128))
        assertEquals(120.0, avg.y, 0.01)
        assertEquals(180.0, avg.v, 0.01)
    }

    @Test fun bytesAbove127AreReadAsUnsigned() {
        assertEquals(250.0, Frames.averages(y = 250, u = 200, v = 230).y, 0.01)
    }

    @Test fun emptyFrameGivesNoReading() {
        val (y, u, v) = Frames.make(100, 128, 128)
        assertNull(YuvSampler.centreAverages(y, u, v, width = 0, height = 0))
    }
}

class FingerDetectorTest {

    private val fingerWithFlash = YuvAverages(y = 120.0, u = 90.0, v = 180.0)
    private val greyRoom = YuvAverages(y = 130.0, u = 128.0, v = 129.0)

    private fun FingerDetector.feed(frame: YuvAverages, times: Int): FingerReading =
        (1..times).map { update(frame) }.last()

    @Test fun fingerIsDetectedAfterThreeMatchingFrames() {
        val d = FingerDetector()
        assertFalse(d.feed(fingerWithFlash, 2).isFingerPresent)
        val third = d.update(fingerWithFlash)
        assertTrue(third.isFingerPresent)
        assertEquals(FingerDetectionStatus.DETECTED, third.status)
    }

    @Test fun oneOddFrameDoesNotDropTheFinger() {
        val d = FingerDetector()
        d.feed(fingerWithFlash, 6)
        assertTrue("a single glitch frame must not flicker", d.update(greyRoom).isFingerPresent)
    }

    @Test fun removingTheFingerIsNoticed() {
        val d = FingerDetector()
        d.feed(fingerWithFlash, 6)
        assertFalse(d.feed(greyRoom, 6).isFingerPresent)
    }

    @Test fun greyRoomAsksToCoverTheLens() {
        assertEquals(FingerDetectionStatus.COVER_LENS, FingerDetector().update(greyRoom).status)
    }

    @Test fun darkFrameSaysTooDark() {
        assertEquals(FingerDetectionStatus.TOO_DARK, FingerDetector().update(YuvAverages(5.0, 128.0, 140.0)).status)
    }

    @Test fun clippedFrameSaysTooBright() {
        assertEquals(FingerDetectionStatus.TOO_BRIGHT, FingerDetector().update(YuvAverages(254.0, 100.0, 170.0)).status)
    }

    @Test fun signalRisesWhenBloodAbsorbsMoreGreen() {
        // Systole: more blood -> more green absorbed -> lower green -> higher optical density.
        val d = FingerDetector()
        val diastole = d.update(YuvAverages(110.0, 100.0, 170.0)).ppgSignal
        val systole = d.update(YuvAverages(110.0, 100.0, 175.0)).ppgSignal // more red, less green
        assertTrue("systole $systole should exceed diastole $diastole", systole > diastole)
    }

    @Test fun samplerAndDetectorWorkTogetherOnARealFrameLayout() {
        val d = FingerDetector()
        val frame = Frames.averages(y = 120, u = 90, v = 180, edge = Triple(250, 128, 128), interleavedUv = true)
        assertTrue(d.feed(frame, 3).isFingerPresent)
    }
}
