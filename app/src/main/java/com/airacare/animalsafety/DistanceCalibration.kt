package com.airacare.animalsafety

data class DistanceCalibration(
    val focalLengthPixels: Float,
    val cameraName: String,
    val cameraSource: String,
    val calibrationWidth: Int,
    val calibrationHeight: Int,
    val calibrationObject: String,
    val knownObjectHeightMeters: Float,
    val knownDistanceMeters: Float,
    val pixelHeight: Float,
    val createdAtMillis: Long,
    val calibrationSource: String = SOURCE_MANUAL
) {
    fun validate() {
        require(focalLengthPixels > 0f) { "Calibration focal length must be greater than 0." }
        require(calibrationWidth > 0 && calibrationHeight > 0) { "Calibration resolution must be valid." }
        require(calibrationSource in VALID_SOURCES) { "Unknown calibration source: $calibrationSource." }

        if (calibrationSource == SOURCE_MANUAL) {
            require(knownObjectHeightMeters > 0f) { "Known object height must be greater than 0." }
            require(knownDistanceMeters > 0f) { "Known distance must be greater than 0." }
            require(pixelHeight >= DistanceEstimator.MIN_BBOX_HEIGHT_PIXELS) { "Calibration pixel height is too small." }
            require(calibrationObject.equals("person", ignoreCase = true)) { "Android manual calibration currently supports person only." }
        }
    }

    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_CAMERA_METADATA = "CAMERA_METADATA"
        private val VALID_SOURCES = setOf(SOURCE_MANUAL, SOURCE_CAMERA_METADATA)
    }
}
