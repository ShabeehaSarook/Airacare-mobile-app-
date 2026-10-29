package com.airacare.animalsafety

import android.util.Log

class MonocularDepthDistanceProvider : DistanceProvider {
    override val source = DistanceSource.MONOCULAR_DEPTH
    override val quality = 0f
    override val cameraLabel = "monocular_depth_unavailable"

    override fun estimate(detection: AnimalDetection): DistanceMeasurement? {
        Log.d(TAG, "Monocular depth unavailable: no validated TFLite/LiteRT depth model asset is bundled.")
        return null
    }

    override fun focalLengthPixelsFor(detection: AnimalDetection): Float? = null

    companion object {
        private const val TAG = "MonocularDepthProvider"
    }
}

class ArCoreDepthDistanceProvider : DistanceProvider {
    override val source = DistanceSource.ARCORE_DEPTH
    override val quality = 0f
    override val cameraLabel = "arcore_depth_unavailable"

    override fun estimate(detection: AnimalDetection): DistanceMeasurement? {
        Log.d(TAG, "ARCore depth unavailable: optional provider is not active on this CameraX pipeline.")
        return null
    }

    override fun focalLengthPixelsFor(detection: AnimalDetection): Float? = null

    companion object {
        private const val TAG = "ArCoreDepthProvider"
    }
}
