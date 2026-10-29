package com.airacare.animalsafety

import android.content.Context
import android.util.Log
import org.json.JSONObject

class DistanceCalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): DistanceCalibration? {
        val raw = preferences.getString(KEY_CALIBRATION, null)
        if (raw.isNullOrBlank()) {
            Log.w(TAG, "Distance calibration missing; DIST: N/A will be shown until camera metadata or manual calibration is available.")
            return null
        }

        return try {
            val json = JSONObject(raw)
            DistanceCalibration(
                focalLengthPixels = json.getDouble("focal_length_pixels").toFloat(),
                cameraName = json.optString("camera_name", "unknown"),
                cameraSource = json.optString("camera_source", "unknown"),
                calibrationWidth = json.getInt("calibration_width"),
                calibrationHeight = json.getInt("calibration_height"),
                calibrationObject = json.optString("calibration_object", json.optString("known_object_class", "person")),
                knownObjectHeightMeters = json.optDouble("known_object_height_m", json.optDouble("known_height_meters", 0.0)).toFloat(),
                knownDistanceMeters = json.optDouble("known_distance_m", json.optDouble("known_distance_meters", 0.0)).toFloat(),
                pixelHeight = json.optDouble("pixel_height", 0.0).toFloat(),
                createdAtMillis = json.optLong("created_at_millis", 0L),
                calibrationSource = json.optString("calibration_source", DistanceCalibration.SOURCE_MANUAL)
            ).also { calibration ->
                calibration.validate()
                Log.i(
                    TAG,
                    "CALIBRATION SOURCE: SAVED storedSource=${calibration.calibrationSource} camera=${calibration.cameraSource} focal=${calibration.focalLengthPixels} resolution=${calibration.calibrationWidth}x${calibration.calibrationHeight}"
                )
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Invalid distance calibration; DIST: N/A will be shown until camera metadata or manual calibration is available.", exception)
            null
        }
    }


    fun hasSavedCalibration(): Boolean {
        return !preferences.getString(KEY_CALIBRATION, null).isNullOrBlank()
    }
    fun save(calibration: DistanceCalibration) {
        calibration.validate()
        val json = JSONObject()
            .put("focal_length_pixels", calibration.focalLengthPixels.toDouble())
            .put("camera_name", calibration.cameraName)
            .put("camera_source", calibration.cameraSource)
            .put("calibration_source", calibration.calibrationSource)
            .put("calibration_width", calibration.calibrationWidth)
            .put("calibration_height", calibration.calibrationHeight)
            .put("calibration_resolution", org.json.JSONArray(listOf(calibration.calibrationWidth, calibration.calibrationHeight)))
            .put("known_object_class", calibration.calibrationObject)
            .put("calibration_object", calibration.calibrationObject)
            .put("known_object_height_m", calibration.knownObjectHeightMeters.toDouble())
            .put("known_height_meters", calibration.knownObjectHeightMeters.toDouble())
            .put("known_distance_m", calibration.knownDistanceMeters.toDouble())
            .put("known_distance_meters", calibration.knownDistanceMeters.toDouble())
            .put("pixel_height", calibration.pixelHeight.toDouble())
            .put("created_at_millis", calibration.createdAtMillis)
            .put("note", "Distance is an estimate based on camera calibration and object size assumptions. Do not change zoom after calibration.")

        preferences.edit().putString(KEY_CALIBRATION, json.toString()).apply()
        Log.i(
            TAG,
            "Calibration saved source=${calibration.calibrationSource} camera=${calibration.cameraSource} focal=${calibration.focalLengthPixels} pixelHeight=${calibration.pixelHeight}"
        )
    }

    companion object {
        private const val TAG = "CalibrationStore"
        private const val PREFERENCES_NAME = "airacare_distance_calibration"
        private const val KEY_CALIBRATION = "calibration_json"
    }
}



