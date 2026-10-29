package com.airacare.animalsafety

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DashcamMjpegStreamClient(
    private val streamUrl: String,
    private val minFrameIntervalMs: Long = DEFAULT_MIN_FRAME_INTERVAL_MS,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    @Volatile
    private var connection: HttpURLConnection? = null
    @Volatile
    private var inputStream: InputStream? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadExecutor()
        executor?.execute { readLoop() }
    }

    fun stop() {
        running.set(false)
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        connection?.disconnect()
        executor?.shutdownNow()
        executor = null
        inputStream = null
        connection = null
    }

    private fun readLoop() {
        try {
            onStatus("Connecting dashcam")
            val openedConnection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                doInput = true
                setRequestProperty("Connection", "close")
            }
            connection = openedConnection
            openedConnection.connect()

            if (openedConnection.responseCode !in 200..299) {
                throw IllegalStateException("Dashcam stream returned HTTP ${openedConnection.responseCode}")
            }

            val stream = openedConnection.inputStream
            inputStream = stream
            onStatus("Dashcam connected")
            readJpegFrames(stream)
        } catch (exception: Exception) {
            if (running.get()) {
                Log.e(TAG, "Dashcam stream failed", exception)
                onError(exception)
            }
        } finally {
            stop()
        }
    }

    private fun readJpegFrames(stream: InputStream) {
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        val frameBuffer = ByteArrayOutputStream(MAX_BUFFER_BYTES)
        var lastFrameAt = 0L

        while (running.get()) {
            val read = stream.read(readBuffer)
            if (read < 0) break
            frameBuffer.write(readBuffer, 0, read)

            var bytes = frameBuffer.toByteArray()
            var start = indexOfJpegStart(bytes)
            var end = if (start >= 0) indexOfJpegEnd(bytes, start + 2) else -1

            while (start >= 0 && end > start && running.get()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt >= minFrameIntervalMs) {
                    val frameBytes = bytes.copyOfRange(start, end + 2)
                    BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)?.let { bitmap ->
                        lastFrameAt = now
                        onFrame(bitmap)
                    }
                }

                val remaining = bytes.copyOfRange(end + 2, bytes.size)
                frameBuffer.reset()
                frameBuffer.write(remaining)
                bytes = frameBuffer.toByteArray()
                start = indexOfJpegStart(bytes)
                end = if (start >= 0) indexOfJpegEnd(bytes, start + 2) else -1
            }

            if (frameBuffer.size() > MAX_BUFFER_BYTES) {
                frameBuffer.reset()
            }
        }
    }

    private fun indexOfJpegStart(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == JPEG_MARKER && bytes[index + 1] == JPEG_START) return index
        }
        return -1
    }

    private fun indexOfJpegEnd(bytes: ByteArray, fromIndex: Int): Int {
        for (index in fromIndex until bytes.size - 1) {
            if (bytes[index] == JPEG_MARKER && bytes[index + 1] == JPEG_END) return index
        }
        return -1
    }

    companion object {
        private const val TAG = "DashcamMjpegClient"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 15000
        private const val READ_BUFFER_SIZE = 8192
        private const val MAX_BUFFER_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_MIN_FRAME_INTERVAL_MS = 200L
        private const val JPEG_MARKER = 0xFF.toByte()
        private const val JPEG_START = 0xD8.toByte()
        private const val JPEG_END = 0xD9.toByte()
    }
}
