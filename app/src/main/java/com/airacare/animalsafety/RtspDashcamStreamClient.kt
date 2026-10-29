package com.airacare.animalsafety

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RtspDashcamStreamClient(
    private val context: Context,
    private val streamUrl: String,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadExecutor()
        executor?.execute { readLoop() }
    }

    fun stop() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
    }

    private fun readLoop() {
        val retriever = MediaMetadataRetriever()
        try {
            onStatus("Connecting RTSP dashcam")
            retriever.setDataSource(streamUrl, emptyMap())
            onStatus("RTSP dashcam connected")
            var currentUs = 0L
            while (running.get()) {
                val startedAt = SystemClock.elapsedRealtime()
                val bitmap = retriever.getFrameAtTime(currentUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null && running.get()) {
                    onFrame(bitmap)
                }
                currentUs += frameIntervalMs * MICROSECONDS_PER_MILLISECOND
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val sleepMs = (frameIntervalMs - elapsed).coerceAtLeast(0L)
                if (sleepMs > 0L) Thread.sleep(sleepMs)
            }
        } catch (exception: Exception) {
            if (running.get()) {
                Log.e(TAG, "RTSP dashcam stream failed", exception)
                onError(exception)
            }
        } finally {
            running.set(false)
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "RtspDashcamClient"
        private const val DEFAULT_FRAME_INTERVAL_MS = 200L
        private const val MICROSECONDS_PER_MILLISECOND = 1000L
    }
}
