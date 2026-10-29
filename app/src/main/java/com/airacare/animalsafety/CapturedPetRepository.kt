package com.airacare.animalsafety

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

class CapturedPetRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun save(record: CapturedPetRecord, onResult: (Boolean, String) -> Unit) {
        Log.i(TAG, "save captured_pet start owner=${record.ownerId} label=${record.label} imagePublicId=${record.imagePublicId}")
        firestore.collection(COLLECTION)
            .add(record.toFirestoreMap())
            .addOnSuccessListener { document ->
                Log.i(TAG, "save captured_pet success id=${document.id}")
                onResult(true, document.id)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "save captured_pet failed owner=${record.ownerId} label=${record.label}", exception)
                onResult(false, firestoreErrorMessage(exception))
            }
    }

    fun load(ownerId: String, onResult: (Result<List<CapturedPetRecord>>) -> Unit) {
        Log.i(TAG, "load captured_pets owner=$ownerId")
        firestore.collection(COLLECTION)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .addOnSuccessListener { snapshot ->
                val records = snapshot.documents.mapNotNull { document ->
                    val data = document.data ?: return@mapNotNull null
                    data.toCapturedPetRecord(document.id)
                }.sortedByDescending { it.capturedAtEpochMillis }
                Log.i(TAG, "load captured_pets success owner=$ownerId count=${records.size}")
                onResult(Result.success(records))
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "load captured_pets failed owner=$ownerId", exception)
                onResult(Result.failure(exception))
            }
    }

    fun delete(documentId: String, onResult: (Boolean, String) -> Unit) {
        Log.i(TAG, "delete captured_pet Firestore start id=$documentId")
        firestore.collection(COLLECTION)
            .document(documentId)
            .delete()
            .addOnSuccessListener {
                Log.i(TAG, "delete captured_pet Firestore success id=$documentId")
                onResult(true, "Deleted")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "delete captured_pet Firestore failed id=$documentId", exception)
                onResult(false, firestoreErrorMessage(exception))
            }
    }

    private fun CapturedPetRecord.toFirestoreMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "ownerId" to ownerId,
            "label" to label,
            "confidence" to confidence.toDouble(),
            "imageUrl" to imageUrl,
            "imagePublicId" to imagePublicId,
            "storageProvider" to storageProvider,
            "capturedAt" to FieldValue.serverTimestamp(),
            "capturedAtEpochMillis" to capturedAtEpochMillis,
            "boundingBox" to mapOf(
                "left" to boundingBox.left.toDouble(),
                "top" to boundingBox.top.toDouble(),
                "right" to boundingBox.right.toDouble(),
                "bottom" to boundingBox.bottom.toDouble()
            ),
            "riskLevel" to riskLevel.name,
            "cameraSource" to cameraSource,
            "source" to source,
            "backgroundRemoved" to backgroundRemoved,
            "backgroundRemovalMethod" to backgroundRemovalMethod
        )
        distanceMeters?.let { map["distanceMeters"] = it.toDouble() }
        trackId?.let { map["trackId"] = it }
        return map
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toCapturedPetRecord(documentId: String): CapturedPetRecord {
        val box = this["boundingBox"] as? Map<String, Any?>
        return CapturedPetRecord(
            documentId = documentId,
            ownerId = this["ownerId"] as? String ?: "",
            label = this["label"] as? String ?: "unknown",
            confidence = (this["confidence"] as? Number)?.toFloat() ?: 0f,
            imageUrl = this["imageUrl"] as? String ?: "",
            imagePublicId = this["imagePublicId"] as? String ?: "",
            storageProvider = this["storageProvider"] as? String ?: "",
            capturedAtEpochMillis = (this["capturedAtEpochMillis"] as? Number)?.toLong() ?: 0L,
            boundingBox = DetectionBox(
                left = (box?.get("left") as? Number)?.toFloat() ?: 0f,
                top = (box?.get("top") as? Number)?.toFloat() ?: 0f,
                right = (box?.get("right") as? Number)?.toFloat() ?: 0f,
                bottom = (box?.get("bottom") as? Number)?.toFloat() ?: 0f
            ),
            distanceMeters = (this["distanceMeters"] as? Number)?.toFloat(),
            riskLevel = runCatching { RiskLevel.valueOf(this["riskLevel"] as? String ?: RiskLevel.UNKNOWN.name) }.getOrDefault(RiskLevel.UNKNOWN),
            cameraSource = this["cameraSource"] as? String ?: "android_camera",
            source = this["source"] as? String ?: "android_capture_store",
            backgroundRemoved = this["backgroundRemoved"] as? Boolean ?: false,
            backgroundRemovalMethod = this["backgroundRemovalMethod"] as? String ?: "",
            trackId = (this["trackId"] as? Number)?.toInt()
        )
    }

    companion object {
        private const val TAG = "CapturedPetRepo"
        private const val COLLECTION = "captured_pets"

        fun firestoreErrorMessage(exception: Exception): String {
            val code = (exception as? FirebaseFirestoreException)?.code
            return if (code != null) {
                "Firestore save failed: $code ${exception.message ?: ""}".trim()
            } else {
                exception.message ?: "Firestore save failed"
            }
        }
    }
}
