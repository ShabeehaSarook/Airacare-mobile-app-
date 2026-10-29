package com.airacare.animalsafety

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class PetCaptureRepository(
    private val baseUrl: String = BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun health(onResult: (Boolean, String) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/api/health")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Health check failed", e)
                deliver { onResult(false, e.message ?: "Backend unavailable") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.i(TAG, "Health check response code=${it.code}")
                    deliver { onResult(it.isSuccessful, if (it.isSuccessful) "Backend ready" else "Backend unavailable: ${it.code}") }
                }
            }
        })
    }

    fun removeBackground(label: String, croppedBitmap: Bitmap, onResult: (Result<BackgroundRemovalResult>) -> Unit) {
        val inputBytes = croppedBitmap.toPngBytes()
        Log.i(TAG, "removeBackground start label=$label crop=${croppedBitmap.width}x${croppedBitmap.height} inputPngBytes=${inputBytes.size}")
        val dataUrl = "data:image/png;base64,${Base64.encodeToString(inputBytes, Base64.NO_WRAP)}"
        val json = JSONObject()
            .put("label", label)
            .put("image", dataUrl)
        val request = Request.Builder()
            .url("$baseUrl/api/remove-background")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "removeBackground network failed label=$label", e)
                deliver { onResult(Result.failure(IOException(e.message ?: "Background removal failed", e))) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    Log.i(TAG, "removeBackground response code=${it.code} bytes=${body.length}")
                    if (!it.isSuccessful) {
                        Log.e(TAG, "removeBackground failed body=${body.take(MAX_LOG_BODY)}")
                        deliver { onResult(Result.failure(IOException("Background removal failed: HTTP ${it.code} ${body.take(160)}"))) }
                        return
                    }
                    try {
                        val obj = JSONObject(body)
                        if (!obj.optBoolean("ok", false)) {
                            Log.e(TAG, "removeBackground ok=false body=${body.take(MAX_LOG_BODY)}")
                            deliver { onResult(Result.failure(IOException("Background removal failed: ${body.take(160)}"))) }
                            return
                        }
                        val imageData = obj.getString("image").substringAfter(",", obj.getString("image"))
                        val pngBytes = Base64.decode(imageData, Base64.DEFAULT)
                        val result = BackgroundRemovalResult(
                            pngBytes = pngBytes,
                            backgroundRemoved = obj.optBoolean("backgroundRemoved", false),
                            method = obj.optString("method", "unknown")
                        )
                        Log.i(TAG, "removeBackground success label=$label pngBytes=${pngBytes.size} method=${result.method} backgroundRemoved=${result.backgroundRemoved}")
                        deliver { onResult(Result.success(result)) }
                    } catch (exception: Exception) {
                        Log.e(TAG, "removeBackground response invalid body=${body.take(MAX_LOG_BODY)}", exception)
                        deliver { onResult(Result.failure(IOException("Background removal response invalid", exception))) }
                    }
                }
            }
        })
    }

    fun uploadCapturedPet(
        label: String,
        ownerId: String,
        pngBytes: ByteArray,
        onResult: (Result<CapturedPetUploadResult>) -> Unit
    ) {
        Log.i(TAG, "uploadCapturedPet start label=$label ownerId=$ownerId pngBytes=${pngBytes.size}")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("label", label)
            .addFormDataPart("ownerId", ownerId)
            .addFormDataPart(
                "image",
                "airacare_${label}_${System.currentTimeMillis()}.png",
                pngBytes.toRequestBody(PNG_MEDIA_TYPE)
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/captured-pets/upload")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "uploadCapturedPet network failed label=$label", e)
                deliver { onResult(Result.failure(IOException(e.message ?: "Upload failed", e))) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string().orEmpty()
                    Log.i(TAG, "uploadCapturedPet response code=${it.code} bytes=${bodyText.length}")
                    if (!it.isSuccessful) {
                        Log.e(TAG, "uploadCapturedPet failed body=${bodyText.take(MAX_LOG_BODY)}")
                        deliver { onResult(Result.failure(IOException("Upload failed: HTTP ${it.code} ${bodyText.take(160)}"))) }
                        return
                    }
                    try {
                        val obj = JSONObject(bodyText)
                        val imageUrl = obj.optString("imageUrl", obj.optString("url", ""))
                        val imagePublicId = obj.optString("imagePublicId", obj.optString("publicId", ""))
                        if (imageUrl.isBlank() || imagePublicId.isBlank()) {
                            throw IOException("Upload response missing imageUrl/imagePublicId")
                        }
                        val result = CapturedPetUploadResult(
                            imageUrl = imageUrl,
                            imagePublicId = imagePublicId,
                            storageProvider = obj.optString("storageProvider", "cloudinary")
                        )
                        Log.i(TAG, "uploadCapturedPet success imagePublicId=${result.imagePublicId} provider=${result.storageProvider}")
                        deliver { onResult(Result.success(result)) }
                    } catch (exception: Exception) {
                        Log.e(TAG, "uploadCapturedPet response invalid body=${bodyText.take(MAX_LOG_BODY)}", exception)
                        deliver { onResult(Result.failure(IOException("Upload response invalid", exception))) }
                    }
                }
            }
        })
    }

    fun deleteCapturedPet(imagePublicId: String, onResult: (Boolean, String) -> Unit) {
        Log.i(TAG, "deleteCapturedPet start imagePublicId=$imagePublicId")
        val json = JSONObject().put("imagePublicId", imagePublicId)
        val request = Request.Builder()
            .url("$baseUrl/api/captured-pets/delete")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "deleteCapturedPet network failed imagePublicId=$imagePublicId", e)
                deliver { onResult(false, e.message ?: "Delete failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    Log.i(TAG, "deleteCapturedPet response code=${it.code} bytes=${body.length}")
                    deliver {
                        onResult(
                            it.isSuccessful,
                            if (it.isSuccessful) "Deleted" else "Delete failed: HTTP ${it.code} ${body.take(160)}"
                        )
                    }
                }
            }
        })
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    private fun deliver(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        private const val BASE_URL = "https://airacare-web-backend.onrender.com"
        private const val TAG = "PetCaptureRepo"
        private const val MAX_LOG_BODY = 800
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PNG_MEDIA_TYPE = "image/png".toMediaType()
    }
}
