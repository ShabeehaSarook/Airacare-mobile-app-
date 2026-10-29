package com.airacare.animalsafety

object DetectorTimingMonitor {
    @Volatile
    private var latest: DetectorTimingSnapshot = DetectorTimingSnapshot()

    fun update(snapshot: DetectorTimingSnapshot) {
        latest = snapshot
    }

    fun current(): DetectorTimingSnapshot = latest
}

data class DetectorTimingSnapshot(
    val conversionMs: Float? = null,
    val rotateMs: Float = 0f,
    val visibilityMs: Float = 0f,
    val letterboxMs: Float = 0f,
    val preprocessMs: Float = 0f,
    val inferenceMs: Float = 0f,
    val postprocessMs: Float = 0f,
    val detectorTotalMs: Float = 0f,
    val backend: String = "unknown",
    val visibilityState: VisibilityState = VisibilityState.NORMAL,
    val enhancementEnabled: Boolean = false
) {
    fun compactText(): String {
        val convertText = conversionMs?.let { String.format("%.0f", it) } ?: "N/A"
        val enhancementText = if (enhancementEnabled) "ENH:AUTO" else "ENH:OFF"
        return "PRE:$convertText/${String.format("%.0f", preprocessMs)}ms POST:${String.format("%.0f", postprocessMs)}ms DET:${String.format("%.0f", detectorTotalMs)}ms $enhancementText"
    }
}
