package com.airacare.animalsafety

enum class DistanceSource {
    SAVED_VALIDATED_CALIBRATION,
    CAMERA_METADATA,
    MONOCULAR_DEPTH,
    ARCORE_DEPTH,
    MANUAL_CALIBRATION,
    UNAVAILABLE
}

data class DistanceMeasurement(
    val meters: Float,
    val source: DistanceSource,
    val quality: Float,
    val detail: String = ""
)

interface DistanceProvider {
    val source: DistanceSource
    val quality: Float
    val cameraLabel: String
    fun estimate(detection: AnimalDetection): DistanceMeasurement?
    fun focalLengthPixelsFor(detection: AnimalDetection): Float?
}
