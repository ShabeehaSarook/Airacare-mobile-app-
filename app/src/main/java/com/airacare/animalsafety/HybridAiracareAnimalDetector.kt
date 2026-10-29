package com.airacare.animalsafety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

class HybridAiracareAnimalDetector(context: Context) : AnimalDetector {
    private val pretrainedDetector = YoloTfliteAnimalDetector(
        context,
        YoloTfliteAnimalDetector.ModelPreference.PRETRAINED_COCO
    )
    private val customDetector = YoloTfliteAnimalDetector(
        context,
        YoloTfliteAnimalDetector.ModelPreference.CUSTOM_AIRACARE
    )

    override fun analyze(image: ImageProxy): List<AnimalDetection> {
        val source = ImageProxyBitmapConverter.toBitmap(image)
        val rotationDegrees = image.imageInfo.rotationDegrees
        val upright = if (rotationDegrees == 0) {
            source
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                source.recycle()
            }
        }
        return try {
            analyze(upright)
        } finally {
            upright.recycle()
        }
    }

    override fun analyze(bitmap: Bitmap): List<AnimalDetection> {
        val pretrained = pretrainedDetector.analyze(bitmap)
            .filter { it.label.lowercase() in PRETRAINED_TARGET_LABELS }
        val customRecovery = customDetector.analyze(bitmap)
            .filter { it.label.lowercase() in CUSTOM_RECOVERY_LABELS }
        val merged = merge(pretrained, customRecovery)
        Log.d(
            TAG,
            "Hybrid detections pretrained=${pretrained.size} customRecovery=${customRecovery.size} merged=${merged.size} labels=${merged.joinToString { "${it.label}:${String.format("%.2f", it.confidence)}" }}"
        )
        return merged
    }

    override fun close() {
        pretrainedDetector.close()
        customDetector.close()
    }

    private fun merge(primary: List<AnimalDetection>, recovery: List<AnimalDetection>): List<AnimalDetection> {
        val selected = primary.toMutableList()
        recovery.sortedByDescending { it.confidence }.forEach { candidate ->
            val overlapsExisting = selected.any { existing ->
                iou(existing.boundingBox, candidate.boundingBox) >= DUPLICATE_IOU_THRESHOLD
            }
            if (!overlapsExisting) selected.add(candidate)
        }
        return selected.sortedByDescending { it.confidence }.take(MAX_MERGED_DETECTIONS)
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        private const val TAG = "HybridAiracareDetector"
        private const val DUPLICATE_IOU_THRESHOLD = 0.45f
        private const val MAX_MERGED_DETECTIONS = 30
        private val PRETRAINED_TARGET_LABELS = setOf("person", "dog", "cat", "horse", "cow", "elephant")
        private val CUSTOM_RECOVERY_LABELS = setOf("deer", "goat")
    }
}
