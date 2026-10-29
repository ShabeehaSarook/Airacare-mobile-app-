package com.airacare.animalsafety

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

class AnimalSafetyAnalyzer(
    private val detector: AnimalDetector,
    private val onDetections: (List<AnimalDetection>) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onPerformanceSample: (source: String, inferenceMillis: Long, totalPipelineMillis: Long, detections: Int, skippedFrames: Long) -> Unit = { _, _, _, _, _ -> },
    private val minAnalysisIntervalMillis: Long = MIN_ANALYSIS_INTERVAL_MILLIS
) : ImageAnalysis.Analyzer {
    private val isRunning = AtomicBoolean(false)
    private var analyzedFrames = 0L
    private var skippedFrames = 0L
    private var lastAnalysisStartedAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisStartedAt < minAnalysisIntervalMillis) {
            skippedFrames++
            image.close()
            return
        }
        if (!isRunning.compareAndSet(false, true)) {
            skippedFrames++
            if (skippedFrames <= 5L || skippedFrames % SKIPPED_FRAME_LOG_INTERVAL == 0L) {
                Log.d(TAG, "skip frame skipped=$skippedFrames image=${image.width}x${image.height} rotation=${image.imageInfo.rotationDegrees}")
            }
            image.close()
            return
        }

        val totalStartedAt = SystemClock.elapsedRealtime()
        try {
            lastAnalysisStartedAt = totalStartedAt
            analyzedFrames++
            val timestamp = image.imageInfo.timestamp
            if (analyzedFrames <= 5L || analyzedFrames % 10L == 0L) {
                Log.i(TAG, "ANALYZER FRAME RECEIVED frame=$analyzedFrames timestamp=$timestamp width=${image.width} height=${image.height} format=${image.format} rotationDegrees=${image.imageInfo.rotationDegrees}")
            }
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            val detections = detector.analyze(image)
            val inferenceMillis = SystemClock.elapsedRealtime() - inferenceStartedAt
            if (analyzedFrames <= 5L || analyzedFrames % ANALYZED_FRAME_LOG_INTERVAL == 0L) {
                Log.d(TAG, "inference finished frame=$analyzedFrames timestamp=$timestamp detections=${detections.size} inferenceMs=$inferenceMillis")
            }
            onDetections(detections)
            onPerformanceSample("phone_camera", inferenceMillis, SystemClock.elapsedRealtime() - totalStartedAt, detections.size, skippedFrames)
        } catch (exception: Exception) {
            Log.e(TAG, "Inference failed", exception)
            onDetections(emptyList())
            onFailure(exception)
            onPerformanceSample("phone_camera", 0L, SystemClock.elapsedRealtime() - totalStartedAt, 0, skippedFrames)
        } finally {
            isRunning.set(false)
            image.close()
        }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "AiracareAnalyzer"
        private const val ANALYZED_FRAME_LOG_INTERVAL = 10L
        private const val SKIPPED_FRAME_LOG_INTERVAL = 30L
        private const val MIN_ANALYSIS_INTERVAL_MILLIS = 120L
    }
}
