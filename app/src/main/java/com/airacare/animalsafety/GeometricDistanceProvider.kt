package com.airacare.animalsafety

import android.util.Log
import kotlin.math.abs

class GeometricDistanceProvider(
    private val calibration: DistanceCalibration,
    override val source: DistanceSource,
    override val quality: Float
) : DistanceProvider {
    override val cameraLabel: String = calibration.cameraSource

    override fun estimate(detection: AnimalDetection): DistanceMeasurement? {
        val knownHeight = DistanceEstimator.KNOWN_OBJECT_HEIGHTS_METERS[detection.label.lowercase()]
        if (knownHeight == null || knownHeight <= 0f) {
            Log.d(TAG, "Distance unavailable reason=unknown_physical_height class=${detection.label} source=$source")
            return null
        }
        val bboxHeight = detection.boundingBox.height
        val boxAreaRatio = detection.boundingBox.area / (detection.imageWidth * detection.imageHeight).toFloat().coerceAtLeast(1f)
        val boxHeightRatio = bboxHeight / detection.imageHeight.coerceAtLeast(1).toFloat()
        if ((boxAreaRatio > MAX_UNTRUSTED_BOX_AREA_RATIO || boxHeightRatio > MAX_UNTRUSTED_BOX_HEIGHT_RATIO) && detection.confidence < HIGH_CONFIDENCE_FOR_LARGE_BOX) {
            Log.d(TAG, "Distance unavailable reason=untrusted_large_box class=${detection.label} confidence=${String.format("%.2f", detection.confidence)} areaRatio=${String.format("%.2f", boxAreaRatio)} heightRatio=${String.format("%.2f", boxHeightRatio)} source=$source")
            return null
        }
        if (bboxHeight < DistanceEstimator.MIN_BBOX_HEIGHT_PIXELS) {
            Log.d(TAG, "Distance unavailable reason=bbox_too_small class=${detection.label} bboxHeight=$bboxHeight source=$source")
            return null
        }
        val focal = focalLengthPixelsFor(detection) ?: return null
        val distance = (knownHeight * focal) / bboxHeight
        if (!distance.isFinite() || distance <= 0f) return null
        val targetQuality = distanceQuality(detection, bboxHeight)
        Log.d(
            TAG,
            "DISTANCE track=${detection.trackId} class=${detection.label} distance=${String.format("%.2f", distance)} source=$source quality=$targetQuality bboxHeight=${String.format("%.1f", bboxHeight)} physicalHeight=$knownHeight focalPx=${String.format("%.1f", focal)} distanceQualityReason=${if (targetQuality < quality) "small_or_far_bbox" else "normal"}"
        )
        return DistanceMeasurement(distance, source, targetQuality, "object_size_geometric")
    }

    private fun distanceQuality(detection: AnimalDetection, bboxHeight: Float): Float {
        val ratio = bboxHeight / detection.imageHeight.coerceAtLeast(1).toFloat()
        return when {
            ratio < 0.08f -> quality * 0.45f
            ratio < 0.14f -> quality * 0.70f
            else -> quality
        }.coerceIn(0.15f, quality)
    }

    override fun focalLengthPixelsFor(detection: AnimalDetection): Float? {
        val runtimeWidth = detection.imageWidth
        val runtimeHeight = detection.imageHeight
        if (runtimeWidth <= 0 || runtimeHeight <= 0) return null
        if (calibration.calibrationWidth == runtimeWidth && calibration.calibrationHeight == runtimeHeight) {
            return calibration.focalLengthPixels
        }

        val heightScale = runtimeHeight / calibration.calibrationHeight.toFloat()
        val widthScale = runtimeWidth / calibration.calibrationWidth.toFloat()
        val scaledFocalLength = calibration.focalLengthPixels * heightScale
        val calibrationAspect = calibration.calibrationWidth / calibration.calibrationHeight.toFloat()
        val runtimeAspect = runtimeWidth / runtimeHeight.toFloat()
        val aspectDelta = abs(calibrationAspect - runtimeAspect) / calibrationAspect
        when {
            aspectDelta > 0.05f -> Log.w(TAG, "Camera aspect ratio differs from calibration; distance accuracy may be reduced. source=$source")
            abs(widthScale - heightScale) > 0.05f -> Log.w(TAG, "Camera resolution scaling is not uniform; distance accuracy may be reduced. source=$source")
            else -> Log.d(TAG, "Runtime resolution differs from calibration; focal length was scaled. source=$source")
        }
        return scaledFocalLength
    }

    companion object {
        private const val TAG = "GeometricDistanceProvider"
        private const val MAX_UNTRUSTED_BOX_AREA_RATIO = 0.70f
        private const val MAX_UNTRUSTED_BOX_HEIGHT_RATIO = 0.92f
        private const val HIGH_CONFIDENCE_FOR_LARGE_BOX = 0.80f
    }
}


