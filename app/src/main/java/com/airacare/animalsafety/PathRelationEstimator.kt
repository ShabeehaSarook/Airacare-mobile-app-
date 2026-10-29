package com.airacare.animalsafety

class PathRelationEstimator {
    fun apply(detections: List<AnimalDetection>): List<AnimalDetection> {
        return detections.map { detection ->
            detection.copy(pathZone = estimate(detection))
        }
    }

    private fun estimate(detection: AnimalDetection): PathZone {
        val width = detection.imageWidth.coerceAtLeast(1).toFloat()
        val height = detection.imageHeight.coerceAtLeast(1).toFloat()
        val xRatio = detection.boundingBox.centerX / width
        val yRatio = detection.boundingBox.centerY / height
        val areaRatio = detection.boundingBox.area / (width * height).coerceAtLeast(1f)
        val heightRatio = detection.boundingBox.height / height

        if ((areaRatio > MAX_UNTRUSTED_PATH_BOX_AREA_RATIO || heightRatio > MAX_UNTRUSTED_PATH_BOX_HEIGHT_RATIO) &&
            detection.confidence < HIGH_CONFIDENCE_FOR_LARGE_PATH_BOX
        ) {
            return PathZone.UNKNOWN
        }

        return when {
            yRatio >= 0.45f && xRatio in 0.35f..0.65f -> PathZone.IN_PATH
            yRatio >= 0.35f && xRatio in 0.18f..0.82f -> PathZone.NEAR_PATH
            else -> PathZone.OUTSIDE_PATH
        }
    }

    companion object {
        private const val MAX_UNTRUSTED_PATH_BOX_AREA_RATIO = 0.70f
        private const val MAX_UNTRUSTED_PATH_BOX_HEIGHT_RATIO = 0.92f
        private const val HIGH_CONFIDENCE_FOR_LARGE_PATH_BOX = 0.80f
    }
}
