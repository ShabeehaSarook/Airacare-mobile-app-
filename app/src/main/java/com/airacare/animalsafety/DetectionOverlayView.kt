package com.airacare.animalsafety

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 5, 8, 10)
        style = Paint.Style.FILL
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val detailTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 238, 232)
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Volatile
    private var detections: List<AnimalDetection> = emptyList()
    private var updateCount = 0L

    fun setDetections(nextDetections: List<AnimalDetection>) {
        detections = nextDetections
        updateCount++
        Log.d(TAG, "overlay update=$updateCount detections=${nextDetections.size} size=${width}x$height")
        nextDetections.forEach { detection ->
            Log.d(TAG, "overlay detection class=${detection.label} confidence=${String.format("%.2f", detection.confidence)} bbox=${detection.boundingBox} track=${detection.trackId} path=${detection.pathZone} risk=${detection.riskLevel}")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentDetections = detections
        if (currentDetections.isEmpty() || width == 0 || height == 0) return

        val frameWidth = currentDetections.first().imageWidth.toFloat().coerceAtLeast(1f)
        val frameHeight = currentDetections.first().imageHeight.toFloat().coerceAtLeast(1f)
        val scale = max(width / frameWidth, height / frameHeight)
        val dx = (width - frameWidth * scale) / 2f
        val dy = (height - frameHeight * scale) / 2f
        val occupiedLabels = mutableListOf<RectF>()

        currentDetections.forEach { detection ->
            val sourceBox = detection.boundingBox
            val box = RectF(
                dx + sourceBox.left * scale,
                dy + sourceBox.top * scale,
                dx + sourceBox.right * scale,
                dy + sourceBox.bottom * scale
            )
            box.left = box.left.coerceIn(0f, width.toFloat())
            box.top = box.top.coerceIn(0f, height.toFloat())
            box.right = box.right.coerceIn(0f, width.toFloat())
            box.bottom = box.bottom.coerceIn(0f, height.toFloat())

            if (box.width() <= 1f || box.height() <= 1f) return@forEach

            val riskColor = colorForRisk(detection.riskLevel)
            boxPaint.color = riskColor
            labelStrokePaint.color = riskColor
            canvas.drawRect(box, boxPaint)

            val lines = labelLines(detection)
            val labelRect = placeLabel(box, lines, occupiedLabels)
            occupiedLabels.add(labelRect)

            canvas.drawRoundRect(labelRect, LABEL_CORNER_RADIUS, LABEL_CORNER_RADIUS, labelBackgroundPaint)
            canvas.drawRoundRect(labelRect, LABEL_CORNER_RADIUS, LABEL_CORNER_RADIUS, labelStrokePaint)

            var baseline = labelRect.top + LABEL_PADDING + 23f
            lines.forEachIndexed { index, line ->
                val paint = if (index == 0) labelTextPaint else detailTextPaint
                canvas.drawText(line, labelRect.left + LABEL_PADDING, baseline, paint)
                baseline += LINE_HEIGHT
            }
        }
    }

    private fun labelLines(detection: AnimalDetection): List<String> {
        val idText = detection.trackId?.let { " #$it" } ?: ""
        val displayName = if (isChecking(detection)) {
            "CHECKING..."
        } else {
            detection.label.uppercase()
        }
        val label = "$displayName$idText ${(detection.confidence * 100f).toInt()}%"
        if (!SHOW_DEBUG_OVERLAY_DETAILS) {
            val distance = detection.distanceMeters?.let { "~${String.format("%.1f", it)}m" } ?: "DIST N/A"
            return listOf(label, "${riskLabel(detection.riskLevel)} $distance")
        }
        val pathLabel = "PATH: ${detection.pathZone.name}"
        val distanceLabel = detection.distanceMeters?.let { "DIST: ~${String.format("%.1f", it)} m" } ?: "DIST: N/A"
        val closingLabel = detection.closingSpeedMetersPerSecond?.let { "CLOSING: ${String.format("%.1f", it)} m/s" } ?: "CLOSING: N/A"
        val ttcLabel = detection.ttcSeconds?.let { "TTC: ~${String.format("%.1f", it)} s" } ?: "TTC: N/A"
        val riskLabel = "RISK: ${riskLabel(detection.riskLevel)}"
        return listOfNotNull(label, pathLabel, distanceLabel, closingLabel, ttcLabel, riskLabel, qualityLabel(detection))
    }

    private fun qualityLabel(detection: AnimalDetection): String? {
        val weakVisibility = detection.visibilityState == VisibilityState.NIGHT ||
            detection.visibilityState == VisibilityState.BLURRY ||
            detection.visibilityState == VisibilityState.POOR_VISIBILITY ||
            detection.visibilityQuality < 0.55f
        val weakDistance = detection.distanceQuality != null && detection.distanceQuality < 0.40f
        return when {
            weakVisibility && weakDistance -> "QUALITY: LOW"
            weakVisibility -> "VIS: ${detection.visibilityState.name}"
            weakDistance -> "DIST Q: LOW"
            else -> null
        }
    }

    private fun placeLabel(box: RectF, lines: List<String>, occupiedLabels: List<RectF>): RectF {
        val labelWidth = (lines.maxOfOrNull { line -> textWidth(line) } ?: 0f) + LABEL_PADDING * 2f
        val labelHeight = LABEL_PADDING * 2f + lines.size * LINE_HEIGHT
        val clampedWidth = min(labelWidth, width - LABEL_MARGIN * 2f)
        val startLeft = box.left.coerceIn(LABEL_MARGIN, width - clampedWidth - LABEL_MARGIN)
        val candidates = mutableListOf<RectF>()
        val preferredTops = listOf(
            box.top - labelHeight - 6f,
            box.bottom + 6f,
            box.centerY() - labelHeight / 2f,
            LABEL_MARGIN
        )
        preferredTops.forEach { topCandidate ->
            val top = topCandidate.coerceIn(LABEL_MARGIN, height - labelHeight - LABEL_MARGIN)
            candidates.add(RectF(startLeft, top, startLeft + clampedWidth, top + labelHeight))
        }
        var scanTop = LABEL_MARGIN
        while (scanTop + labelHeight <= height - LABEL_MARGIN) {
            candidates.add(RectF(startLeft, scanTop, startLeft + clampedWidth, scanTop + labelHeight))
            scanTop += labelHeight + LABEL_MARGIN
        }
        return candidates.firstOrNull { candidate ->
            occupiedLabels.none { occupied -> RectF.intersects(occupied, expanded(candidate, 4f)) }
        } ?: candidates.first()
    }

    private fun expanded(rect: RectF, margin: Float): RectF {
        return RectF(rect.left - margin, rect.top - margin, rect.right + margin, rect.bottom + margin)
    }

    private fun textWidth(line: String): Float {
        return max(labelTextPaint.measureText(line), detailTextPaint.measureText(line))
    }

    private fun colorForRisk(riskLevel: RiskLevel): Int {
        return when (riskLevel) {
            RiskLevel.HIGH -> Color.rgb(220, 38, 38)
            RiskLevel.MEDIUM -> Color.rgb(245, 190, 35)
            RiskLevel.LOW -> Color.rgb(34, 197, 94)
            RiskLevel.UNKNOWN -> Color.rgb(34, 197, 94)
        }
    }

    private fun riskLabel(riskLevel: RiskLevel): String {
        return when (riskLevel) {
            RiskLevel.UNKNOWN -> "N/A"
            else -> riskLevel.name
        }
    }

    private fun isChecking(detection: AnimalDetection): Boolean {
        return detection.trackId != null && detection.trackAgeFrames in 1 until CHECKING_TRACK_FRAMES
    }

    companion object {
        private const val TAG = "DetectionOverlay"
        private const val LABEL_PADDING = 8f
        private const val LABEL_MARGIN = 8f
        private const val LABEL_CORNER_RADIUS = 6f
        private const val LINE_HEIGHT = 23f
        private const val SHOW_DEBUG_OVERLAY_DETAILS = false
        private const val CHECKING_TRACK_FRAMES = 3
    }
}
