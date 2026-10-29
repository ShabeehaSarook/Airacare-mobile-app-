package com.airacare.animalsafety

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OfflineHazardQueue(context: Context) {
    private val queueFile = File(context.applicationContext.getExternalFilesDir(null), QUEUE_FILE_NAME)
    private val lock = Any()

    fun enqueue(candidate: HazardReportCandidate) {
        synchronized(lock) {
            val array = readArray()
            array.put(candidateToJson(candidate))
            queueFile.parentFile?.mkdirs()
            queueFile.writeText(array.toString())
            Log.i(TAG, "Hazard queued offline count=${array.length()}")
        }
    }

    fun drain(maxItems: Int = MAX_DRAIN_ITEMS): List<HazardReportCandidate> {
        synchronized(lock) {
            val array = readArray()
            val items = mutableListOf<HazardReportCandidate>()
            val remaining = JSONArray()
            for (index in 0 until array.length()) {
                val candidate = candidateFromJson(array.optJSONObject(index) ?: continue) ?: continue
                if (items.size < maxItems) items.add(candidate) else remaining.put(candidateToJson(candidate))
            }
            queueFile.writeText(remaining.toString())
            return items
        }
    }

    fun requeue(candidates: List<HazardReportCandidate>) {
        if (candidates.isEmpty()) return
        synchronized(lock) {
            val array = readArray()
            candidates.forEach { array.put(candidateToJson(it)) }
            queueFile.writeText(array.toString())
        }
    }

    fun size(): Int = synchronized(lock) { readArray().length() }

    private fun readArray(): JSONArray {
        return try {
            if (!queueFile.exists()) JSONArray() else JSONArray(queueFile.readText())
        } catch (exception: Exception) {
            Log.w(TAG, "Offline queue was unreadable; starting a new queue", exception)
            JSONArray()
        }
    }

    private fun candidateToJson(candidate: HazardReportCandidate): JSONObject {
        return JSONObject().apply {
            candidate.trackId?.let { put("trackId", it) }
            put("label", candidate.label)
            put("confidence", candidate.confidence.toDouble())
            put("riskLevel", candidate.riskLevel.name)
            put("riskReason", candidate.riskReason)
            put("pathZone", candidate.pathZone.name)
            candidate.distanceMeters?.let { put("distanceMeters", it.toDouble()) }
            candidate.ttcSeconds?.let { put("ttcSeconds", it.toDouble()) }
            candidate.vehicleSpeedKmh?.let { put("vehicleSpeedKmh", it.toDouble()) }
            candidate.latitude?.let { put("latitude", it) }
            candidate.longitude?.let { put("longitude", it) }
            candidate.bearingDegrees?.let { put("bearingDegrees", it.toDouble()) }
            candidate.horizontalAccuracyMeters?.let { put("horizontalAccuracyMeters", it.toDouble()) }
            put("createdAtElapsedMillis", candidate.createdAtElapsedMillis)
        }
    }

    private fun candidateFromJson(json: JSONObject): HazardReportCandidate? {
        return try {
            HazardReportCandidate(
                trackId = if (json.has("trackId") && !json.isNull("trackId")) json.optInt("trackId") else null,
                label = json.getString("label"),
                confidence = json.getDouble("confidence").toFloat(),
                riskLevel = RiskLevel.valueOf(json.getString("riskLevel")),
                riskReason = json.optString("riskReason", "offline_queue"),
                pathZone = runCatching { PathZone.valueOf(json.optString("pathZone", PathZone.UNKNOWN.name)) }.getOrDefault(PathZone.UNKNOWN),
                distanceMeters = json.optionalFloat("distanceMeters"),
                ttcSeconds = json.optionalFloat("ttcSeconds"),
                vehicleSpeedKmh = json.optionalFloat("vehicleSpeedKmh"),
                latitude = if (json.has("latitude") && !json.isNull("latitude")) json.optDouble("latitude") else null,
                longitude = if (json.has("longitude") && !json.isNull("longitude")) json.optDouble("longitude") else null,
                bearingDegrees = json.optionalFloat("bearingDegrees"),
                horizontalAccuracyMeters = json.optionalFloat("horizontalAccuracyMeters"),
                createdAtElapsedMillis = json.optLong("createdAtElapsedMillis", SystemClock.elapsedRealtime())
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Skipping invalid queued hazard", exception)
            null
        }
    }

    private fun JSONObject.optionalFloat(name: String): Float? {
        return if (has(name) && !isNull(name)) optDouble(name).toFloat() else null
    }

    companion object {
        private const val TAG = "OfflineHazardQueue"
        private const val QUEUE_FILE_NAME = "airacare_offline_hazards.json"
        private const val MAX_DRAIN_ITEMS = 20
    }
}
