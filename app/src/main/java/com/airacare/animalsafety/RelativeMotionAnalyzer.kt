package com.airacare.animalsafety

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.abs

class RelativeMotionAnalyzer {
    private val histories = mutableMapOf<Int, ArrayDeque<MotionSample>>()
    private val lastSeen = mutableMapOf<Int, Long>()
    private val lastSource = mutableMapOf<Int, DistanceSource>()

    fun apply(detections: List<AnimalDetection>): List<AnimalDetection> {
        val now = SystemClock.elapsedRealtime()
        val result = detections.map { detection ->
            val trackId = detection.trackId
            if (trackId == null) {
                detection.copy(closingSpeedMetersPerSecond = null, ttcSeconds = null)
            } else {
                val estimate = update(trackId, detection, now)
                detection.copy(
                    closingSpeedMetersPerSecond = estimate.displayClosingSpeedMetersPerSecond,
                    ttcSeconds = estimate.ttcSeconds
                )
            }
        }
        cleanupStale(now)
        return result
    }

    fun removeTracks(trackIds: Set<Int>) {
        trackIds.forEach { trackId ->
            histories.remove(trackId)
            lastSeen.remove(trackId)
            lastSource.remove(trackId)
            Log.d(TAG, "Removed motion/TTC history for stale track=$trackId")
        }
    }


    fun historyCount(trackId: Int?): Int {
        return trackId?.let { histories[it]?.size } ?: 0
    }
    private fun update(trackId: Int, detection: AnimalDetection, timestampMillis: Long): MotionEstimate {
        val distanceMeters = detection.distanceMeters
        val distanceSource = detection.distanceSource
        val previousSource = lastSource[trackId]
        if (previousSource != null && previousSource != distanceSource) {
            histories.remove(trackId)
            Log.i(TAG, "Distance source changed for track=$trackId from=$previousSource to=$distanceSource; reset motion history")
        }
        lastSource[trackId] = distanceSource
        if (distanceMeters == null || distanceMeters <= 0f) {
            logMotion(
                trackId = trackId,
                timestampMillis = timestampMillis,
                distanceMeters = distanceMeters,
                previousDistanceMeters = null,
                dtSeconds = null,
                rawClosingSpeed = null,
                smoothedClosingSpeed = null,
                sampleCount = 0,
                thresholdMet = false,
                ttcSeconds = null,
                ttcReason = "distance_unavailable"
            )
            return MotionEstimate()
        }

        val samples = histories.getOrPut(trackId) { ArrayDeque() }
        val previous = samples.lastOrNull()
        val previousDistance = previous?.distanceMeters
        var dtSeconds: Float? = null
        var rawClosingSpeed: Float? = null
        val vehicleFallbackClosingSpeed = vehicleClosingFallback(detection)

        if (previous != null) {
            dtSeconds = (timestampMillis - previous.timestampMillis) / 1000f
            if (dtSeconds <= 0f) {
                logMotion(
                    trackId = trackId,
                    timestampMillis = timestampMillis,
                    distanceMeters = distanceMeters,
                    previousDistanceMeters = previousDistance,
                    dtSeconds = dtSeconds,
                    rawClosingSpeed = null,
                    smoothedClosingSpeed = null,
                    sampleCount = samples.size,
                    thresholdMet = false,
                    ttcSeconds = null,
                    ttcReason = "invalid_timestamp"
                )
                return MotionEstimate(sampleCount = samples.size)
            }

            rawClosingSpeed = (previous.distanceMeters - distanceMeters) / dtSeconds
            if (abs(rawClosingSpeed) > MAX_REASONABLE_DISTANCE_RATE_MPS) {
                logMotion(
                    trackId = trackId,
                    timestampMillis = timestampMillis,
                    distanceMeters = distanceMeters,
                    previousDistanceMeters = previousDistance,
                    dtSeconds = dtSeconds,
                    rawClosingSpeed = rawClosingSpeed,
                    smoothedClosingSpeed = null,
                    sampleCount = samples.size,
                    thresholdMet = false,
                    ttcSeconds = null,
                    ttcReason = "outlier_rejected"
                )
                return MotionEstimate(rawClosingSpeedMetersPerSecond = rawClosingSpeed, sampleCount = samples.size, outlierRejected = true)
            }
        }

        if (samples.size >= RELATIVE_HISTORY_LIMIT) {
            samples.removeFirst()
        }
        samples.addLast(MotionSample(timestampMillis, distanceMeters))
        lastSeen[trackId] = timestampMillis

        if (samples.size < MIN_MOTION_SAMPLES) {
            val fallbackTtcSeconds = ttcFromFallback(distanceMeters, vehicleFallbackClosingSpeed)
            logMotion(
                trackId = trackId,
                timestampMillis = timestampMillis,
                distanceMeters = distanceMeters,
                previousDistanceMeters = previousDistance,
                dtSeconds = dtSeconds,
                rawClosingSpeed = rawClosingSpeed,
                smoothedClosingSpeed = vehicleFallbackClosingSpeed,
                sampleCount = samples.size,
                thresholdMet = vehicleFallbackClosingSpeed != null,
                ttcSeconds = fallbackTtcSeconds,
                ttcReason = if (fallbackTtcSeconds != null) "vehicle_speed_fallback_insufficient_history" else "insufficient_history"
            )
            return MotionEstimate(
                displayClosingSpeedMetersPerSecond = vehicleFallbackClosingSpeed,
                rawClosingSpeedMetersPerSecond = rawClosingSpeed,
                ttcSeconds = fallbackTtcSeconds,
                sampleCount = samples.size
            )
        }

        val smoothedClosingSpeed = closingSpeedFromTrend(samples)
        val effectiveClosingSpeed = when {
            smoothedClosingSpeed > CLOSING_THRESHOLD_MPS -> smoothedClosingSpeed
            vehicleFallbackClosingSpeed != null && smoothedClosingSpeed > -OPENING_SUPPRESSES_FALLBACK_MPS -> vehicleFallbackClosingSpeed
            else -> smoothedClosingSpeed
        }
        val thresholdMet = effectiveClosingSpeed > CLOSING_THRESHOLD_MPS
        val displayClosingSpeed = effectiveClosingSpeed
        val ttcSeconds = if (thresholdMet && distanceMeters > 0f && effectiveClosingSpeed > MIN_TTC_CLOSING_MPS) {
            distanceMeters / effectiveClosingSpeed
        } else {
            null
        }
        val ttcReason = if (ttcSeconds != null && vehicleFallbackClosingSpeed != null && smoothedClosingSpeed <= CLOSING_THRESHOLD_MPS) {
            "vehicle_speed_fallback"
        } else {
            ttcInvalidReason(distanceMeters, effectiveClosingSpeed, thresholdMet, ttcSeconds)
        }

        logMotion(
            trackId = trackId,
            timestampMillis = timestampMillis,
            distanceMeters = distanceMeters,
            previousDistanceMeters = previousDistance,
            dtSeconds = dtSeconds,
            rawClosingSpeed = rawClosingSpeed,
            smoothedClosingSpeed = effectiveClosingSpeed,
            sampleCount = samples.size,
            thresholdMet = thresholdMet,
            ttcSeconds = ttcSeconds,
            ttcReason = ttcReason
        )

        return MotionEstimate(
            displayClosingSpeedMetersPerSecond = displayClosingSpeed,
            rawClosingSpeedMetersPerSecond = rawClosingSpeed,
            ttcSeconds = ttcSeconds,
            sampleCount = samples.size
        )
    }

    private fun vehicleClosingFallback(detection: AnimalDetection): Float? {
        val vehicleSpeed = detection.vehicleSpeedMetersPerSecond ?: return null
        if (vehicleSpeed < MIN_VEHICLE_SPEED_FOR_FALLBACK_MPS) return null
        val zone = estimatePathZone(detection)
        if (zone == PathZone.OUTSIDE_PATH || zone == PathZone.UNKNOWN) return null
        val accuracy = detection.gpsHorizontalAccuracyMeters
        if (accuracy != null && accuracy > MAX_GPS_ACCURACY_FOR_TTC_METERS) return null
        return vehicleSpeed.coerceAtMost(MAX_FALLBACK_CLOSING_MPS)
    }

    private fun ttcFromFallback(distanceMeters: Float, closingSpeedMetersPerSecond: Float?): Float? {
        val closing = closingSpeedMetersPerSecond ?: return null
        if (distanceMeters <= 0f || closing <= MIN_TTC_CLOSING_MPS) return null
        return distanceMeters / closing
    }

    private fun estimatePathZone(detection: AnimalDetection): PathZone {
        val width = detection.imageWidth.coerceAtLeast(1).toFloat()
        val height = detection.imageHeight.coerceAtLeast(1).toFloat()
        val xRatio = detection.boundingBox.centerX / width
        val yRatio = detection.boundingBox.centerY / height
        return when {
            yRatio >= 0.45f && xRatio in 0.35f..0.65f -> PathZone.IN_PATH
            yRatio >= 0.35f && xRatio in 0.18f..0.82f -> PathZone.NEAR_PATH
            else -> PathZone.OUTSIDE_PATH
        }
    }

    private fun cleanupStale(nowMillis: Long) {
        val staleIds = lastSeen.filterValues { nowMillis - it > STALE_MOTION_MILLIS }.keys
        removeTracks(staleIds)
    }

    private fun closingSpeedFromTrend(samples: ArrayDeque<MotionSample>): Float {
        val firstTime = samples.first().timestampMillis
        val times = samples.map { (it.timestampMillis - firstTime) / 1000f }
        val distances = samples.map { it.distanceMeters }
        val meanTime = times.average().toFloat()
        val meanDistance = distances.average().toFloat()
        val denominator = times.sumOf { time ->
            val delta = time - meanTime
            (delta * delta).toDouble()
        }.toFloat()
        if (denominator <= 0f) return 0f

        val slopeDistancePerSecond = times.zip(distances).sumOf { (time, distance) ->
            ((time - meanTime) * (distance - meanDistance)).toDouble()
        }.toFloat() / denominator
        return -slopeDistancePerSecond
    }

    private fun ttcInvalidReason(
        distanceMeters: Float,
        smoothedClosingSpeed: Float,
        thresholdMet: Boolean,
        ttcSeconds: Float?
    ): String {
        return when {
            ttcSeconds != null -> "valid"
            distanceMeters <= 0f -> "invalid_distance"
            !thresholdMet && smoothedClosingSpeed < -CLOSING_THRESHOLD_MPS -> "object_opening"
            !thresholdMet -> "closing_below_threshold"
            smoothedClosingSpeed <= MIN_TTC_CLOSING_MPS -> "closing_too_small"
            else -> "invalid"
        }
    }

    private fun logMotion(
        trackId: Int,
        timestampMillis: Long,
        distanceMeters: Float?,
        previousDistanceMeters: Float?,
        dtSeconds: Float?,
        rawClosingSpeed: Float?,
        smoothedClosingSpeed: Float?,
        sampleCount: Int,
        thresholdMet: Boolean,
        ttcSeconds: Float?,
        ttcReason: String
    ) {
        Log.d(
            TAG,
            "PYTHON-EQUIVALENT VALUES track=$trackId timestamp=$timestampMillis distance=${fmt(distanceMeters)} previous=${fmt(previousDistanceMeters)} dt=${fmt(dtSeconds)} history_sample_count=$sampleCount raw_closing_speed=${fmt(rawClosingSpeed)} smoothed_closing_speed=${fmt(smoothedClosingSpeed)} closingThreshold=${fmt(CLOSING_THRESHOLD_MPS)} thresholdMet=$thresholdMet ttc=${fmt(ttcSeconds)} ttcReason=$ttcReason"
        )
    }

    private fun fmt(value: Float?): String {
        return if (value == null) "N/A" else String.format("%.2f", value)
    }

    private data class MotionSample(
        val timestampMillis: Long,
        val distanceMeters: Float
    )

    private data class MotionEstimate(
        val displayClosingSpeedMetersPerSecond: Float? = null,
        val rawClosingSpeedMetersPerSecond: Float? = null,
        val ttcSeconds: Float? = null,
        val sampleCount: Int = 0,
        val outlierRejected: Boolean = false
    )

    companion object {
        private const val TAG = "RelativeMotion"
        private const val RELATIVE_HISTORY_LIMIT = 7
        private const val MIN_MOTION_SAMPLES = 3
        private const val CLOSING_THRESHOLD_MPS = 0.5f
        private const val MIN_TTC_CLOSING_MPS = 0.0001f
        private const val MIN_VEHICLE_SPEED_FOR_FALLBACK_MPS = 1.5f
        private const val MAX_FALLBACK_CLOSING_MPS = 28.0f
        private const val OPENING_SUPPRESSES_FALLBACK_MPS = 1.0f
        private const val MAX_GPS_ACCURACY_FOR_TTC_METERS = 35.0f
        private const val MAX_REASONABLE_DISTANCE_RATE_MPS = 35.0f
        private const val STALE_MOTION_MILLIS = 15_000L
    }
}





