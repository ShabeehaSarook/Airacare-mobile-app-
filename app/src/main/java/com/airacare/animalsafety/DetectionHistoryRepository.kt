package com.airacare.animalsafety

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionHistoryRepository(context: Context) {
    private val databaseHelper = DetectionDatabaseHelper(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun saveDetections(records: List<DetectionRecord>) {
        if (records.isEmpty()) return
        executor.execute {
            try {
                databaseHelper.writableDatabase.useTransaction { db ->
                    records.forEach { record ->
                        db.insert(DetectionDatabaseHelper.TABLE_DETECTIONS, null, record.toContentValues())
                    }
                }
                Log.d(TAG, "Saved detection records count=${records.size}")
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save detection records", exception)
            }
        }
    }

    fun recentDetections(limit: Int = DEFAULT_RECENT_LIMIT): List<DetectionHistoryItem> {
        val safeLimit = limit.coerceIn(1, MAX_RECENT_LIMIT)
        val items = mutableListOf<DetectionHistoryItem>()
        val columns = arrayOf(
            DetectionDatabaseHelper.COL_ID,
            DetectionDatabaseHelper.COL_DETECTED_AT_EPOCH_MILLIS,
            DetectionDatabaseHelper.COL_LABEL,
            DetectionDatabaseHelper.COL_CONFIDENCE,
            DetectionDatabaseHelper.COL_RISK_LEVEL,
            DetectionDatabaseHelper.COL_DISTANCE_METERS,
            DetectionDatabaseHelper.COL_VEHICLE_SPEED_KMH,
            DetectionDatabaseHelper.COL_LATITUDE,
            DetectionDatabaseHelper.COL_LONGITUDE,
            DetectionDatabaseHelper.COL_MOVEMENT_DIRECTION,
            DetectionDatabaseHelper.COL_PATH_ZONE,
            DetectionDatabaseHelper.COL_WARNING_LEVEL,
            DetectionDatabaseHelper.COL_CAMERA_SOURCE,
            DetectionDatabaseHelper.COL_SYNC_STATUS
        )
        databaseHelper.readableDatabase.query(
            DetectionDatabaseHelper.TABLE_DETECTIONS,
            columns,
            null,
            null,
            null,
            null,
            "${DetectionDatabaseHelper.COL_DETECTED_AT_EPOCH_MILLIS} DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.add(cursor.toDetectionHistoryItem())
            }
        }
        return items
    }

    fun close() {
        executor.shutdown()
        databaseHelper.close()
    }

    private fun SQLiteDatabase.useTransaction(block: (SQLiteDatabase) -> Unit) {
        beginTransaction()
        try {
            block(this)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun DetectionRecord.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(DetectionDatabaseHelper.COL_DETECTED_AT_EPOCH_MILLIS, detectedAtEpochMillis)
            put(DetectionDatabaseHelper.COL_DETECTED_AT_ELAPSED_MILLIS, detectedAtElapsedMillis)
            put(DetectionDatabaseHelper.COL_PIPELINE_FRAME, pipelineFrame)
            put(DetectionDatabaseHelper.COL_CAMERA_SOURCE, cameraSource)
            putNullable(DetectionDatabaseHelper.COL_TRACK_ID, trackId)
            put(DetectionDatabaseHelper.COL_LABEL, label)
            put(DetectionDatabaseHelper.COL_CONFIDENCE, confidence)
            put(DetectionDatabaseHelper.COL_RISK_LEVEL, riskLevel.name)
            put(DetectionDatabaseHelper.COL_RISK_REASON, riskReason)
            put(DetectionDatabaseHelper.COL_PATH_ZONE, pathZone.name)
            put(DetectionDatabaseHelper.COL_MOVEMENT_DIRECTION, movementDirection.name)
            put(DetectionDatabaseHelper.COL_PREDICTED_PATH_CROSSING, predictedPathCrossing.toInt())
            putNullable(DetectionDatabaseHelper.COL_PREDICTED_PATH_CROSSING_SECONDS, predictedPathCrossingSeconds)
            put(DetectionDatabaseHelper.COL_PREDICTED_PATH_REASON, predictedPathReason)
            putNullable(DetectionDatabaseHelper.COL_DISTANCE_METERS, distanceMeters)
            put(DetectionDatabaseHelper.COL_DISTANCE_SOURCE, distanceSource.name)
            putNullable(DetectionDatabaseHelper.COL_DISTANCE_QUALITY, distanceQuality)
            putNullable(DetectionDatabaseHelper.COL_CLOSING_SPEED_MPS, closingSpeedMetersPerSecond)
            putNullable(DetectionDatabaseHelper.COL_TTC_SECONDS, ttcSeconds)
            putNullable(DetectionDatabaseHelper.COL_VEHICLE_SPEED_MPS, vehicleSpeedMetersPerSecond)
            putNullable(DetectionDatabaseHelper.COL_VEHICLE_SPEED_KMH, vehicleSpeedKmh)
            put(DetectionDatabaseHelper.COL_VEHICLE_SPEED_SOURCE, vehicleSpeedSource)
            putNullable(DetectionDatabaseHelper.COL_LATITUDE, latitude)
            putNullable(DetectionDatabaseHelper.COL_LONGITUDE, longitude)
            putNullable(DetectionDatabaseHelper.COL_BEARING_DEGREES, bearingDegrees)
            putNullable(DetectionDatabaseHelper.COL_HORIZONTAL_ACCURACY_METERS, horizontalAccuracyMeters)
            putNullable(DetectionDatabaseHelper.COL_BEARING_ACCURACY_DEGREES, bearingAccuracyDegrees)
            put(DetectionDatabaseHelper.COL_IMAGE_WIDTH, imageWidth)
            put(DetectionDatabaseHelper.COL_IMAGE_HEIGHT, imageHeight)
            put(DetectionDatabaseHelper.COL_ROTATION_DEGREES, rotationDegrees)
            put(DetectionDatabaseHelper.COL_BOX_LEFT, boxLeft)
            put(DetectionDatabaseHelper.COL_BOX_TOP, boxTop)
            put(DetectionDatabaseHelper.COL_BOX_RIGHT, boxRight)
            put(DetectionDatabaseHelper.COL_BOX_BOTTOM, boxBottom)
            put(DetectionDatabaseHelper.COL_VISIBILITY_STATE, visibilityState.name)
            put(DetectionDatabaseHelper.COL_VISIBILITY_QUALITY, visibilityQuality)
            put(DetectionDatabaseHelper.COL_ENHANCEMENT_ENABLED, enhancementEnabled.toInt())
            put(DetectionDatabaseHelper.COL_WARNING_LEVEL, warningLevel.name)
            put(DetectionDatabaseHelper.COL_WARNING_TRIGGERED, warningTriggered.toInt())
            put(DetectionDatabaseHelper.COL_AUDIO_TRIGGERED, audioTriggered.toInt())
            put(DetectionDatabaseHelper.COL_VIBRATION_TRIGGERED, vibrationTriggered.toInt())
            put(DetectionDatabaseHelper.COL_WARNING_MESSAGE, warningMessage)
            putNullable(DetectionDatabaseHelper.COL_IMAGE_PATH, imagePath)
            put(DetectionDatabaseHelper.COL_SYNC_STATUS, syncStatus)
        }
    }

    private fun Cursor.toDetectionHistoryItem(): DetectionHistoryItem {
        return DetectionHistoryItem(
            id = getLong(columnIndex(DetectionDatabaseHelper.COL_ID)),
            detectedAtEpochMillis = getLong(columnIndex(DetectionDatabaseHelper.COL_DETECTED_AT_EPOCH_MILLIS)),
            label = getString(columnIndex(DetectionDatabaseHelper.COL_LABEL)),
            confidence = getFloat(columnIndex(DetectionDatabaseHelper.COL_CONFIDENCE)),
            riskLevel = getString(columnIndex(DetectionDatabaseHelper.COL_RISK_LEVEL)),
            distanceMeters = nullableFloat(DetectionDatabaseHelper.COL_DISTANCE_METERS),
            vehicleSpeedKmh = nullableFloat(DetectionDatabaseHelper.COL_VEHICLE_SPEED_KMH),
            latitude = nullableDouble(DetectionDatabaseHelper.COL_LATITUDE),
            longitude = nullableDouble(DetectionDatabaseHelper.COL_LONGITUDE),
            movementDirection = getString(columnIndex(DetectionDatabaseHelper.COL_MOVEMENT_DIRECTION)),
            pathZone = getString(columnIndex(DetectionDatabaseHelper.COL_PATH_ZONE)),
            warningLevel = getString(columnIndex(DetectionDatabaseHelper.COL_WARNING_LEVEL)),
            cameraSource = getString(columnIndex(DetectionDatabaseHelper.COL_CAMERA_SOURCE)),
            syncStatus = getString(columnIndex(DetectionDatabaseHelper.COL_SYNC_STATUS))
        )
    }

    private fun Cursor.columnIndex(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.nullableFloat(name: String): Float? {
        val index = columnIndex(name)
        return if (isNull(index)) null else getFloat(index)
    }

    private fun Cursor.nullableDouble(name: String): Double? {
        val index = columnIndex(name)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Float?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    companion object {
        private const val TAG = "DetectionHistoryRepo"
        private const val DEFAULT_RECENT_LIMIT = 100
        private const val MAX_RECENT_LIMIT = 500
    }
}
