package com.airacare.animalsafety

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordedVideoFramePlayer(
    private val context: Context,
    private val videoUri: Uri,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onComplete: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadExecutor()
        executor?.execute { playLoop() }
    }

    fun stop() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
    }

    private fun playLoop() {
        val retriever = MediaMetadataRetriever()
        try {
            onStatus("Opening recorded video")
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw IllegalStateException("Could not read video duration")

            if (durationMs <= 0L) {
                throw IllegalStateException("Video duration is empty")
            }

            onStatus("Recorded video ready")
            var currentMs = 0L
            while (running.get() && currentMs <= durationMs) {
                val startedAt = SystemClock.elapsedRealtime()
                val bitmap = retriever.getFrameAtTime(
                    currentMs * MICROSECONDS_PER_MILLISECOND,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null && running.get()) {
                    onFrame(bitmap)
                }
                currentMs += frameIntervalMs

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val sleepMs = (frameIntervalMs - elapsed).coerceAtLeast(0L)
                if (sleepMs > 0L) {
                    Thread.sleep(sleepMs)
                }
            }
            if (running.get()) {
                onComplete()
            }
        } catch (exception: Exception) {
            if (running.get()) {
                Log.e(TAG, "Recorded video playback failed", exception)
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
        private const val TAG = "RecordedVideoPlayer"
        private const val DEFAULT_FRAME_INTERVAL_MS = 200L
        private const val MICROSECONDS_PER_MILLISECOND = 1000L
    }
}
