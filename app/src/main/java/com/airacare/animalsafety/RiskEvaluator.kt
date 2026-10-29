package com.airacare.animalsafety

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

class RiskEvaluator {
    private val history = mutableMapOf<Int, ArrayDeque<RiskLevel>>()
    private val current = mutableMapOf<Int, RiskLevel>()
    private val lastSeen = mutableMapOf<Int, Long>()

    fun apply(detections: List<AnimalDetection>): List<AnimalDetection> {
        val now = SystemClock.elapsedRealtime()
        val result = detections.map { detection ->
            val raw = evaluateRaw(detection)
            val trackId = detection.trackId
            val smoothedLevel = if (trackId == null) {
                raw.level
            } else {
                smooth(trackId, raw.level, now)
            }
            Log.d(TAG, "PYTHON-EQUIVALENT VALUES track=$trackId class=${detection.label} distance=${detection.distanceMeters ?: "N/A"} closing=${detection.closingSpeedMetersPerSecond ?: "N/A"} ttc=${detection.ttcSeconds ?: "N/A"} movement=${detection.movementDirection} predictedCrossing=${detection.predictedPathCrossing} predictedCrossingSeconds=${detection.predictedPathCrossingSeconds ?: "N/A"} vehicleSpeed=${detection.vehicleSpeedKmh ?: "N/A"}kmh speedSource=${detection.vehicleSpeedSource} rawRisk=${raw.level} risk=$smoothedLevel score=${raw.score} zone=${raw.zone} reason=${raw.reason}")
            detection.copy(riskLevel = smoothedLevel, riskReason = raw.reason, pathZone = raw.zone)
        }
        cleanupStale(now)
        return result
    }

    fun removeTracks(trackIds: Set<Int>) {
        trackIds.forEach { trackId ->
            history.remove(trackId)
            current.remove(trackId)
            lastSeen.remove(trackId)
            Log.d(TAG, "Removed risk history for stale track=$trackId")
        }
    }

    private fun evaluateRaw(detection: AnimalDetection): RiskResult {
        if (detection.confidence < MINIMUM_CONFIDENCE) {
            return RiskResult(RiskLevel.UNKNOWN, 0, "Detection confidence is below risk-evaluation threshold", PathZone.UNKNOWN)
        }
        if (detection.trackId != null && detection.trackAgeFrames < MIN_CONFIRMED_TRACK_FRAMES && detection.confidence < STRONG_CONFIDENCE) {
            return RiskResult(RiskLevel.UNKNOWN, 0, "Track is pending temporal confirmation", PathZone.UNKNOWN)
        }
        val distance = detection.distanceMeters ?: return RiskResult(RiskLevel.UNKNOWN, 0, "Distance is unavailable", PathZone.UNKNOWN)
        val zone = estimatePathZone(detection)
        val movementState = movementState(detection.closingSpeedMetersPerSecond)
                val vehicleSpeedMps = detection.vehicleSpeedMetersPerSecond
        var score = 0
        val reasons = mutableListOf<String>()

        when (zone) {
            PathZone.IN_PATH -> {
                score += 35
                reasons.add("object is IN_PATH")
            }
            PathZone.NEAR_PATH -> {
                score += 20
                reasons.add("object is NEAR_PATH")
            }
            PathZone.OUTSIDE_PATH -> {
                score += 5
                reasons.add("object is OUTSIDE_PATH")
            }
            PathZone.UNKNOWN -> {
                score += 10
                reasons.add("path zone is unknown")
            }
        }

        val ttc = detection.ttcSeconds
        if (ttc != null) {
            when {
                ttc <= HIGH_TTC_SECONDS -> {
                    score += 35
                    reasons.add("TTC is ${String.format("%.1f", ttc)}s")
                }
                ttc <= MEDIUM_TTC_SECONDS -> {
                    score += 22
                    reasons.add("moderate TTC ${String.format("%.1f", ttc)}s")
                }
                else -> {
                    score += 8
                    reasons.add("longer TTC ${String.format("%.1f", ttc)}s")
                }
            }
        } else {
            reasons.add("TTC unavailable")
        }

        when {
            distance <= HIGH_DISTANCE_METERS -> {
                score += 20
                reasons.add("distance is ${String.format("%.1f", distance)}m")
            }
            distance <= MEDIUM_DISTANCE_METERS -> {
                score += 10
                reasons.add("moderate distance ${String.format("%.1f", distance)}m")
            }
            else -> reasons.add("far distance ${String.format("%.1f", distance)}m")
        }


        if (vehicleSpeedMps != null) {
            when {
                vehicleSpeedMps >= FAST_VEHICLE_MPS && zone != PathZone.OUTSIDE_PATH && distance <= MEDIUM_DISTANCE_METERS -> {
                    score += 18
                    reasons.add("vehicle speed ${String.format("%.0f", vehicleSpeedMps * 3.6f)}km/h")
                }
                vehicleSpeedMps >= MOVING_VEHICLE_MPS && zone == PathZone.IN_PATH -> {
                    score += 10
                    reasons.add("moving vehicle ${String.format("%.0f", vehicleSpeedMps * 3.6f)}km/h")
                }
                vehicleSpeedMps < STATIONARY_VEHICLE_MPS -> {
                    score -= 10
                    reasons.add("vehicle nearly stopped")
                }
                else -> reasons.add("vehicle speed ${String.format("%.0f", vehicleSpeedMps * 3.6f)}km/h")
            }
        } else {
            reasons.add("vehicle speed unavailable")
        }
        val closing = detection.closingSpeedMetersPerSecond
        if (closing != null) {
            when {
                closing >= FAST_CLOSING_MPS -> {
                    score += 20
                    reasons.add("fast closing ${String.format("%.1f", closing)}m/s")
                }
                closing >= CLOSING_MPS -> {
                    score += 10
                    reasons.add("closing ${String.format("%.1f", closing)}m/s")
                }
                closing <= -CLOSING_MPS -> {
                    score -= 12
                    reasons.add("object is opening")
                }
                else -> reasons.add("relative distance is stable")
            }
        } else {
            reasons.add("closing speed unavailable")
        }

        when {
            detection.predictedPathCrossing -> {
                score += 30
                val crossingText = detection.predictedPathCrossingSeconds?.let { "${String.format("%.1f", it)}s" } ?: "soon"
                reasons.add("predicted path crossing $crossingText")
            }
            movementState != "CLOSING" && vehicleSpeedMps != null && vehicleSpeedMps < STATIONARY_VEHICLE_MPS -> {
                score = score.coerceAtMost(25)
                reasons.add("vehicle nearly stopped and object is not closing")
            }
            movementState == "UNKNOWN" -> {
                score = score.coerceAtMost(45)
                reasons.add("motion evidence unavailable")
            }
            movementState == "STABLE" -> {
                score = score.coerceAtMost(45)
                reasons.add("object is not closing")
            }
        }
        if (zone == PathZone.OUTSIDE_PATH && movementState == "OPENING") {
            score = score.coerceAtMost(25)
            reasons.add("outside path and opening")
        }

        score = score.coerceIn(0, 100)
        var level = levelFromScore(score)
        if (zone == PathZone.IN_PATH && movementState == "CLOSING" && ttc != null) {
            if (ttc <= HIGH_TTC_SECONDS) {
                level = RiskLevel.HIGH
                score = score.coerceAtLeast(80)
            } else if (ttc <= MEDIUM_TTC_SECONDS && level.order < RiskLevel.MEDIUM.order) {
                level = RiskLevel.MEDIUM
                score = score.coerceAtLeast(55)
            }
        }
        if (zone == PathZone.NEAR_PATH && movementState == "CLOSING" && distance <= HIGH_DISTANCE_METERS && level.order < RiskLevel.MEDIUM.order) {
            level = RiskLevel.MEDIUM
            score = score.coerceAtLeast(50)
        }
        if (detection.predictedPathCrossing) {
            val crossing = detection.predictedPathCrossingSeconds ?: Float.POSITIVE_INFINITY
            when {
                crossing <= HIGH_PREDICTED_CROSSING_SECONDS && distance <= MEDIUM_DISTANCE_METERS && vehicleSpeedMps != null && vehicleSpeedMps >= MOVING_VEHICLE_MPS -> {
                    level = RiskLevel.HIGH
                    score = score.coerceAtLeast(75)
                }
                level.order < RiskLevel.MEDIUM.order -> {
                    level = RiskLevel.MEDIUM
                    score = score.coerceAtLeast(55)
                }
            }
        }
        if (shouldCapHighRiskForWeakEvidence(detection, ttc, level)) {
            level = RiskLevel.MEDIUM
            score = score.coerceAtMost(69)
            reasons.add("evidence quality degraded")
        }

        return RiskResult(level, score, reasons.take(3).joinToString("; "), zone)
    }

    private fun shouldCapHighRiskForWeakEvidence(
        detection: AnimalDetection,
        ttc: Float?,
        level: RiskLevel
    ): Boolean {
        if (level != RiskLevel.HIGH) return false
        if (ttc != null && ttc <= HIGH_TTC_SECONDS && detection.confidence >= STRONG_CONFIDENCE) return false
        val weakVisibility = detection.visibilityState == VisibilityState.NIGHT ||
            detection.visibilityState == VisibilityState.BLURRY ||
            detection.visibilityState == VisibilityState.POOR_VISIBILITY ||
            detection.visibilityQuality < WEAK_VISIBILITY_QUALITY
        val weakDistance = detection.distanceQuality != null && detection.distanceQuality < WEAK_DISTANCE_QUALITY
        val weakDetection = detection.confidence < WEAK_DETECTION_CONFIDENCE
        return weakVisibility || weakDistance || weakDetection
    }

    private fun smooth(trackId: Int, rawLevel: RiskLevel, now: Long): RiskLevel {
        val previous = current[trackId] ?: RiskLevel.UNKNOWN
        val samples = history.getOrPut(trackId) { ArrayDeque() }
        if (samples.size >= RISK_HISTORY_SIZE) samples.removeFirst()
        samples.addLast(rawLevel)
        lastSeen[trackId] = now

        val smoothed = if (rawLevel.order > previous.order) {
            rawLevel
        } else {
            val grouped = samples.groupingBy { it }.eachCount()
            var candidate = grouped.maxWithOrNull(compareBy<Map.Entry<RiskLevel, Int>> { it.value }.thenBy { it.key.order })?.key ?: rawLevel
            if (previous.order - rawLevel.order >= 2) {
                val recentSafe = samples.count { it.order <= RiskLevel.LOW.order }
                if (recentSafe < 3) candidate = previous
            }
            candidate
        }
        current[trackId] = smoothed
        return smoothed
    }

    private fun cleanupStale(now: Long) {
        val staleIds = lastSeen.filterValues { now - it > STALE_RISK_MILLIS }.keys
        removeTracks(staleIds)
    }

    private fun estimatePathZone(detection: AnimalDetection): PathZone {
        if (detection.pathZone != PathZone.UNKNOWN) return detection.pathZone
        val width = detection.imageWidth.coerceAtLeast(1).toFloat()
        val height = detection.imageHeight.coerceAtLeast(1).toFloat()
        val xRatio = detection.boundingBox.centerX / width
        val yRatio = detection.boundingBox.centerY / height
        val areaRatio = detection.boundingBox.area / (width * height).coerceAtLeast(1f)
        val heightRatio = detection.boundingBox.height / height
        if ((areaRatio > MAX_UNTRUSTED_PATH_BOX_AREA_RATIO || heightRatio > MAX_UNTRUSTED_PATH_BOX_HEIGHT_RATIO) && detection.confidence < HIGH_CONFIDENCE_FOR_LARGE_PATH_BOX) {
            return PathZone.UNKNOWN
        }
        return when {
            yRatio >= 0.45f && xRatio in 0.35f..0.65f -> PathZone.IN_PATH
            yRatio >= 0.35f && xRatio in 0.18f..0.82f -> PathZone.NEAR_PATH
            else -> PathZone.OUTSIDE_PATH
        }
    }

    private fun movementState(closingSpeed: Float?): String {
        return when {
            closingSpeed == null -> "UNKNOWN"
            closingSpeed > CLOSING_MPS -> "CLOSING"
            closingSpeed < -CLOSING_MPS -> "OPENING"
            else -> "STABLE"
        }
    }

    private fun levelFromScore(score: Int): RiskLevel {
        return when {
            score >= 70 -> RiskLevel.HIGH
            score >= 30 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private data class RiskResult(
        val level: RiskLevel,
        val score: Int,
        val reason: String,
        val zone: PathZone
    )

    companion object {
        private const val TAG = "RiskEvaluator"
        private const val HIGH_TTC_SECONDS = 2.5f
        private const val MEDIUM_TTC_SECONDS = 5.0f
        private const val HIGH_PREDICTED_CROSSING_SECONDS = 1.5f
        private const val HIGH_DISTANCE_METERS = 10.0f
        private const val MEDIUM_DISTANCE_METERS = 25.0f
        private const val FAST_CLOSING_MPS = 5.0f
        private const val CLOSING_MPS = 0.5f
                private const val STATIONARY_VEHICLE_MPS = 0.5f
        private const val MOVING_VEHICLE_MPS = 2.0f
        private const val FAST_VEHICLE_MPS = 8.0f
private const val MINIMUM_CONFIDENCE = 0.30f
        private const val WEAK_DETECTION_CONFIDENCE = 0.45f
        private const val MAX_UNTRUSTED_PATH_BOX_AREA_RATIO = 0.70f
        private const val MAX_UNTRUSTED_PATH_BOX_HEIGHT_RATIO = 0.92f
        private const val HIGH_CONFIDENCE_FOR_LARGE_PATH_BOX = 0.80f
        private const val STRONG_CONFIDENCE = 0.65f
        private const val MIN_CONFIRMED_TRACK_FRAMES = 2
        private const val WEAK_VISIBILITY_QUALITY = 0.45f
        private const val WEAK_DISTANCE_QUALITY = 0.35f
        private const val RISK_HISTORY_SIZE = 5
        private const val STALE_RISK_MILLIS = 2_000L
    }
}



