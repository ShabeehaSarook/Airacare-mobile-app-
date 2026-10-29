package com.airacare.animalsafety

import androidx.camera.core.ImageProxy

class PlaceholderAnimalDetector : AnimalDetector {
    override fun analyze(image: ImageProxy): List<AnimalDetection> {
        return emptyList()
    }
}
