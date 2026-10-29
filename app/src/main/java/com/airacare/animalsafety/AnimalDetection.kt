package com.airacare.animalsafety

data class AnimalDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: DetectionBox,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val trackId: Int? = null,
    val trackAgeFrames: Int = 0,
    val distanceMeters: Float? = null,
    val distanceSource: DistanceSource = DistanceSource.UNAVAILABLE,
    val distanceQuality: Float? = null,
    val closingSpeedMetersPerSecond: Float? = null,
    val ttcSeconds: Float? = null,
    val vehicleSpeedMetersPerSecond: Float? = null,
    val vehicleSpeedKmh: Float? = null,
    val vehicleSpeedSource: String = "none",
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsBearingDegrees: Float? = null,
    val gpsHorizontalAccuracyMeters: Float? = null,
    val gpsBearingAccuracyDegrees: Float? = null,
    val riskLevel: RiskLevel = RiskLevel.UNKNOWN,
    val riskReason: String = "",
    val pathZone: PathZone = PathZone.UNKNOWN,
    val movementDirection: MovementDirection = MovementDirection.UNKNOWN,
    val predictedPathCrossing: Boolean = false,
    val predictedPathCrossingSeconds: Float? = null,
    val predictedPathReason: String = "",
    val visibilityState: VisibilityState = VisibilityState.NORMAL,
    val visibilityQuality: Float = 1f,
    val enhancementEnabled: Boolean = false
)

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    val area: Float
        get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
}

enum class RiskLevel(val order: Int) {
    UNKNOWN(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3)
}

enum class PathZone {
    IN_PATH,
    NEAR_PATH,
    OUTSIDE_PATH,
    UNKNOWN
}

enum class MovementDirection {
    LEFT,
    RIGHT,
    TOWARD_LANE,
    AWAY_FROM_LANE,
    STATIONARY,
    UNKNOWN
}





