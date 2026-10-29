package com.airacare.animalsafety

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DetectionDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_DETECTIONS)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_history_time ON $TABLE_DETECTIONS ($COL_DETECTED_AT_EPOCH_MILLIS DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_history_risk ON $TABLE_DETECTIONS ($COL_RISK_LEVEL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_history_location ON $TABLE_DETECTIONS ($COL_LATITUDE, $COL_LONGITUDE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_history_sync ON $TABLE_DETECTIONS ($COL_SYNC_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "airacare_detections.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_DETECTIONS = "detection_history"
        const val COL_ID = "id"
        const val COL_DETECTED_AT_EPOCH_MILLIS = "detected_at_epoch_millis"
        const val COL_DETECTED_AT_ELAPSED_MILLIS = "detected_at_elapsed_millis"
        const val COL_PIPELINE_FRAME = "pipeline_frame"
        const val COL_CAMERA_SOURCE = "camera_source"
        const val COL_TRACK_ID = "track_id"
        const val COL_LABEL = "label"
        const val COL_CONFIDENCE = "confidence"
        const val COL_RISK_LEVEL = "risk_level"
        const val COL_RISK_REASON = "risk_reason"
        const val COL_PATH_ZONE = "path_zone"
        const val COL_MOVEMENT_DIRECTION = "movement_direction"
        const val COL_PREDICTED_PATH_CROSSING = "predicted_path_crossing"
        const val COL_PREDICTED_PATH_CROSSING_SECONDS = "predicted_path_crossing_seconds"
        const val COL_PREDICTED_PATH_REASON = "predicted_path_reason"
        const val COL_DISTANCE_METERS = "distance_meters"
        const val COL_DISTANCE_SOURCE = "distance_source"
        const val COL_DISTANCE_QUALITY = "distance_quality"
        const val COL_CLOSING_SPEED_MPS = "closing_speed_mps"
        const val COL_TTC_SECONDS = "ttc_seconds"
        const val COL_VEHICLE_SPEED_MPS = "vehicle_speed_mps"
        const val COL_VEHICLE_SPEED_KMH = "vehicle_speed_kmh"
        const val COL_VEHICLE_SPEED_SOURCE = "vehicle_speed_source"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_BEARING_DEGREES = "bearing_degrees"
        const val COL_HORIZONTAL_ACCURACY_METERS = "horizontal_accuracy_meters"
        const val COL_BEARING_ACCURACY_DEGREES = "bearing_accuracy_degrees"
        const val COL_IMAGE_WIDTH = "image_width"
        const val COL_IMAGE_HEIGHT = "image_height"
        const val COL_ROTATION_DEGREES = "rotation_degrees"
        const val COL_BOX_LEFT = "box_left"
        const val COL_BOX_TOP = "box_top"
        const val COL_BOX_RIGHT = "box_right"
        const val COL_BOX_BOTTOM = "box_bottom"
        const val COL_VISIBILITY_STATE = "visibility_state"
        const val COL_VISIBILITY_QUALITY = "visibility_quality"
        const val COL_ENHANCEMENT_ENABLED = "enhancement_enabled"
        const val COL_WARNING_LEVEL = "warning_level"
        const val COL_WARNING_TRIGGERED = "warning_triggered"
        const val COL_AUDIO_TRIGGERED = "audio_triggered"
        const val COL_VIBRATION_TRIGGERED = "vibration_triggered"
        const val COL_WARNING_MESSAGE = "warning_message"
        const val COL_IMAGE_PATH = "image_path"
        const val COL_SYNC_STATUS = "sync_status"

        private val SQL_CREATE_DETECTIONS = """
            CREATE TABLE IF NOT EXISTS $TABLE_DETECTIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DETECTED_AT_EPOCH_MILLIS INTEGER NOT NULL,
                $COL_DETECTED_AT_ELAPSED_MILLIS INTEGER NOT NULL,
                $COL_PIPELINE_FRAME INTEGER NOT NULL,
                $COL_CAMERA_SOURCE TEXT NOT NULL,
                $COL_TRACK_ID INTEGER,
                $COL_LABEL TEXT NOT NULL,
                $COL_CONFIDENCE REAL NOT NULL,
                $COL_RISK_LEVEL TEXT NOT NULL,
                $COL_RISK_REASON TEXT NOT NULL,
                $COL_PATH_ZONE TEXT NOT NULL,
                $COL_MOVEMENT_DIRECTION TEXT NOT NULL,
                $COL_PREDICTED_PATH_CROSSING INTEGER NOT NULL,
                $COL_PREDICTED_PATH_CROSSING_SECONDS REAL,
                $COL_PREDICTED_PATH_REASON TEXT NOT NULL,
                $COL_DISTANCE_METERS REAL,
                $COL_DISTANCE_SOURCE TEXT NOT NULL,
                $COL_DISTANCE_QUALITY REAL,
                $COL_CLOSING_SPEED_MPS REAL,
                $COL_TTC_SECONDS REAL,
                $COL_VEHICLE_SPEED_MPS REAL,
                $COL_VEHICLE_SPEED_KMH REAL,
                $COL_VEHICLE_SPEED_SOURCE TEXT NOT NULL,
                $COL_LATITUDE REAL,
                $COL_LONGITUDE REAL,
                $COL_BEARING_DEGREES REAL,
                $COL_HORIZONTAL_ACCURACY_METERS REAL,
                $COL_BEARING_ACCURACY_DEGREES REAL,
                $COL_IMAGE_WIDTH INTEGER NOT NULL,
                $COL_IMAGE_HEIGHT INTEGER NOT NULL,
                $COL_ROTATION_DEGREES INTEGER NOT NULL,
                $COL_BOX_LEFT REAL NOT NULL,
                $COL_BOX_TOP REAL NOT NULL,
                $COL_BOX_RIGHT REAL NOT NULL,
                $COL_BOX_BOTTOM REAL NOT NULL,
                $COL_VISIBILITY_STATE TEXT NOT NULL,
                $COL_VISIBILITY_QUALITY REAL NOT NULL,
                $COL_ENHANCEMENT_ENABLED INTEGER NOT NULL,
                $COL_WARNING_LEVEL TEXT NOT NULL,
                $COL_WARNING_TRIGGERED INTEGER NOT NULL,
                $COL_AUDIO_TRIGGERED INTEGER NOT NULL,
                $COL_VIBRATION_TRIGGERED INTEGER NOT NULL,
                $COL_WARNING_MESSAGE TEXT NOT NULL,
                $COL_IMAGE_PATH TEXT,
                $COL_SYNC_STATUS TEXT NOT NULL
            )
        """.trimIndent()
    }
}
