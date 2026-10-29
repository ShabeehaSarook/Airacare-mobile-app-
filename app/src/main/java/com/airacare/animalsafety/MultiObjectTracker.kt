package com.airacare.animalsafety

import android.util.Log
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MultiObjectTracker {
    private val tracks = mutableListOf<Track>()
    private val removedTrackIds = mutableSetOf<Int>()
    private var nextTrackId = 1
    private var frameIndex = 0L

    fun update(detections: List<AnimalDetection>): List<AnimalDetection> {
        frameIndex++
        tracks.forEach { it.missedFrames++ }

        val matchedTrackIds = mutableSetOf<Int>()
        val trackedDetections = detections.map { detection ->
            if (!isTrackable(detection.label)) {
                detection
            } else {
                val track = matchTrack(detection, matchedTrackIds)
                    ?: createTrack(detection)
                Log.d(TAG, "TRACK MATCH track=${track.id} class=${track.label} confidence=${String.format("%.3f", detection.confidence)} missedFrames=${track.missedFrames} frame=$frameIndex")
                track.update(detection, frameIndex)
                matchedTrackIds.add(track.id)
                detection.copy(trackId = track.id, trackAgeFrames = track.hitCount, boundingBox = track.box)
            }
        }

        val staleTracks = tracks.filter { it.missedFrames > MAX_MISSED_FRAMES }
        removedTrackIds.addAll(staleTracks.map { it.id })
        staleTracks.forEach { track ->
            Log.i(TAG, "TRACK REMOVE track=${track.id} class=${track.label} missedFrames=${track.missedFrames} frame=$frameIndex")
        }
        tracks.removeAll(staleTracks.toSet())
        Log.d(
            TAG,
            "frame=$frameIndex detections=${detections.size} tracked=${trackedDetections.count { it.trackId != null }} activeTracks=${tracks.size} removedStale=${staleTracks.size}"
        )
        return trackedDetections
    }

    fun consumeRemovedTrackIds(): Set<Int> {
        val removed = removedTrackIds.toSet()
        removedTrackIds.clear()
        return removed
    }

    private fun matchTrack(detection: AnimalDetection, matchedTrackIds: Set<Int>): Track? {
        val candidates = tracks
            .filter { it.id !in matchedTrackIds }
            .filter { it.label.equals(detection.label, ignoreCase = true) }
            .map { track ->
                val iouScore = iou(track.box, detection.boundingBox)
                val centerDistanceScore = normalizedCenterDistance(track.box, detection.boundingBox)
                val score = iouScore - centerDistanceScore * CENTER_DISTANCE_WEIGHT
                Match(track, iouScore, centerDistanceScore, score)
            }
            .filter { it.iou >= MIN_IOU || it.centerDistance <= MAX_NORMALIZED_CENTER_DISTANCE }
            .sortedByDescending { it.score }

        return candidates.firstOrNull()?.track
    }

    private fun createTrack(detection: AnimalDetection): Track {
        val track = Track(
            id = nextTrackId++,
            label = detection.label,
            box = detection.boundingBox,
            confidence = detection.confidence,
            lastSeenFrame = frameIndex,
            missedFrames = 0,
            hitCount = 0
        )
        tracks.add(track)
        Log.i(TAG, "TRACK CREATE track=${track.id} class=${track.label} confidence=${String.format("%.3f", track.confidence)} frame=$frameIndex")
        return track
    }

    private fun isTrackable(label: String): Boolean {
        return label.lowercase() in TRACKED_LABELS
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun normalizedCenterDistance(a: DetectionBox, b: DetectionBox): Float {
        val distance = hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble()).toFloat()
        val reference = max(max(a.width, a.height), max(b.width, b.height)).coerceAtLeast(1f)
        return distance / reference
    }

    private data class Track(
        val id: Int,
        val label: String,
        var box: DetectionBox,
        var confidence: Float,
        var lastSeenFrame: Long,
        var missedFrames: Int,
        var hitCount: Int
    ) {
        fun update(detection: AnimalDetection, frameIndex: Long) {
            box = smoothBox(box, detection.boundingBox)
            confidence = detection.confidence
            lastSeenFrame = frameIndex
            missedFrames = 0
            hitCount++
        }

        private fun smoothBox(previous: DetectionBox, current: DetectionBox): DetectionBox {
            return DetectionBox(
                left = previous.left * BOX_SMOOTHING + current.left * (1f - BOX_SMOOTHING),
                top = previous.top * BOX_SMOOTHING + current.top * (1f - BOX_SMOOTHING),
                right = previous.right * BOX_SMOOTHING + current.right * (1f - BOX_SMOOTHING),
                bottom = previous.bottom * BOX_SMOOTHING + current.bottom * (1f - BOX_SMOOTHING)
            )
        }
    }

    private data class Match(
        val track: Track,
        val iou: Float,
        val centerDistance: Float,
        val score: Float
    )

    companion object {
        private const val TAG = "MultiObjectTracker"
        private const val MAX_MISSED_FRAMES = 12
        private const val MIN_IOU = 0.20f
        private const val MAX_NORMALIZED_CENTER_DISTANCE = 0.75f
        private const val CENTER_DISTANCE_WEIGHT = 0.15f
        private const val BOX_SMOOTHING = 0.65f
        private val TRACKED_LABELS = setOf(
            "person",
            "dog",
            "cat",
            "horse",
            "cow",
            "deer",
            "goat",
            "elephant"
        )
    }
}



