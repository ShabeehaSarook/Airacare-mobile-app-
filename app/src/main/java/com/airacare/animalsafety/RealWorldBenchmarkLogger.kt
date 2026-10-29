package com.airacare.animalsafety

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RealWorldBenchmarkLogger(context: Context) {
    private val appContext = context.applicationContext
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var sessionDir: File? = null
    private var detectionsFile: File? = null
    private var failuresFile: File? = null
    private var summaryFile: File? = null
    private var startedAtMillis: Long = 0L
    private var frameIndex: Long = 0L
    private var detectionRows: Long = 0L
    private var falsePositiveCount: Long = 0L
    private var falseNegativeCount: Long = 0L
    private val classCounts = mutableMapOf<String, Long>()

    val isActive: Boolean
        get() = sessionDir != null

    fun start(): File {
        if (sessionDir != null) return sessionDir!!
        val root = File(appContext.getExternalFilesDir(null), "airacare_benchmarks")
        val dir = File(root, "session_${dateFormat.format(Date())}")
        dir.mkdirs()
        sessionDir = dir
        detectionsFile = File(dir, "detections.csv").also { file ->
            file.writeText(
                "session_elapsed_ms,frame_index,source,label,confidence,track_id,risk,path_zone,distance_m,ttc_s,speed_kmh,gps_lat,gps_lon,bbox_left,bbox_top,bbox_right,bbox_bottom,image_width,image_height,warning_level,warning_message\n"
            )
        }
        failuresFile = File(dir, "failure_notes.csv").also { file ->
            file.writeText(
                "session_elapsed_ms,frame_index,source,type,note,detection_count,top_detection,top_confidence,track_id,risk,path_zone,distance_m,ttc_s,visibility,visibility_quality,bbox_left,bbox_top,bbox_right,bbox_bottom,gps_lat,gps_lon\n"
            )
        }
        summaryFile = File(dir, "summary.txt").also { file ->
            file.writeText("Airacare real-world benchmark session\nStarted: ${Date()}\n")
        }
        startedAtMillis = SystemClock.elapsedRealtime()
        frameIndex = 0L
        detectionRows = 0L
        falsePositiveCount = 0L
        falseNegativeCount = 0L
        classCounts.clear()
        Log.i(TAG, "Benchmark logging started dir=${dir.absolutePath}")
        return dir
    }

    fun stop(): File? {
        val dir = sessionDir ?: return null
        summaryFile?.appendText(buildSummaryText())
        sessionDir = null
        detectionsFile = null
        failuresFile = null
        summaryFile = null
        Log.i(TAG, "Benchmark logging stopped dir=${dir.absolutePath}")
        return dir
    }

    fun logFrame(source: String, detections: List<AnimalDetection>, warningState: WarningState) {
        val file = detectionsFile ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAtMillis
        val currentFrame = frameIndex++
        if (detections.isEmpty()) {
            file.appendText(csvRow(listOf(elapsed, currentFrame, source, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", warningState.level.name, warningState.message)))
            return
        }
        detections.forEach { detection ->
            detectionRows++
            classCounts[detection.label] = (classCounts[detection.label] ?: 0L) + 1L
            file.appendText(
                csvRow(
                    listOf(
                        elapsed,
                        currentFrame,
                        source,
                        detection.label,
                        detection.confidence,
                        detection.trackId ?: "",
                        detection.riskLevel.name,
                        detection.pathZone.name,
                        detection.distanceMeters ?: "",
                        detection.ttcSeconds ?: "",
                        detection.vehicleSpeedKmh ?: "",
                        detection.gpsLatitude ?: "",
                        detection.gpsLongitude ?: "",
                        detection.boundingBox.left,
                        detection.boundingBox.top,
                        detection.boundingBox.right,
                        detection.boundingBox.bottom,
                        detection.imageWidth,
                        detection.imageHeight,
                        warningState.level.name,
                        warningState.message
                    )
                )
            )
        }
    }

    fun markFailure(type: FailureType, source: String, detections: List<AnimalDetection>, note: String = "") {
        if (!isActive) start()
        val file = failuresFile ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAtMillis
        when (type) {
            FailureType.FALSE_POSITIVE -> falsePositiveCount++
            FailureType.FALSE_NEGATIVE -> falseNegativeCount++
        }
        val top = detections.maxByOrNull { it.confidence }
        val box = top?.boundingBox
        file.appendText(
            csvRow(
                listOf(
                    elapsed,
                    frameIndex,
                    source,
                    type.name,
                    note,
                    detections.size,
                    top?.label ?: "",
                    top?.confidence ?: "",
                    top?.trackId ?: "",
                    top?.riskLevel?.name ?: "",
                    top?.pathZone?.name ?: "",
                    top?.distanceMeters ?: "",
                    top?.ttcSeconds ?: "",
                    top?.visibilityState?.name ?: "",
                    top?.visibilityQuality ?: "",
                    box?.left ?: "",
                    box?.top ?: "",
                    box?.right ?: "",
                    box?.bottom ?: "",
                    top?.gpsLatitude ?: "",
                    top?.gpsLongitude ?: ""
                )
            )
        )
        Log.i(TAG, "Benchmark failure marked type=${type.name} detections=${detections.size}")
    }


    private fun buildSummaryText(): String {
        val classSummary = if (classCounts.isEmpty()) {
            "none"
        } else {
            classCounts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        return "Stopped: ${Date()}\nFrames observed: $frameIndex\nDetection rows: $detectionRows\nFalse positives marked: $falsePositiveCount\nFalse negatives marked: $falseNegativeCount\nClass counts:\n$classSummary\n"
    }
    fun currentSessionPath(): String? = sessionDir?.absolutePath

    private fun csvRow(values: List<Any?>): String {
        return values.joinToString(",") { value -> csvCell(value?.toString() ?: "") } + "\n"
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '\n' || it == '\r' || it == '"' }) "\"$escaped\"" else escaped
    }

    enum class FailureType {
        FALSE_POSITIVE,
        FALSE_NEGATIVE
    }

    companion object {
        private const val TAG = "BenchmarkLogger"
    }
}



