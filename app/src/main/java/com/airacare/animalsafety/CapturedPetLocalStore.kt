package com.airacare.animalsafety

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class CapturedPetLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(record: CapturedPetRecord) {
        val records = load().filterNot { it.imagePublicId == record.imagePublicId }.toMutableList()
        records.add(record.copy(documentId = localDocumentId(record.imagePublicId)))
        persist(records.sortedByDescending { it.capturedAtEpochMillis })
        Log.i(TAG, "local captured_pet saved label=${record.label} imagePublicId=${record.imagePublicId}")
    }

    fun load(): List<CapturedPetRecord> {
        val raw = prefs.getString(KEY_RECORDS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toRecord() }
        }.getOrElse { exception ->
            Log.e(TAG, "local captured_pet load failed", exception)
            emptyList()
        }
    }

    fun delete(imagePublicId: String) {
        val remaining = load().filterNot { it.imagePublicId == imagePublicId }
        persist(remaining)
        Log.i(TAG, "local captured_pet deleted imagePublicId=$imagePublicId")
    }

    private fun persist(records: List<CapturedPetRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(record.toJson()) }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun CapturedPetRecord.toJson(): JSONObject {
        return JSONObject()
            .put("documentId", documentId ?: localDocumentId(imagePublicId))
            .put("ownerId", ownerId)
            .put("label", label)
            .put("confidence", confidence.toDouble())
            .put("imageUrl", imageUrl)
            .put("imagePublicId", imagePublicId)
            .put("storageProvider", storageProvider)
            .put("capturedAtEpochMillis", capturedAtEpochMillis)
            .put("boundingBox", JSONObject()
                .put("left", boundingBox.left.toDouble())
                .put("top", boundingBox.top.toDouble())
                .put("right", boundingBox.right.toDouble())
                .put("bottom", boundingBox.bottom.toDouble())
            )
            .put("distanceMeters", distanceMeters?.toDouble() ?: JSONObject.NULL)
            .put("riskLevel", riskLevel.name)
            .put("cameraSource", cameraSource)
            .put("source", source)
            .put("backgroundRemoved", backgroundRemoved)
            .put("backgroundRemovalMethod", backgroundRemovalMethod)
            .put("trackId", trackId ?: JSONObject.NULL)
    }

    private fun JSONObject.toRecord(): CapturedPetRecord {
        val box = optJSONObject("boundingBox") ?: JSONObject()
        return CapturedPetRecord(
            documentId = optString("documentId", localDocumentId(optString("imagePublicId", ""))),
            ownerId = optString("ownerId", ""),
            label = optString("label", "unknown"),
            confidence = optDouble("confidence", 0.0).toFloat(),
            imageUrl = optString("imageUrl", ""),
            imagePublicId = optString("imagePublicId", ""),
            storageProvider = optString("storageProvider", "cloudinary"),
            capturedAtEpochMillis = optLong("capturedAtEpochMillis", 0L),
            boundingBox = DetectionBox(
                left = box.optDouble("left", 0.0).toFloat(),
                top = box.optDouble("top", 0.0).toFloat(),
                right = box.optDouble("right", 0.0).toFloat(),
                bottom = box.optDouble("bottom", 0.0).toFloat()
            ),
            distanceMeters = optNullableDouble("distanceMeters")?.toFloat(),
            riskLevel = runCatching { RiskLevel.valueOf(optString("riskLevel", RiskLevel.UNKNOWN.name)) }.getOrDefault(RiskLevel.UNKNOWN),
            cameraSource = optString("cameraSource", "android_camera"),
            source = optString("source", "android_capture_store"),
            backgroundRemoved = optBoolean("backgroundRemoved", false),
            backgroundRemovalMethod = optString("backgroundRemovalMethod", ""),
            trackId = optNullableInt("trackId")
        )
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        return if (isNull(name)) null else optDouble(name)
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (isNull(name)) null else optInt(name)
    }

    companion object {
        private const val TAG = "CapturedPetLocalStore"
        private const val PREFS_NAME = "captured_pet_local_store"
        private const val KEY_RECORDS = "records"
        const val LOCAL_ID_PREFIX = "local:"

        fun localDocumentId(imagePublicId: String): String = "$LOCAL_ID_PREFIX$imagePublicId"
        fun isLocalDocumentId(documentId: String?): Boolean = documentId?.startsWith(LOCAL_ID_PREFIX) == true
    }
}
