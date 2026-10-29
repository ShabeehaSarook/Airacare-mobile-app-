package com.airacare.animalsafety

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

interface AnimalDetector {
    fun analyze(image: ImageProxy): List<AnimalDetection>

    fun analyze(bitmap: Bitmap): List<AnimalDetection> = emptyList()

    fun close() = Unit
}
