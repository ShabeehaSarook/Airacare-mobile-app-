package com.airacare.animalsafety

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class FirestoreDetectionRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun saveTestDetection(onResult: (Boolean, String) -> Unit) {
        val testRecord = mapOf(
            "label" to "firebase_test",
            "confidence" to 1.0,
            "riskLevel" to "TEST",
            "source" to "manual_test_button",
            "detectedAtEpochMillis" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp()
        )
        firestore.collection(COLLECTION_DETECTIONS)
            .add(testRecord)
            .addOnSuccessListener { document ->
                val message = "Firebase test saved id=${document.id}"
                Log.i(TAG, message)
                onResult(true, message)
            }
            .addOnFailureListener { exception ->
                val message = exception.message ?: exception.javaClass.simpleName
                Log.e(TAG, "Firebase test failed", exception)
                onResult(false, message)
            }
    }

    fun saveDetections(records: List<DetectionRecord>) {
        if (records.isEmpty()) return
        records.forEach { record ->
            Log.i(TAG, record.firestoreWriteSummary())
            record.nullAvailabilityNotes().forEach { note -> Log.i(TAG, note) }
            firestore.collection(COLLECTION_DETECTIONS)
                .add(record.toFirestoreMap())
                .addOnSuccessListener { document ->
                    Log.i(TAG, "Detection saved to Firestore id=${document.id} label=${record.label}")
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Could not save detection to Firestore label=${record.label}", exception)
                }
        }
    }

    private fun DetectionRecord.toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "label" to label,
            "confidence" to confidence.toDouble(),
            "riskLevel" to riskLevel.name,
            "riskReason" to riskReason,
            "distanceMeters" to distanceMeters?.toDouble(),
            "distanceSource" to distanceSource.name,
            "distanceQuality" to distanceQuality?.toDouble(),
            "closingSpeedMetersPerSecond" to closingSpeedMetersPerSecond?.toDouble(),
            "ttcSeconds" to ttcSeconds?.toDouble(),
            "vehicleSpeedMetersPerSecond" to vehicleSpeedMetersPerSecond?.toDouble(),
            "vehicleSpeedKmh" to vehicleSpeedKmh?.toDouble(),
            "vehicleSpeedSource" to vehicleSpeedSource,
            "latitude" to latitude,
            "longitude" to longitude,
            "bearingDegrees" to bearingDegrees?.toDouble(),
            "horizontalAccuracyMeters" to horizontalAccuracyMeters?.toDouble(),
            "bearingAccuracyDegrees" to bearingAccuracyDegrees?.toDouble(),
            "movementDirection" to movementDirection.name,
            "pathZone" to pathZone.name,
            "predictedPathCrossing" to predictedPathCrossing,
            "predictedPathCrossingSeconds" to predictedPathCrossingSeconds?.toDouble(),
            "predictedPathReason" to predictedPathReason,
            "warningLevel" to warningLevel.name,
            "warningTriggered" to warningTriggered,
            "audioTriggered" to audioTriggered,
            "vibrationTriggered" to vibrationTriggered,
            "warningMessage" to warningMessage,
            "trackId" to trackId,
            "pipelineFrame" to pipelineFrame,
            "cameraSource" to cameraSource,
            "imageWidth" to imageWidth,
            "imageHeight" to imageHeight,
            "rotationDegrees" to rotationDegrees,
            "boundingBox" to mapOf(
                "left" to boxLeft.toDouble(),
                "top" to boxTop.toDouble(),
                "right" to boxRight.toDouble(),
                "bottom" to boxBottom.toDouble()
            ),
            "visibilityState" to visibilityState.name,
            "visibilityQuality" to visibilityQuality.toDouble(),
            "enhancementEnabled" to enhancementEnabled,
            "imagePath" to imagePath,
            "syncStatus" to "FIRESTORE_SUBMITTED",
            "detectedAtEpochMillis" to detectedAtEpochMillis,
            "detectedAtElapsedMillis" to detectedAtElapsedMillis,
            "createdAt" to FieldValue.serverTimestamp()
        )
    }

    private fun DetectionRecord.firestoreWriteSummary(): String {
        return "FirestoreDetection: label=$label confidence=${fmt(confidence)} risk=$riskLevel distance=${fmt(distanceMeters)} vehicleSpeedKmh=${fmt(vehicleSpeedKmh)} lat=${latitude ?: "N/A"} lng=${longitude ?: "N/A"} bearing=${fmt(bearingDegrees)} warning=$warningLevel camera=$cameraSource track=${trackId ?: "N/A"}"
    }

    private fun DetectionRecord.nullAvailabilityNotes(): List<String> {
        val notes = mutableListOf<String>()
        if (latitude == null || longitude == null) {
            notes.add("FirestoreDetection: location unavailable for label=$label source=$vehicleSpeedSource; latitude/longitude remain null")
        }
        if (bearingDegrees == null) {
            notes.add("FirestoreDetection: bearing unavailable for label=$label source=$vehicleSpeedSource; Android Location had no fresh bearing or derived bearing")
        }
        if (bearingAccuracyDegrees == null) {
            notes.add("FirestoreDetection: bearing accuracy unavailable for label=$label; provider did not report bearing accuracy")
        }
        if (vehicleSpeedKmh == null) {
            notes.add("FirestoreDetection: vehicle speed unavailable for label=$label source=$vehicleSpeedSource")
        }
        if (ttcSeconds == null) {
            notes.add("FirestoreDetection: TTC unavailable for label=$label; closing speed or distance did not support TTC")
        }
        return notes
    }

    private fun fmt(value: Float?): String {
        return value?.let { String.format("%.2f", it) } ?: "N/A"
    }

    companion object {
        private const val TAG = "FirestoreDetectionRepo"
        private const val COLLECTION_DETECTIONS = "detections"
    }
}
