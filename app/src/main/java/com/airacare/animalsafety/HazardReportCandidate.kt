package com.airacare.animalsafety

import android.os.SystemClock

data class HazardReportCandidate(
    val trackId: Int?,
    val label: String,
    val confidence: Float,
    val riskLevel: RiskLevel,
    val riskReason: String,
    val pathZone: PathZone,
    val distanceMeters: Float?,
    val ttcSeconds: Float?,
    val vehicleSpeedKmh: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val bearingDegrees: Float?,
    val horizontalAccuracyMeters: Float?,
    val createdAtElapsedMillis: Long
) {
    val hasUsableLocation: Boolean
        get() = latitude != null && longitude != null

    companion object {
        fun fromDetection(detection: AnimalDetection): HazardReportCandidate {
            return HazardReportCandidate(
                trackId = detection.trackId,
                label = detection.label,
                confidence = detection.confidence,
                riskLevel = detection.riskLevel,
                riskReason = detection.riskReason,
                pathZone = detection.pathZone,
                distanceMeters = detection.distanceMeters,
                ttcSeconds = detection.ttcSeconds,
                vehicleSpeedKmh = detection.vehicleSpeedKmh,
                latitude = detection.gpsLatitude,
                longitude = detection.gpsLongitude,
                bearingDegrees = detection.gpsBearingDegrees,
                horizontalAccuracyMeters = detection.gpsHorizontalAccuracyMeters,
                createdAtElapsedMillis = SystemClock.elapsedRealtime()
            )
        }
    }
}
