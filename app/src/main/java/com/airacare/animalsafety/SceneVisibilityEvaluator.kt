package com.airacare.animalsafety

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.sqrt

enum class VisibilityState {
    DAY,
    NORMAL,
    LOW_LIGHT,
    NIGHT,
    BLURRY,
    POOR_VISIBILITY
}

data class SceneVisibility(
    val state: VisibilityState,
    val quality: Float,
    val meanBrightness: Float,
    val contrast: Float,
    val blurScore: Float,
    val enhancementEnabled: Boolean = false
) {
    val isPoor: Boolean
        get() = state == VisibilityState.NIGHT || state == VisibilityState.BLURRY || state == VisibilityState.POOR_VISIBILITY

    companion object {
        val DEFAULT = SceneVisibility(
            state = VisibilityState.NORMAL,
            quality = 1f,
            meanBrightness = 0f,
            contrast = 0f,
            blurScore = 0f,
            enhancementEnabled = false
        )
    }
}

object SceneVisibilityMonitor {
    @Volatile
    var current: SceneVisibility = SceneVisibility.DEFAULT
        private set

    fun update(next: SceneVisibility) {
        current = next
    }
}

class SceneVisibilityEvaluator {
    fun evaluate(bitmap: Bitmap, enhancementEnabled: Boolean = false): SceneVisibility {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 1 || height <= 1) return SceneVisibility.DEFAULT

        val step = 8
        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var gradientSum = 0.0
        var gradientCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val luminance = luminance(bitmap.getPixel(x, y))
                sum += luminance
                sumSquares += luminance * luminance
                count++
                if (x + step < width && y + step < height) {
                    val right = luminance(bitmap.getPixel(x + step, y))
                    val down = luminance(bitmap.getPixel(x, y + step))
                    gradientSum += kotlin.math.abs(luminance - right) + kotlin.math.abs(luminance - down)
                    gradientCount += 2
                }
                x += step
            }
            y += step
        }

        val mean = (sum / count).toFloat()
        val variance = ((sumSquares / count) - (mean * mean)).coerceAtLeast(0.0).toFloat()
        val contrast = sqrt(variance)
        val blurScore = if (gradientCount > 0) (gradientSum / gradientCount).toFloat() else 0f
        val state = classify(mean, contrast, blurScore)
        val quality = qualityFor(state, mean, contrast, blurScore)
        return SceneVisibility(state, quality, mean, contrast, blurScore, enhancementEnabled)
    }

    private fun classify(mean: Float, contrast: Float, blurScore: Float): VisibilityState {
        return when {
            mean < 28f -> VisibilityState.NIGHT
            blurScore < 3.0f && contrast < 24f -> VisibilityState.POOR_VISIBILITY
            blurScore < 2.2f -> VisibilityState.BLURRY
            mean < 70f -> VisibilityState.LOW_LIGHT
            mean > 145f && contrast > 32f -> VisibilityState.DAY
            else -> VisibilityState.NORMAL
        }
    }

    private fun qualityFor(state: VisibilityState, mean: Float, contrast: Float, blurScore: Float): Float {
        val brightnessQuality = (mean / 110f).coerceIn(0f, 1f)
        val contrastQuality = (contrast / 42f).coerceIn(0f, 1f)
        val blurQuality = (blurScore / 9f).coerceIn(0f, 1f)
        val base = (brightnessQuality * 0.35f) + (contrastQuality * 0.25f) + (blurQuality * 0.40f)
        val cap = when (state) {
            VisibilityState.NIGHT -> 0.45f
            VisibilityState.POOR_VISIBILITY -> 0.50f
            VisibilityState.BLURRY -> 0.60f
            VisibilityState.LOW_LIGHT -> 0.75f
            VisibilityState.DAY,
            VisibilityState.NORMAL -> 1.0f
        }
        return base.coerceIn(0.15f, cap)
    }

    companion object {
        private const val TAG = "SceneVisibility"

        fun log(frameNumber: Long, visibility: SceneVisibility, effectivePersonThreshold: Float, effectiveOtherThreshold: Float) {
            Log.d(
                TAG,
                "frame=$frameNumber brightness=${String.format("%.1f", visibility.meanBrightness)} contrast=${String.format("%.1f", visibility.contrast)} blurScore=${String.format("%.2f", visibility.blurScore)} visibility=${visibility.state} quality=${String.format("%.2f", visibility.quality)} enhancement=${if (visibility.enhancementEnabled) "AUTO" else "OFF"} personThreshold=${String.format("%.2f", effectivePersonThreshold)} otherThreshold=${String.format("%.2f", effectiveOtherThreshold)}"
            )
        }

        private fun luminance(pixel: Int): Float {
            return 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
        }
    }

    private fun luminance(pixel: Int): Float {
        return 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
    }
}
