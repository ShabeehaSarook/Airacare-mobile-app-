package com.airacare.animalsafety

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HazardSharingClient(
    context: Context? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String = DEFAULT_API_KEY
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val offlineQueue: OfflineHazardQueue? = context?.let { OfflineHazardQueue(it) }

    fun submitHazard(candidate: HazardReportCandidate, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            val result = submitHazardBlocking(candidate)
            if (!result.first && offlineQueue != null && candidate.hasUsableLocation) {
                offlineQueue.enqueue(candidate)
                onResult(false, "${result.second}; queued offline (${offlineQueue.size()})")
            } else {
                onResult(result.first, result.second)
            }
        }
    }

    fun syncQueuedHazards(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val queue = offlineQueue ?: run {
            onResult(false, "Offline queue disabled")
            return
        }
        executor.execute {
            val pending = queue.drain()
            if (pending.isEmpty()) {
                onResult(true, "No queued hazards")
                return@execute
            }
            val failed = mutableListOf<HazardReportCandidate>()
            pending.forEach { candidate ->
                val result = submitHazardBlocking(candidate)
                if (!result.first) failed.add(candidate)
            }
            queue.requeue(failed)
            val sent = pending.size - failed.size
            onResult(failed.isEmpty(), "Synced $sent queued hazards, failed ${failed.size}, remaining ${queue.size()}")
        }
    }

    fun fetchNearbyHazards(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = DEFAULT_RADIUS_METERS,
        onResult: (Boolean, List<SharedHazard>, String) -> Unit
    ) {
        executor.execute {
            try {
                syncQueuedHazards()
                val url = "$baseUrl/hazards/nearby?latitude=${encode(latitude.toString())}&longitude=${encode(longitude.toString())}&radius_meters=${encode(radiusMeters.toString())}"
                val connection = openJsonConnection(url, "GET")
                val code = connection.responseCode
                if (code !in 200..299) {
                    connection.disconnect()
                    onResult(false, emptyList(), "Nearby hazard lookup failed HTTP $code")
                    return@execute
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                val hazards = parseHazards(JSONArray(body))
                onResult(true, hazards, "Loaded ${hazards.size} nearby hazards")
            } catch (exception: Exception) {
                Log.e(TAG, "Nearby hazard lookup failed", exception)
                onResult(false, emptyList(), exception.message ?: "Nearby hazard lookup failed")
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun submitHazardBlocking(candidate: HazardReportCandidate): Pair<Boolean, String> {
        return try {
            if (!candidate.hasUsableLocation) {
                return false to "GPS location unavailable"
            }
            if (candidate.riskLevel == RiskLevel.UNKNOWN || candidate.riskLevel == RiskLevel.LOW) {
                return false to "Risk is not reportable"
            }

            val body = JSONObject().apply {
                put("label", candidate.label)
                put("confidence", candidate.confidence.toDouble())
                put("risk_level", candidate.riskLevel.name)
                put("latitude", candidate.latitude)
                put("longitude", candidate.longitude)
                candidate.bearingDegrees?.let { put("bearing_degrees", it.toDouble()) }
                candidate.vehicleSpeedKmh?.let { put("speed_kmh", it.toDouble()) }
                candidate.distanceMeters?.let { put("distance_meters", it.toDouble()) }
                candidate.ttcSeconds?.let { put("ttc_seconds", it.toDouble()) }
                put("note", "android_candidate")
            }

            val connection = openJsonConnection("$baseUrl/hazards", "POST")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            val ok = code in 200..299
            val message = if (ok) "Hazard shared" else "Hazard share failed HTTP $code"
            Log.i(TAG, message)
            connection.disconnect()
            ok to message
        } catch (exception: Exception) {
            Log.e(TAG, "Hazard share failed", exception)
            false to (exception.message ?: "Hazard share failed")
        }
    }

    private fun openJsonConnection(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = method == "POST"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("X-Airacare-Api-Key", apiKey)
        }
    }

    private fun parseHazards(array: JSONArray): List<SharedHazard> {
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            SharedHazard(
                id = item.optString("id"),
                label = item.optString("label"),
                confidence = item.optDouble("confidence", 0.0).toFloat(),
                riskLevel = item.optString("risk_level"),
                latitude = item.optDouble("latitude"),
                longitude = item.optDouble("longitude"),
                bearingDegrees = item.optionalFloat("bearing_degrees"),
                speedKmh = item.optionalFloat("speed_kmh"),
                distanceMeters = item.optionalFloat("distance_meters"),
                ttcSeconds = item.optionalFloat("ttc_seconds"),
                confirmations = item.optInt("confirmations", 0),
                distanceFromQueryMeters = item.optionalFloat("distance_from_query_meters"),
                createdAt = item.optLong("created_at", 0L),
                expiresAt = item.optLong("expires_at", 0L)
            )
        }
    }

    private fun JSONObject.optionalFloat(name: String): Float? {
        return if (has(name) && !isNull(name)) optDouble(name).toFloat() else null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "HazardSharingClient"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 6000
        private const val DEFAULT_RADIUS_METERS = 3000f
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        const val DEFAULT_API_KEY = "airacare-dev-key"
    }
}

data class SharedHazard(
    val id: String,
    val label: String,
    val confidence: Float,
    val riskLevel: String,
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float?,
    val speedKmh: Float?,
    val distanceMeters: Float?,
    val ttcSeconds: Float?,
    val confirmations: Int,
    val distanceFromQueryMeters: Float?,
    val createdAt: Long,
    val expiresAt: Long
)
