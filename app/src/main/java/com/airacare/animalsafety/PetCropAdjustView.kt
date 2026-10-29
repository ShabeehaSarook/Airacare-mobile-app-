package com.airacare.animalsafety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PetCropAdjustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val imageRect = RectF()
    private val cropRect = RectF()
    private val cropViewRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var activeMode = Mode.NONE
    private var lastBitmapX = 0f
    private var lastBitmapY = 0f
    private val handleRadius = 18f * resources.displayMetrics.density
    private val touchSlop = 36f * resources.displayMetrics.density

    fun setImageAndCrop(source: Bitmap, initialCrop: Rect) {
        bitmap = source
        cropRect.set(
            initialCrop.left.toFloat(),
            initialCrop.top.toFloat(),
            initialCrop.right.toFloat(),
            initialCrop.bottom.toFloat()
        )
        clampCropRect()
        invalidate()
    }

    fun selectedCropRect(): Rect {
        clampCropRect()
        return Rect(
            cropRect.left.roundToInt(),
            cropRect.top.roundToInt(),
            cropRect.right.roundToInt(),
            cropRect.bottom.roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        updateImageRect(source)
        canvas.drawBitmap(source, null, imageRect, imagePaint)
        updateCropViewRect()
        drawOverlay(canvas)
        canvas.drawRect(cropViewRect, borderPaint)
        drawHandle(canvas, cropViewRect.left, cropViewRect.top)
        drawHandle(canvas, cropViewRect.right, cropViewRect.top)
        drawHandle(canvas, cropViewRect.left, cropViewRect.bottom)
        drawHandle(canvas, cropViewRect.right, cropViewRect.bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = bitmap ?: return false
        updateImageRect(source)
        updateCropViewRect()
        val bitmapX = viewToBitmapX(event.x).coerceIn(0f, source.width.toFloat())
        val bitmapY = viewToBitmapY(event.y).coerceIn(0f, source.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeMode = pickMode(event.x, event.y)
                lastBitmapX = bitmapX
                lastBitmapY = bitmapY
                return activeMode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeMode == Mode.NONE) return false
                val dx = bitmapX - lastBitmapX
                val dy = bitmapY - lastBitmapY
                applyMove(activeMode, dx, dy)
                lastBitmapX = bitmapX
                lastBitmapY = bitmapY
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeMode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawOverlay(canvas: Canvas) {
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropViewRect.top, overlayPaint)
        canvas.drawRect(imageRect.left, cropViewRect.bottom, imageRect.right, imageRect.bottom, overlayPaint)
        canvas.drawRect(imageRect.left, cropViewRect.top, cropViewRect.left, cropViewRect.bottom, overlayPaint)
        canvas.drawRect(cropViewRect.right, cropViewRect.top, imageRect.right, cropViewRect.bottom, overlayPaint)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
    }

    private fun updateImageRect(source: Bitmap) {
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return
        scale = min(width / source.width.toFloat(), height / source.height.toFloat())
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun updateCropViewRect() {
        cropViewRect.set(
            imageRect.left + cropRect.left * scale,
            imageRect.top + cropRect.top * scale,
            imageRect.left + cropRect.right * scale,
            imageRect.top + cropRect.bottom * scale
        )
    }

    private fun viewToBitmapX(x: Float): Float = (x - imageRect.left) / scale

    private fun viewToBitmapY(y: Float): Float = (y - imageRect.top) / scale

    private fun pickMode(x: Float, y: Float): Mode {
        val nearLeft = abs(x - cropViewRect.left) <= touchSlop
        val nearRight = abs(x - cropViewRect.right) <= touchSlop
        val nearTop = abs(y - cropViewRect.top) <= touchSlop
        val nearBottom = abs(y - cropViewRect.bottom) <= touchSlop
        return when {
            nearLeft && nearTop -> Mode.TOP_LEFT
            nearRight && nearTop -> Mode.TOP_RIGHT
            nearLeft && nearBottom -> Mode.BOTTOM_LEFT
            nearRight && nearBottom -> Mode.BOTTOM_RIGHT
            cropViewRect.contains(x, y) -> Mode.DRAG
            else -> Mode.NONE
        }
    }

    private fun applyMove(mode: Mode, dx: Float, dy: Float) {
        when (mode) {
            Mode.DRAG -> cropRect.offset(dx, dy)
            Mode.TOP_LEFT -> {
                cropRect.left += dx
                cropRect.top += dy
            }
            Mode.TOP_RIGHT -> {
                cropRect.right += dx
                cropRect.top += dy
            }
            Mode.BOTTOM_LEFT -> {
                cropRect.left += dx
                cropRect.bottom += dy
            }
            Mode.BOTTOM_RIGHT -> {
                cropRect.right += dx
                cropRect.bottom += dy
            }
            Mode.NONE -> return
        }
        clampCropRect()
    }

    private fun clampCropRect() {
        val source = bitmap ?: return
        val minSide = MIN_CROP_SIDE_PX.toFloat()
        if (cropRect.width() < minSide) {
            val cx = cropRect.centerX()
            cropRect.left = cx - minSide / 2f
            cropRect.right = cx + minSide / 2f
        }
        if (cropRect.height() < minSide) {
            val cy = cropRect.centerY()
            cropRect.top = cy - minSide / 2f
            cropRect.bottom = cy + minSide / 2f
        }
        if (cropRect.left < 0f) cropRect.offset(-cropRect.left, 0f)
        if (cropRect.top < 0f) cropRect.offset(0f, -cropRect.top)
        if (cropRect.right > source.width) cropRect.offset(source.width - cropRect.right, 0f)
        if (cropRect.bottom > source.height) cropRect.offset(0f, source.height - cropRect.bottom)
        cropRect.left = max(0f, cropRect.left)
        cropRect.top = max(0f, cropRect.top)
        cropRect.right = min(source.width.toFloat(), cropRect.right)
        cropRect.bottom = min(source.height.toFloat(), cropRect.bottom)
    }

    private enum class Mode {
        NONE,
        DRAG,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    companion object {
        private const val MIN_CROP_SIDE_PX = 32
    }
}
