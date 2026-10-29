package com.airacare.animalsafety

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.max

class WarningManager(
    context: Context,
    private val audioPlayer: AudioAlertPlayer = AudioAlertPlayer(context),
    private val vibrationPlayer: VibrationAlertPlayer = VibrationAlertPlayer(context)
) {
    @Volatile private var enabled: Boolean = false
    private var previousLevel = WarningLevel.NONE
    private val lastAudioTimeByLevel = mutableMapOf(
        WarningLevel.CAUTION to 0L,
        WarningLevel.DANGER to 0L
    )
    private val lastWarningByTrack = mutableMapOf<Int, WarningLevel>()
    private val lastSeenByTrack = mutableMapOf<Int, Long>()
    private val confirmationCountByTrack = mutableMapOf<Int, Int>()

    fun setEnabled(next: Boolean) {
        if (enabled == next) return
        enabled = next
        if (!next) {
            previousLevel = WarningLevel.NONE
            audioPlayer.stop()
            vibrationPlayer.cancel()
            Log.i(TAG, "warning manager disabled")
        } else {
            Log.i(TAG, "warning manager enabled")
        }
    }

    fun update(detections: List<AnimalDetection>): WarningState {
        if (!enabled) {
            previousLevel = WarningLevel.NONE
            audioPlayer.stop()
            vibrationPlayer.cancel()
            return WarningState(reason = "Driving Mode inactive")
        }

        val now = SystemClock.elapsedRealtime()
        cleanupStale(now)
        val threat = selectPrimaryThreat(detections)
        if (threat != null && !warningConfirmed(threat)) {
            Log.d(TAG, "warning suppressed pending confirmation track=${threat.trackId} risk=${threat.riskLevel} confidence=${String.format("%.2f", threat.confidence)} visibility=${threat.visibilityState} quality=${String.format("%.2f", threat.visibilityQuality)}")
            val state = WarningState(level = WarningLevel.NONE, previousLevel = previousLevel, reason = "Waiting for temporal confirmation")
            previousLevel = WarningLevel.NONE
            return state
        }
        if (threat == null) {
            if (previousLevel != WarningLevel.NONE) {
                Log.i(TAG, "warning cleared previous=$previousLevel reason=no_active_warning_level_risk")
            }
            val state = WarningState(level = WarningLevel.NONE, previousLevel = previousLevel, reason = "No active warning-level risk")
            previousLevel = WarningLevel.NONE
            return state
        }

        val level = warningLevelForRisk(threat.riskLevel)
        val previous = previousLevel
        val trackId = threat.trackId
        if (trackId != null) {
            lastSeenByTrack[trackId] = now
            lastWarningByTrack[trackId] = level
        }

        val cooldownRemaining = cooldownRemaining(level, now)
        val escalated = level.order > previous.order
        var audioTriggered = false
        var vibrationTriggered = false
        var nextCooldownRemaining = cooldownRemaining
        if (level == WarningLevel.CAUTION || level == WarningLevel.DANGER) {
            if (escalated || cooldownRemaining <= 0L) {
                audioTriggered = audioPlayer.play(level)
                vibrationTriggered = vibrationPlayer.play(level)
                lastAudioTimeByLevel[level] = now
                nextCooldownRemaining = cooldownMillis(level)
                Log.i(TAG, "warning output requested track=$trackId risk=${threat.riskLevel} level=$level audioStarted=$audioTriggered vibrationStarted=$vibrationTriggered")
            } else {
                Log.d(TAG, "warning output suppressed by cooldown track=$trackId risk=${threat.riskLevel} level=$level remainingMs=$cooldownRemaining")
            }
        }

        val message = buildWarningMessage(threat)
        Log.i(
            TAG,
            "track=$trackId risk=${threat.riskLevel} warning=$level triggered=${level != WarningLevel.NONE} audio=$audioTriggered vibration=$vibrationTriggered message=$message"
        )

        val state = WarningState(
            level = level,
            trackId = trackId,
            className = threat.label,
            message = message,
            zone = threat.pathZone,
            distanceMeters = threat.distanceMeters,
            ttcSeconds = threat.ttcSeconds,
            audioTriggered = audioTriggered,
            vibrationTriggered = vibrationTriggered,
            cooldownRemainingMillis = max(0L, nextCooldownRemaining),
            previousLevel = previous,
            riskLevel = threat.riskLevel,
            reason = threat.riskReason
        )
        previousLevel = level
        return state
    }

    fun removeTracks(trackIds: Set<Int>) {
        trackIds.forEach { trackId ->
            lastSeenByTrack.remove(trackId)
            lastWarningByTrack.remove(trackId)
            confirmationCountByTrack.remove(trackId)
            Log.d(TAG, "Removed warning state for stale track=$trackId")
        }
    }

    fun silence() {
        previousLevel = WarningLevel.NONE
        audioPlayer.stop()
        vibrationPlayer.cancel()
    }

    fun release() {
        enabled = false
        audioPlayer.release()
        vibrationPlayer.cancel()
    }

    private fun warningConfirmed(detection: AnimalDetection): Boolean {
        val trackId = detection.trackId ?: return false
        val count = (confirmationCountByTrack[trackId] ?: 0) + 1
        confirmationCountByTrack[trackId] = count
        val weakEvidence = detection.confidence < WEAK_WARNING_CONFIDENCE ||
            detection.visibilityState == VisibilityState.BLURRY ||
            detection.visibilityState == VisibilityState.POOR_VISIBILITY ||
            detection.visibilityState == VisibilityState.NIGHT ||
            detection.visibilityQuality < 0.55f
        val required = if (weakEvidence) WEAK_EVIDENCE_CONFIRMATION_FRAMES else 1
        val confirmed = count >= required || detection.confidence >= STRONG_WARNING_CONFIDENCE
        Log.d(TAG, "warning confirmation track=$trackId count=$count required=$required confirmed=$confirmed confidence=${String.format("%.2f", detection.confidence)} visibility=${detection.visibilityState} quality=${String.format("%.2f", detection.visibilityQuality)}")
        return confirmed
    }

    private fun selectPrimaryThreat(detections: List<AnimalDetection>): AnimalDetection? {
        return detections
            .filter { warningLevelForRisk(it.riskLevel) != WarningLevel.NONE && it.trackId != null }
            .sortedWith(
                compareByDescending<AnimalDetection> { it.riskLevel.order }
                    .thenBy { it.ttcSeconds ?: Float.POSITIVE_INFINITY }
                    .thenBy { it.distanceMeters ?: Float.POSITIVE_INFINITY }
                    .thenBy { it.trackId ?: Int.MAX_VALUE }
            )
            .firstOrNull()
    }

    private fun warningLevelForRisk(riskLevel: RiskLevel): WarningLevel {
        return when (riskLevel) {
            RiskLevel.MEDIUM -> WarningLevel.CAUTION
            RiskLevel.HIGH -> WarningLevel.DANGER
            RiskLevel.LOW, RiskLevel.UNKNOWN -> WarningLevel.NONE
        }
    }

    private fun buildWarningMessage(detection: AnimalDetection): String {
        val name = detection.label.uppercase()
        if (detection.predictedPathCrossing && detection.riskLevel.order >= RiskLevel.MEDIUM.order) {
            return "$name MOVING TOWARD LANE"
        }
        return when (detection.riskLevel) {
            RiskLevel.HIGH -> if (detection.pathZone == PathZone.IN_PATH) "$name IN VEHICLE PATH" else "$name AHEAD"
            RiskLevel.MEDIUM -> if (detection.pathZone == PathZone.NEAR_PATH) "$name NEAR VEHICLE PATH" else "$name AHEAD"
            else -> ""
        }
    }

    private fun cooldownMillis(level: WarningLevel): Long {
        return when (level) {
            WarningLevel.DANGER -> HIGH_COOLDOWN_MILLIS
            WarningLevel.CAUTION -> MEDIUM_COOLDOWN_MILLIS
            WarningLevel.NONE -> 0L
        }
    }

    private fun cooldownRemaining(level: WarningLevel, now: Long): Long {
        val cooldown = cooldownMillis(level)
        val lastTime = lastAudioTimeByLevel[level] ?: 0L
        return cooldown - (now - lastTime)
    }

    private fun cleanupStale(now: Long) {
        val staleIds = lastSeenByTrack.filterValues { now - it > STALE_WARNING_MILLIS }.keys
        staleIds.forEach { trackId ->
            lastSeenByTrack.remove(trackId)
            lastWarningByTrack.remove(trackId)
            confirmationCountByTrack.remove(trackId)
            Log.d(TAG, "Removed warning state for stale track=$trackId")
        }
    }

    companion object {
        private const val TAG = "WarningManager"
        private const val MEDIUM_COOLDOWN_MILLIS = 3_000L
        private const val HIGH_COOLDOWN_MILLIS = 1_000L
        private const val STALE_WARNING_MILLIS = 15_000L
        private const val WEAK_WARNING_CONFIDENCE = 0.60f
        private const val STRONG_WARNING_CONFIDENCE = 0.75f
        private const val WEAK_EVIDENCE_CONFIRMATION_FRAMES = 3
    }
}

class AudioAlertPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var toneGenerator: ToneGenerator? = null
    private var hasAudioFocus: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i("AudioAlert", "audio focus interrupted change=$change")
                stop()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(lock) { hasAudioFocus = true }
                Log.i("AudioAlert", "audio focus gained")
            }
        }
    }

    fun play(level: WarningLevel): Boolean {
        if (level == WarningLevel.NONE) return false
        if (!requestFocus()) return false
        return try {
            thread(name = "AiracareAudioAlert", isDaemon = true) {
                try {
                    val tone = when (level) {
                        WarningLevel.DANGER -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                        WarningLevel.CAUTION -> ToneGenerator.TONE_PROP_BEEP
                        WarningLevel.NONE -> return@thread
                    }
                    val duration = if (level == WarningLevel.DANGER) 450 else 220
                    val generator = synchronized(lock) {
                        toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 85).also { toneGenerator = it }
                    }
                    val started = generator.startTone(tone, duration)
                    Log.i("AudioAlert", "audio started level=$level started=$started")
                    Thread.sleep(duration.toLong() + 40L)
                    Log.i("AudioAlert", "audio completed level=$level")
                    abandonFocus()
                } catch (exception: Exception) {
                    Log.e("AudioAlert", "Audio alert failed", exception)
                    abandonFocus()
                }
            }
            true
        } catch (exception: Exception) {
            Log.e("AudioAlert", "Could not start audio alert thread", exception)
            abandonFocus()
            false
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                toneGenerator?.stopTone()
            } catch (exception: Exception) {
                Log.w("AudioAlert", "Audio stop failed", exception)
            }
        }
        abandonFocus()
    }

    fun release() {
        synchronized(lock) {
            try {
                toneGenerator?.release()
            } catch (exception: Exception) {
                Log.w("AudioAlert", "Audio release failed", exception)
            }
            toneGenerator = null
        }
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        synchronized(lock) { hasAudioFocus = granted }
        if (!granted) Log.w("AudioAlert", "audio focus denied result=$result")
        return granted
    }

    private fun abandonFocus() {
        val shouldAbandon = synchronized(lock) {
            val hadFocus = hasAudioFocus
            hasAudioFocus = false
            hadFocus
        }
        if (!shouldAbandon) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (exception: Exception) {
            Log.w("AudioAlert", "Audio focus abandon failed", exception)
        }
    }
}

class VibrationAlertPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun play(level: WarningLevel): Boolean {
        val activeVibrator = vibrator ?: return false
        if (!activeVibrator.hasVibrator()) return false
        return try {
            val pattern = when (level) {
                WarningLevel.DANGER -> longArrayOf(0L, 180L, 80L, 180L)
                WarningLevel.CAUTION -> longArrayOf(0L, 120L)
                WarningLevel.NONE -> return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                activeVibrator.vibrate(pattern, -1)
            }
            Log.i("VibrationAlert", "vibration started level=$level")
            true
        } catch (exception: Exception) {
            Log.w("VibrationAlert", "vibration failed level=$level", exception)
            false
        }
    }

    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (exception: Exception) {
            Log.w("VibrationAlert", "vibration cancel failed", exception)
        }
    }
}

data class WarningState(
    val level: WarningLevel = WarningLevel.NONE,
    val trackId: Int? = null,
    val className: String? = null,
    val message: String = "",
    val zone: PathZone? = null,
    val distanceMeters: Float? = null,
    val ttcSeconds: Float? = null,
    val audioTriggered: Boolean = false,
    val vibrationTriggered: Boolean = false,
    val cooldownRemainingMillis: Long = 0L,
    val previousLevel: WarningLevel = WarningLevel.NONE,
    val riskLevel: RiskLevel = RiskLevel.UNKNOWN,
    val reason: String = ""
)

enum class WarningLevel(val order: Int) {
    NONE(0),
    CAUTION(1),
    DANGER(2)
}
