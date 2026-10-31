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

class PPGAnalyzer(
    private val onPPGUpdate: (
        ppgSignal: Double,
        isFingerPresent: Boolean,
        status: FingerDetectionStatus
    ) -> Unit
) : ImageAnalysis.Analyzer {

    // Debounce counter to avoid single-frame flickering
    private var detectedConfidence = 0
    private val confidenceThreshold = 3
    private val maxConfidence = 6

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val img = image.image ?: run {
            image.close()
            return
        }

        try {
            if (img.planes.size < 3) {
                image.close()
                return
            }

            // Planes: 0 = Y (Luminance), 1 = U (Cb chrominance), 2 = V (Cr red-chrominance)
            val yPlane = img.planes[0]
            val uPlane = img.planes[1]
            val vPlane = img.planes[2]

            val yBuffer: ByteBuffer = yPlane.buffer
            val uBuffer: ByteBuffer = uPlane.buffer
            val vBuffer: ByteBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val width = img.width
            val height = img.height

            // Center Region of Interest (ROI): sample the middle 50% of the frame
            // This ignores edge light leakage, peripheral lens glare, and vignetting
            val roiStartX = (width * 0.25).toInt()
            val roiEndX = (width * 0.75).toInt()
            val roiStartY = (height * 0.25).toInt()
            val roiEndY = (height * 0.75).toInt()

            var sumY = 0.0
            var sumU = 0.0
            var sumV = 0.0
            var sampleCount = 0

            // Sample on an 8-pixel grid for ultra-fast, CPU-efficient evaluation
            val step = 8
            val yLimit = yBuffer.limit()
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()

            var yMin = 255.0
            var yMax = 0.0

            for (y in roiStartY until roiEndY step step) {
                val yRowOffset = y * yRowStride
                val uvY = y / 2
                val uRowOffset = uvY * uRowStride
                val vRowOffset = uvY * vRowStride

                for (x in roiStartX until roiEndX step step) {
                    val yIndex = yRowOffset + x * yPixelStride
                    if (yIndex < yLimit) {
                        val yVal = (yBuffer.get(yIndex).toInt() and 0xFF).toDouble()
                        sumY += yVal
                        if (yVal < yMin) yMin = yVal
                        if (yVal > yMax) yMax = yVal
                    }

                    val uvX = x / 2
                    val uIndex = uRowOffset + uvX * uPixelStride
                    if (uIndex < uLimit) {
                        sumU += (uBuffer.get(uIndex).toInt() and 0xFF).toDouble()
                    }

                    val vIndex = vRowOffset + uvX * vPixelStride
                    if (vIndex < vLimit) {
                        sumV += (vBuffer.get(vIndex).toInt() and 0xFF).toDouble()
                    }

                    sampleCount++
                }
            }

            if (sampleCount > 0) {
                val avgY = sumY / sampleCount
                val avgU = sumU / sampleCount
                val avgV = sumV / sampleCount

                // Convert YUV to approximate RGB (BT.601 standard)
                // R = Y + 1.402 * (V - 128)
                // G = Y - 0.344136 * (U - 128) - 0.714136 * (V - 128)
                // B = Y + 1.772 * (U - 128)
                val r = (avgY + 1.402 * (avgV - 128.0)).coerceIn(0.0, 255.0)
                val g = (avgY - 0.344136 * (avgU - 128.0) - 0.714136 * (avgV - 128.0)).coerceIn(0.0, 255.0)
                val b = (avgY + 1.772 * (avgU - 128.0)).coerceIn(0.0, 255.0)

                // Detailed finger presence criteria:
                // 1. Adequate light: flashlight penetrating flesh is neither pitch black nor blown out
                val isLightAdequate = avgY in 18.0..252.0
                // 2. Translucent flesh spectral signature:
                // Blood tissue strongly transmits red light and absorbs green/blue.
                // - V (red chrominance) is high (typically 145-220, neutral is 128)
                // - U (blue chrominance) is suppressed (typically 75-118, neutral is 128)
                // - V - U difference is pronounced (typically > 16)
                // - Or in RGB: R is dominant over G and B
                val isRedDominant = (avgV >= 136.0 && avgU <= 128.0 && (avgV - avgU >= 14.0)) ||
                        (r >= 60.0 && r > g * 1.12 && r > b * 1.30)

                val rawFingerDetected = isLightAdequate && isRedDominant

                // Update debounced confidence counter
                if (rawFingerDetected) {
                    if (detectedConfidence < maxConfidence) {
                        detectedConfidence++
                    }
                } else {
                    if (detectedConfidence > 0) {
                        detectedConfidence--
                    }
                }

                val isFingerPresent = detectedConfidence >= confidenceThreshold

                // Determine diagnostic status for user guidance
                val status = when {
                    isFingerPresent -> FingerDetectionStatus.DETECTED
                    avgY < 18.0 -> FingerDetectionStatus.TOO_DARK
                    avgY > 252.0 -> FingerDetectionStatus.TOO_BRIGHT
                    !isRedDominant -> FingerDetectionStatus.COVER_LENS
                    else -> FingerDetectionStatus.NONE
                }

                // In biomedical smartphone PPG:
                // Hemoglobin has a strong absorption peak in green wavelengths (~540nm).
                // Blood pulse wave surges in systole -> absorption increases -> transmitted green light drops.
                // Therefore, optical absorption = (255.0 - g) directly produces physiological systolic pulse peaks!
                val pulsatileSignal = (255.0 - g)

                onPPGUpdate(pulsatileSignal, isFingerPresent, status)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
