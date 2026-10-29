package com.airacare.animalsafety

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class PerformanceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = Runtime.getRuntime()
    private val csvFile: File
    private val frameTimes = ArrayDeque<Long>()
    private var lastLogAtMillis = 0L
    private var lastSnapshot = PerformanceSnapshot()
    private var lastBatteryReadAtMillis = 0L
    private var cachedBattery = BatteryInfo(null, null)

    init {
        val root = File(appContext.getExternalFilesDir(null), "airacare_performance")
        root.mkdirs()
        csvFile = File(root, "performance_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
        csvFile.writeText(
            "elapsed_ms,source,fps,inference_ms,total_pipeline_ms,detections,skipped_frames,used_memory_mb,max_memory_mb,battery_percent,battery_temp_c\n"
        )
    }

    @Synchronized
    fun recordFrame(
        source: String,
        inferenceMillis: Long,
        totalPipelineMillis: Long,
        detections: Int,
        skippedFrames: Long = 0L
    ): PerformanceSnapshot {
        val now = SystemClock.elapsedRealtime()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > FPS_WINDOW_MILLIS) {
            frameTimes.removeFirst()
        }
        val battery = cachedBattery(now)
        val usedMemoryMb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MEGABYTE).toInt()
        val maxMemoryMb = (runtime.maxMemory() / BYTES_PER_MEGABYTE).toInt()
        val fps = frameTimes.size * 1000f / FPS_WINDOW_MILLIS
        lastSnapshot = PerformanceSnapshot(
            fps = fps,
            inferenceMillis = inferenceMillis,
            totalPipelineMillis = totalPipelineMillis,
            detections = detections,
            skippedFrames = skippedFrames,
            usedMemoryMb = usedMemoryMb,
            maxMemoryMb = maxMemoryMb,
            batteryPercent = battery.percent,
            batteryTempCelsius = battery.temperatureCelsius,
            source = source
        )
        csvFile.appendText(
            listOf(
                now,
                source,
                String.format(Locale.US, "%.2f", fps),
                inferenceMillis,
                totalPipelineMillis,
                detections,
                skippedFrames,
                usedMemoryMb,
                maxMemoryMb,
                battery.percent ?: "",
                battery.temperatureCelsius?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            ).joinToString(",") + "\n"
        )
        if (now - lastLogAtMillis >= LOG_INTERVAL_MILLIS) {
            lastLogAtMillis = now
            Log.i(TAG, lastSnapshot.toLogLine())
        }
        return lastSnapshot
    }

    @Synchronized
    fun recordSkippedFrame(source: String, skippedFrames: Long): PerformanceSnapshot {
        return recordFrame(
            source = source,
            inferenceMillis = 0L,
            totalPipelineMillis = 0L,
            detections = 0,
            skippedFrames = skippedFrames
        )
    }

    @Synchronized
    fun snapshot(): PerformanceSnapshot = lastSnapshot

    fun reportPath(): String = csvFile.absolutePath

    private fun readBattery(): BatteryInfo {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else null
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null
        return BatteryInfo(percent, tempC)
    }

    private fun cachedBattery(nowMillis: Long): BatteryInfo {
        if (nowMillis - lastBatteryReadAtMillis >= BATTERY_READ_INTERVAL_MILLIS) {
            cachedBattery = readBattery()
            lastBatteryReadAtMillis = nowMillis
        }
        return cachedBattery
    }

    private data class BatteryInfo(
        val percent: Int?,
        val temperatureCelsius: Float?
    )

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val FPS_WINDOW_MILLIS = 3_000L
        private const val LOG_INTERVAL_MILLIS = 2_000L
        private const val BATTERY_READ_INTERVAL_MILLIS = 5_000L
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
    }
}

data class PerformanceSnapshot(
    val fps: Float = 0f,
    val inferenceMillis: Long = 0L,
    val totalPipelineMillis: Long = 0L,
    val detections: Int = 0,
    val skippedFrames: Long = 0L,
    val usedMemoryMb: Int = 0,
    val maxMemoryMb: Int = 0,
    val batteryPercent: Int? = null,
    val batteryTempCelsius: Float? = null,
    val source: String = "unknown"
) {
    fun compactText(): String {
        val battery = batteryPercent?.let { "$it%" } ?: "N/A"
        val temp = batteryTempCelsius?.let { String.format(Locale.US, "%.1fC", it) } ?: "N/A"
        return "FPS:${String.format(Locale.US, "%.1f", fps)} INF:${inferenceMillis}ms PIPE:${totalPipelineMillis}ms MEM:${usedMemoryMb}/${maxMemoryMb}MB BAT:$battery TEMP:$temp"
    }

    fun toLogLine(): String {
        return "PERF source=$source ${compactText()} detections=$detections skipped=$skippedFrames"
    }
}
