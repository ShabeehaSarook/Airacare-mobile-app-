package com.airacare.animalsafety

data class CapturedPetRecord(
    val documentId: String? = null,
    val ownerId: String,
    val label: String,
    val confidence: Float,
    val imageUrl: String,
    val imagePublicId: String,
    val storageProvider: String,
    val capturedAtEpochMillis: Long,
    val boundingBox: DetectionBox,
    val distanceMeters: Float?,
    val riskLevel: RiskLevel,
    val cameraSource: String = "android_camera",
    val source: String = "android_capture_store",
    val backgroundRemoved: Boolean,
    val backgroundRemovalMethod: String,
    val trackId: Int?
)

data class BackgroundRemovalResult(
    val pngBytes: ByteArray,
    val backgroundRemoved: Boolean,
    val method: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BackgroundRemovalResult
        return pngBytes.contentEquals(other.pngBytes) &&
            backgroundRemoved == other.backgroundRemoved &&
            method == other.method
    }

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + backgroundRemoved.hashCode()
        result = 31 * result + method.hashCode()
        return result
    }
}

data class CapturedPetUploadResult(
    val imageUrl: String,
    val imagePublicId: String,
    val storageProvider: String
)
