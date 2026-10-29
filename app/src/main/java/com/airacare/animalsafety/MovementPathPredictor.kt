package com.airacare.animalsafety

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.abs

class MovementPathPredictor {
    private val histories = mutableMapOf<Int, ArrayDeque<PathSample>>()
    private val lastSeen = mutableMapOf<Int, Long>()

    fun apply(detections: List<AnimalDetection>): List<AnimalDetection> {
        val now = SystemClock.elapsedRealtime()
        val result = detections.map { detection ->
            val trackId = detection.trackId
            if (trackId == null) {
                detection.copy(predictedPathReason = "track_unavailable")
            } else {
                val prediction = update(trackId, detection, now)
                detection.copy(
                    movementDirection = prediction.direction,
                    predictedPathCrossing = prediction.crossesVehiclePath,
                    predictedPathCrossingSeconds = prediction.crossingSeconds,
                    predictedPathReason = prediction.reason
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
            Log.d(TAG, "Removed path prediction history for stale track=$trackId")
        }
    }

    private fun update(trackId: Int, detection: AnimalDetection, now: Long): PathPrediction {
        val frameWidth = detection.imageWidth.coerceAtLeast(1).toFloat()
        val frameHeight = detection.imageHeight.coerceAtLeast(1).toFloat()
        val sample = PathSample(
            timestampMillis = now,
            centerX = detection.boundingBox.centerX / frameWidth,
            centerY = detection.boundingBox.centerY / frameHeight
        )
        val history = histories.getOrPut(trackId) { ArrayDeque() }
        if (history.size >= HISTORY_LIMIT) history.removeFirst()
        history.addLast(sample)
        lastSeen[trackId] = now

        if (history.size < MIN_SAMPLES) {
            return PathPrediction(MovementDirection.UNKNOWN, false, null, "insufficient_tracking_history")
        }

        val first = history.first()
        val last = history.last()
        val dtSeconds = (last.timestampMillis - first.timestampMillis) / 1000f
        if (dtSeconds <= 0f) {
            return PathPrediction(MovementDirection.UNKNOWN, false, null, "invalid_tracking_timestamp")
        }

        val vx = (last.centerX - first.centerX) / dtSeconds
        val vy = (last.centerY - first.centerY) / dtSeconds
        if (abs(vx) > MAX_NORMALIZED_SPEED_PER_SECOND || abs(vy) > MAX_NORMALIZED_SPEED_PER_SECOND) {
            return PathPrediction(MovementDirection.UNKNOWN, false, null, "path_motion_outlier")
        }

        val lane = vehiclePathFor(last.centerY)
        val movingTowardLane = when {
            last.centerX < lane.left -> vx > MIN_LATERAL_SPEED_PER_SECOND
            last.centerX > lane.right -> vx < -MIN_LATERAL_SPEED_PER_SECOND
            else -> false
        }
        val movingAwayFromLane = when {
            last.centerX < lane.left -> vx < -MIN_LATERAL_SPEED_PER_SECOND
            last.centerX > lane.right -> vx > MIN_LATERAL_SPEED_PER_SECOND
            else -> false
        }
        val lateralDirection = when {
            abs(vx) < MIN_LATERAL_SPEED_PER_SECOND -> MovementDirection.STATIONARY
            movingTowardLane -> MovementDirection.TOWARD_LANE
            movingAwayFromLane -> MovementDirection.AWAY_FROM_LANE
            vx < 0f -> MovementDirection.LEFT
            else -> MovementDirection.RIGHT
        }

        val crossingSeconds = secondsUntilVehiclePath(last.centerX, vx, lane)
        val vehicleApproaching = (detection.vehicleSpeedMetersPerSecond ?: 0f) >= MIN_APPROACH_SPEED_MPS ||
            (detection.closingSpeedMetersPerSecond ?: 0f) >= MIN_APPROACH_SPEED_MPS
        val crosses = movingTowardLane &&
            crossingSeconds != null &&
            crossingSeconds <= PREDICTION_HORIZON_SECONDS &&
            vehicleApproaching &&
            detection.pathZone != PathZone.IN_PATH

        val reason = when {
            crosses -> "animal_path_intersects_vehicle_path"
            !vehicleApproaching -> "vehicle_not_approaching"
            !movingTowardLane -> "animal_not_moving_toward_lane"
            crossingSeconds == null -> "no_lane_intersection_predicted"
            crossingSeconds > PREDICTION_HORIZON_SECONDS -> "intersection_beyond_horizon"
            else -> "no_intersection"
        }

        Log.d(
            TAG,
            "PATH PRED track=$trackId class=${detection.label} zone=${detection.pathZone} cx=${fmt(last.centerX)} cy=${fmt(last.centerY)} vx=${fmt(vx)} vy=${fmt(vy)} lane=${fmt(lane.left)}..${fmt(lane.right)} direction=$lateralDirection crossing=${fmt(crossingSeconds)} crosses=$crosses reason=$reason"
        )
        return PathPrediction(lateralDirection, crosses, crossingSeconds, reason)
    }

    private fun vehiclePathFor(y: Float): LaneBand {
        val width = BASE_LANE_HALF_WIDTH + y.coerceIn(0f, 1f) * LANE_PERSPECTIVE_GAIN
        return LaneBand(
            left = (0.5f - width).coerceIn(0f, 1f),
            right = (0.5f + width).coerceIn(0f, 1f)
        )
    }

    private fun secondsUntilVehiclePath(centerX: Float, vx: Float, lane: LaneBand): Float? {
        if (abs(vx) < MIN_LATERAL_SPEED_PER_SECOND) return null
        return when {
            centerX < lane.left && vx > 0f -> (lane.left - centerX) / vx
            centerX > lane.right && vx < 0f -> (centerX - lane.right) / -vx
            centerX in lane.left..lane.right -> 0f
            else -> null
        }?.takeIf { it.isFinite() && it >= 0f }
    }

    private fun cleanupStale(now: Long) {
        val staleIds = lastSeen.filterValues { now - it > STALE_HISTORY_MILLIS }.keys
        removeTracks(staleIds)
    }

    private fun fmt(value: Float?): String {
        return value?.let { String.format("%.2f", it) } ?: "N/A"
    }

    private data class PathSample(
        val timestampMillis: Long,
        val centerX: Float,
        val centerY: Float
    )

    private data class LaneBand(
        val left: Float,
        val right: Float
    )

    private data class PathPrediction(
        val direction: MovementDirection,
        val crossesVehiclePath: Boolean,
        val crossingSeconds: Float?,
        val reason: String
    )

    companion object {
        private const val TAG = "MovementPathPredictor"
        private const val HISTORY_LIMIT = 6
        private const val MIN_SAMPLES = 3
        private const val STALE_HISTORY_MILLIS = 4_000L
        private const val MIN_LATERAL_SPEED_PER_SECOND = 0.035f
        private const val MAX_NORMALIZED_SPEED_PER_SECOND = 0.90f
        private const val PREDICTION_HORIZON_SECONDS = 3.0f
        private const val MIN_APPROACH_SPEED_MPS = 1.5f
        private const val BASE_LANE_HALF_WIDTH = 0.10f
        private const val LANE_PERSPECTIVE_GAIN = 0.18f
    }
}
