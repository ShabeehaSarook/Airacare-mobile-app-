package com.airacare.animalsafety

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PetCropper {
    private const val PADDING_RATIO = 0.15f
    private const val MIN_CROP_SIDE_PX = 32
    private const val MAX_CROP_AREA_RATIO = 0.98f
    private const val MAX_OUTPUT_SIDE_PX = 1536

    fun crop(frame: Bitmap, detection: AnimalDetection): Bitmap? {
        return cropWithReason(frame, detection).bitmap
    }

    fun cropWithReason(frame: Bitmap, detection: AnimalDetection): CropResult {
        val cropBox = defaultCropRect(frame, detection)
        val rect = cropBox.rect ?: return CropResult(null, cropBox.reason)
        return cropRectWithReason(frame, rect)
    }

    fun defaultCropRect(frame: Bitmap, detection: AnimalDetection): CropBoxResult {
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return CropBoxResult(null, "invalid_frame")
        val box = detection.boundingBox
        if (box.width <= 0f || box.height <= 0f) return CropBoxResult(null, "invalid_box")

        val scaleX = frame.width.toFloat() / detection.imageWidth.coerceAtLeast(1)
        val scaleY = frame.height.toFloat() / detection.imageHeight.coerceAtLeast(1)
        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY
        val width = right - left
        val height = bottom - top
        if (width < MIN_CROP_SIDE_PX || height < MIN_CROP_SIDE_PX) {
            return CropBoxResult(null, "crop_too_small width=$width height=$height")
        }

        val padX = width * PADDING_RATIO
        val padY = height * PADDING_RATIO
        val cropLeft = (left - padX).roundToInt().coerceIn(0, frame.width - 1)
        val cropTop = (top - padY).roundToInt().coerceIn(0, frame.height - 1)
        val cropRight = (right + padX).roundToInt().coerceIn(cropLeft + 1, frame.width)
        val cropBottom = (bottom + padY).roundToInt().coerceIn(cropTop + 1, frame.height)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth < MIN_CROP_SIDE_PX || cropHeight < MIN_CROP_SIDE_PX) {
            return CropBoxResult(null, "clamped_crop_too_small width=$cropWidth height=$cropHeight")
        }

        val frameArea = frame.width.toFloat() * frame.height.toFloat()
        val cropArea = cropWidth.toFloat() * cropHeight.toFloat()
        if (cropArea / frameArea > MAX_CROP_AREA_RATIO) {
            return CropBoxResult(null, "crop_too_large ratio=${cropArea / frameArea}")
        }

        return CropBoxResult(Rect(cropLeft, cropTop, cropRight, cropBottom), "ok rect=${cropWidth}x$cropHeight frame=${frame.width}x${frame.height}")
    }

    fun cropRectWithReason(frame: Bitmap, rect: Rect): CropResult {
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return CropResult(null, "invalid_frame")
        val cropLeft = rect.left.coerceIn(0, frame.width - 1)
        val cropTop = rect.top.coerceIn(0, frame.height - 1)
        val cropRight = rect.right.coerceIn(cropLeft + 1, frame.width)
        val cropBottom = rect.bottom.coerceIn(cropTop + 1, frame.height)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth < MIN_CROP_SIDE_PX || cropHeight < MIN_CROP_SIDE_PX) {
            return CropResult(null, "crop_too_small width=$cropWidth height=$cropHeight")
        }

        val frameArea = frame.width.toFloat() * frame.height.toFloat()
        val cropArea = cropWidth.toFloat() * cropHeight.toFloat()
        if (cropArea / frameArea > MAX_CROP_AREA_RATIO) {
            return CropResult(null, "crop_too_large ratio=${cropArea / frameArea}")
        }

        val cropped = Bitmap.createBitmap(frame, cropLeft, cropTop, cropWidth, cropHeight)
        val maxSide = max(cropWidth, cropHeight)
        if (maxSide <= MAX_OUTPUT_SIDE_PX) return CropResult(cropped, "ok crop=${cropWidth}x$cropHeight frame=${frame.width}x${frame.height}")

        val resizeScale = MAX_OUTPUT_SIDE_PX.toFloat() / maxSide
        val resizedWidth = max(1, (cropWidth * resizeScale).roundToInt())
        val resizedHeight = max(1, (cropHeight * resizeScale).roundToInt())
        val resized = Bitmap.createScaledBitmap(cropped, resizedWidth, resizedHeight, true)
        if (resized !== cropped) cropped.recycle()
        return CropResult(resized, "ok_resized crop=${cropWidth}x$cropHeight resized=${resizedWidth}x$resizedHeight frame=${frame.width}x${frame.height}")
    }

    data class CropResult(
        val bitmap: Bitmap?,
        val reason: String
    )

    data class CropBoxResult(
        val rect: Rect?,
        val reason: String
    )
}
