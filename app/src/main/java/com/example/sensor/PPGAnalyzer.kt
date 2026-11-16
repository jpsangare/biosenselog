package com.example.sensor

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

enum class FingerDetectionStatus {
    DETECTED,
    TOO_DARK,
    TOO_BRIGHT,
    COVER_LENS,
    NONE
}

/** Average luminance (Y) and chrominance (U = blue, V = red) of a camera frame, 0..255. */
data class YuvAverages(val y: Double, val u: Double, val v: Double)

/** One plane of a YUV_420_888 frame as the camera delivers it. */
class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

object YuvSampler {

    /** Pixels between samples: fast enough for every frame, still hundreds of samples per frame. */
    private const val STEP = 8

    /**
     * Averages the centre half of the frame (25 % to 75 % on each axis), which ignores light
     * leaking around the finger, lens glare and vignetting at the edges.
     * U and V planes are half resolution (4:2:0). Returns null for an empty frame.
     */
    fun centreAverages(y: YuvPlane, u: YuvPlane, v: YuvPlane, width: Int, height: Int): YuvAverages? {
        var sumY = 0.0
        var sumU = 0.0
        var sumV = 0.0
        var count = 0
        for (row in (height * 0.25).toInt() until (height * 0.75).toInt() step STEP) {
            for (col in (width * 0.25).toInt() until (width * 0.75).toInt() step STEP) {
                sumY += y.read(row * y.rowStride + col * y.pixelStride)
                sumU += u.read((row / 2) * u.rowStride + (col / 2) * u.pixelStride)
                sumV += v.read((row / 2) * v.rowStride + (col / 2) * v.pixelStride)
                count++
            }
        }
        return if (count == 0) null else YuvAverages(sumY / count, sumU / count, sumV / count)
    }

    private fun YuvPlane.read(index: Int): Double =
        if (index < buffer.limit()) (buffer.get(index).toInt() and 0xFF).toDouble() else 0.0
}

/** Result for one frame: the pulse signal value and whether a fingertip covers the lens. */
data class FingerReading(val ppgSignal: Double, val isFingerPresent: Boolean, val status: FingerDetectionStatus)

/**
 * Decides from the frame colour whether a fingertip lit by the flash covers the lens.
 * A confidence counter needs 3 matching frames to switch, so one odd frame does not flicker the state.
 */
class FingerDetector {

    private var confidence = 0

    fun update(frame: YuvAverages): FingerReading {
        val (avgY, avgU, avgV) = frame
        // BT.601 YUV -> RGB
        val r = (avgY + 1.402 * (avgV - 128.0)).coerceIn(0.0, 255.0)
        val g = (avgY - 0.344136 * (avgU - 128.0) - 0.714136 * (avgV - 128.0)).coerceIn(0.0, 255.0)
        val b = (avgY + 1.772 * (avgU - 128.0)).coerceIn(0.0, 255.0)

        // Flash through flesh: neither pitch black nor clipped white.
        val isLightAdequate = avgY in MIN_LUMA..MAX_LUMA
        // Capillary tissue transmits red and absorbs green and blue.
        val isRedDominant = (avgV >= 134.0 && avgV - avgU >= 10.0) ||
            (r >= 50.0 && r > g * 1.08 && r > b * 1.20)

        confidence = if (isLightAdequate && isRedDominant) {
            minOf(confidence + 1, MAX_CONFIDENCE)
        } else {
            maxOf(confidence - 1, 0)
        }
        val isFingerPresent = confidence >= CONFIDENCE_THRESHOLD

        val status = when {
            isFingerPresent -> FingerDetectionStatus.DETECTED
            avgY < MIN_LUMA -> FingerDetectionStatus.TOO_DARK
            avgY > MAX_LUMA -> FingerDetectionStatus.TOO_BRIGHT
            !isRedDominant -> FingerDetectionStatus.COVER_LENS
            else -> FingerDetectionStatus.NONE
        }

        // Optical density of green normalised by brightness: rises when blood volume rises (systole),
        // and cancels slow lighting or pressure changes.
        val ppgSignal = ((255.0 - g) / (avgY + 10.0)) * 200.0
        return FingerReading(ppgSignal, isFingerPresent, status)
    }

    private companion object {
        const val MIN_LUMA = 15.0
        const val MAX_LUMA = 253.0
        const val CONFIDENCE_THRESHOLD = 3
        const val MAX_CONFIDENCE = 6
    }
}

/** CameraX analyzer: turns each camera frame into a pulse sample and a finger-presence state. */
class PPGAnalyzer(
    private val onPPGUpdate: (
        ppgSignal: Double,
        isFingerPresent: Boolean,
        status: FingerDetectionStatus
    ) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FingerDetector()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val img = image.image ?: return
            if (img.planes.size < 3) return
            val (y, u, v) = img.planes.take(3).map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) }
            val averages = YuvSampler.centreAverages(y, u, v, img.width, img.height) ?: return
            val reading = detector.update(averages)
            onPPGUpdate(reading.ppgSignal, reading.isFingerPresent, reading.status)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
