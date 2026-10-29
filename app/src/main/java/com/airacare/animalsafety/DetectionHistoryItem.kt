package com.airacare.animalsafety

data class DetectionHistoryItem(
    val id: Long,
    val detectedAtEpochMillis: Long,
    val label: String,
    val confidence: Float,
    val riskLevel: String,
    val distanceMeters: Float?,
    val vehicleSpeedKmh: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val movementDirection: String,
    val pathZone: String,
    val warningLevel: String,
    val cameraSource: String,
    val syncStatus: String
)
