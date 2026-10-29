package com.airacare.animalsafety

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.abs

class DistanceEstimator(
    private var provider: DistanceProvider?
) {
    constructor(calibration: DistanceCalibration?) : this(calibration?.let { calibrationToProvider(it) })

    private val histories = mutableMapOf<Int, ArrayDeque<Float>>()
    private val lastSeen = mutableMapOf<Int, Long>()
    private var missingDistanceSourceLogged = false
    private var activeSource: DistanceSource = provider?.source ?: DistanceSource.UNAVAILABLE

    fun setCalibration(nextCalibration: DistanceCalibration?) {
        setDistanceProvider(nextCalibration?.let { calibrationToProvider(it) })
    }

    fun setDistanceProvider(nextProvider: DistanceProvider?) {
        val nextSource = nextProvider?.source ?: DistanceSource.UNAVAILABLE
        if (nextSource != activeSource) {
            histories.clear()
            lastSeen.clear()
            Log.i(TAG, "Distance source changed from=$activeSource to=$nextSource; cleared distance/motion smoothing history")
        }
        provider = nextProvider
        activeSource = nextSource
        missingDistanceSourceLogged = false
        if (nextProvider != null) {
            Log.i(TAG, "DIST SOURCE: ${nextProvider.source} camera=${nextProvider.cameraLabel} quality=${nextProvider.quality}")
        } else {
            Log.w(TAG, "DIST SOURCE: NONE")
        }
    }

    fun apply(detections: List<AnimalDetection>): List<AnimalDetection> {
        val activeProvider = provider
        if (activeProvider == null) {
            if (!missingDistanceSourceLogged) {
                Log.w(TAG, "No distance provider available; DIST: N/A.")
                missingDistanceSourceLogged = true
            }
            cleanupStale()
            return detections.map { it.copy(distanceMeters = null, distanceSource = DistanceSource.UNAVAILABLE, distanceQuality = null) }
        }

        val result = detections.map { detection ->
            val measurement = activeProvider.estimate(detection)
            if (measurement == null || detection.trackId == null) {
                detection.copy(
                    distanceMeters = measurement?.meters,
                    distanceSource = measurement?.source ?: activeProvider.source,
                    distanceQuality = measurement?.quality ?: activeProvider.quality
                )
            } else {
                detection.copy(
                    distanceMeters = smooth(detection.trackId, measurement.meters),
                    distanceSource = measurement.source,
                    distanceQuality = measurement.quality
                )
            }
        }
        cleanupStale()
        return result
    }

    private fun smooth(trackId: Int, distance: Float): Float {
        val history = histories.getOrPut(trackId) { ArrayDeque() }
        if (history.size >= DISTANCE_HISTORY_LIMIT) {
            history.removeFirst()
        }
        history.addLast(distance)
        lastSeen[trackId] = SystemClock.elapsedRealtime()
        val sorted = history.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private fun cleanupStale() {
        val now = SystemClock.elapsedRealtime()
        val staleIds = lastSeen.filterValues { now - it > STALE_DISTANCE_MILLIS }.keys
        staleIds.forEach { trackId ->
            histories.remove(trackId)
            lastSeen.remove(trackId)
        }
    }

    fun diagnosticValues(detection: AnimalDetection): DistanceDiagnostic {
        val activeProvider = provider
        val knownHeight = KNOWN_OBJECT_HEIGHTS_METERS[detection.label.lowercase()]
        val focalLength = activeProvider?.focalLengthPixelsFor(detection)
        val historyCount = detection.trackId?.let { histories[it]?.size } ?: 0
        return DistanceDiagnostic(
            bboxPixelHeight = detection.boundingBox.height,
            objectPhysicalHeightMeters = knownHeight,
            focalLengthPixels = focalLength,
            calibrationSource = detection.distanceSource.name,
            calibrationCamera = activeProvider?.cameraLabel ?: "N/A",
            historyCount = historyCount
        )
    }

    data class DistanceDiagnostic(
        val bboxPixelHeight: Float,
        val objectPhysicalHeightMeters: Float?,
        val focalLengthPixels: Float?,
        val calibrationSource: String,
        val calibrationCamera: String,
        val historyCount: Int
    )
    companion object {
        private const val TAG = "DistanceEstimator"
        fun calibrationToProvider(calibration: DistanceCalibration): DistanceProvider {
            val source = when (calibration.calibrationSource) {
                DistanceCalibration.SOURCE_MANUAL -> DistanceSource.MANUAL_CALIBRATION
                DistanceCalibration.SOURCE_CAMERA_METADATA -> DistanceSource.CAMERA_METADATA
                else -> DistanceSource.SAVED_VALIDATED_CALIBRATION
            }
            val quality = when (source) {
                DistanceSource.MANUAL_CALIBRATION -> 0.85f
                DistanceSource.CAMERA_METADATA -> 0.55f
                else -> 0.70f
            }
            return GeometricDistanceProvider(calibration, source, quality)
        }
        const val MIN_BBOX_HEIGHT_PIXELS = 4.0f
        private const val DISTANCE_HISTORY_LIMIT = 5
        private const val STALE_DISTANCE_MILLIS = 2_000L
        // Approximate configurable references for geometric estimates; these are not exact distance truth.
        val KNOWN_OBJECT_HEIGHTS_METERS = mapOf(
            "person" to 1.70f,
            "dog" to 0.60f,
            "cat" to 0.30f,
            "horse" to 1.60f,
            "cow" to 1.50f,
            "deer" to 1.20f,
            "goat" to 0.75f,
            "elephant" to 2.70f
        )
    }
}




