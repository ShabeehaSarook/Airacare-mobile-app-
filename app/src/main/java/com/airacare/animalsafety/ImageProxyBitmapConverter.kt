package com.airacare.animalsafety

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

object ImageProxyBitmapConverter {
    private var reusablePixels = IntArray(0)
    private var reusableBitmap: Bitmap? = null

    @Synchronized
    fun toBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val pixelCount = width * height
        if (reusablePixels.size != pixelCount) {
            reusablePixels = IntArray(pixelCount)
        }
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        buffer.rewind()
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + x * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = if (pixelStride > 3) buffer.get(offset + 3).toInt() and 0xFF else 0xFF
                reusablePixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = reusableBitmap?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
        bitmap.setPixels(reusablePixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
