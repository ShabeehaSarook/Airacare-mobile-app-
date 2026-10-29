package com.airacare.animalsafety

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera

class CameraMetadataCalibrationFactory(private val context: Context) {
    fun create(camera: Camera, analysisWidth: Int, analysisHeight: Int): DistanceCalibration? {
        if (analysisWidth <= 0 || analysisHeight <= 0) {
            Log.w(TAG, "Camera metadata calibration unavailable reason=invalid_analysis_resolution resolution=${analysisWidth}x$analysisHeight")
            return null
        }

        return try {
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val focalLengthMm = firstValidFocalLength(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
            val sensorPhysicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val lensFacingLabel = lensFacingLabel(lensFacing)

            Log.i(TAG, "Camera metadata cameraId=$cameraId lensFacing=$lensFacingLabel")
            Log.i(TAG, "Camera metadata focalLengthMm=$focalLengthMm sensorPhysicalSize=$sensorPhysicalSize sensorPixelArray=$pixelArraySize activeArray=$activeArraySize analysis=${analysisWidth}x$analysisHeight")

            if (focalLengthMm == null || sensorPhysicalSize == null || pixelArraySize == null) {
                Log.w(TAG, "Camera metadata calibration unavailable reason=missing_metadata cameraId=$cameraId focal=$focalLengthMm physical=$sensorPhysicalSize pixels=$pixelArraySize")
                return null
            }
            if (!validPhysicalSize(sensorPhysicalSize) || !validPixelArray(pixelArraySize)) {
                Log.w(TAG, "Camera metadata calibration unavailable reason=invalid_metadata cameraId=$cameraId physical=$sensorPhysicalSize pixels=$pixelArraySize")
                return null
            }

            val portraitUprightFrame = analysisHeight >= analysisWidth
            val sensorHorizontalMm = if (portraitUprightFrame) sensorPhysicalSize.height else sensorPhysicalSize.width
            val sensorVerticalMm = if (portraitUprightFrame) sensorPhysicalSize.width else sensorPhysicalSize.height
            val sensorHorizontalPixels = if (portraitUprightFrame) pixelArraySize.height else pixelArraySize.width
            val sensorVerticalPixels = if (portraitUprightFrame) pixelArraySize.width else pixelArraySize.height
            if (sensorHorizontalMm <= 0f || sensorVerticalMm <= 0f || sensorHorizontalPixels <= 0 || sensorVerticalPixels <= 0) {
                Log.w(TAG, "Camera metadata calibration unavailable reason=invalid_axis cameraId=$cameraId horizontalMm=$sensorHorizontalMm verticalMm=$sensorVerticalMm horizontalPixels=$sensorHorizontalPixels verticalPixels=$sensorVerticalPixels portrait=$portraitUprightFrame")
                return null
            }

            val focalPxX = (focalLengthMm / sensorHorizontalMm) * analysisWidth.toFloat()
            val focalPxY = (focalLengthMm / sensorVerticalMm) * analysisHeight.toFloat()
            val focalLengthPixels = focalPxY
            if (!focalPxX.isFinite() || !focalPxY.isFinite() || focalPxX <= 0f || focalPxY <= 0f) {
                Log.w(TAG, "Camera metadata calibration unavailable reason=invalid_focal_pixels cameraId=$cameraId focalPxX=$focalPxX focalPxY=$focalPxY")
                return null
            }

            Log.i(
                TAG,
                "INTRINSICS cameraId=$cameraId lensFacing=$lensFacingLabel focalLengthMm=$focalLengthMm sensorPhysicalWidth=${sensorPhysicalSize.width} sensorPhysicalHeight=${sensorPhysicalSize.height} pixelArray=${pixelArraySize.width}x${pixelArraySize.height} activeArray=$activeArraySize analysis=${analysisWidth}x$analysisHeight focalPxX=$focalPxX focalPxY=$focalPxY selectedFocalPxY=$focalLengthPixels portraitUpright=$portraitUprightFrame"
            )

            DistanceCalibration(
                focalLengthPixels = focalLengthPixels,
                cameraName = "${Build.MANUFACTURER} ${Build.MODEL}",
                cameraSource = "${Build.MANUFACTURER} ${Build.MODEL} camera_id_$cameraId lens=$lensFacingLabel resolution=${analysisWidth}x$analysisHeight",
                calibrationWidth = analysisWidth,
                calibrationHeight = analysisHeight,
                calibrationObject = "camera_metadata",
                knownObjectHeightMeters = 0f,
                knownDistanceMeters = 0f,
                pixelHeight = 0f,
                createdAtMillis = System.currentTimeMillis(),
                calibrationSource = DistanceCalibration.SOURCE_CAMERA_METADATA
            ).also { it.validate() }
        } catch (exception: Exception) {
            Log.e(TAG, "Camera metadata calibration unavailable reason=exception", exception)
            null
        }
    }

    private fun firstValidFocalLength(values: FloatArray?): Float? {
        return values?.firstOrNull { it.isFinite() && it > 0f }
    }

    private fun validPhysicalSize(size: SizeF): Boolean {
        return size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f
    }

    private fun validPixelArray(size: Size): Boolean {
        return size.width > 0 && size.height > 0
    }

    private fun lensFacingLabel(value: Int?): String {
        return when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }

    companion object {
        private const val TAG = "CameraMetadataCalib"
    }
}


