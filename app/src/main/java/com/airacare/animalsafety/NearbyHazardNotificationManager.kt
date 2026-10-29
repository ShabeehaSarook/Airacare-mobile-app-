package com.airacare.animalsafety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class NearbyHazardNotificationManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val notifiedHazards = mutableMapOf<String, Long>()

    init {
        createChannel()
    }

    fun notifyNearbyHazards(hazards: List<SharedHazard>) {
        if (!hasNotificationPermission()) return
        val now = android.os.SystemClock.elapsedRealtime()
        hazards
            .filter { hazard -> hazard.riskLevel == "HIGH" || hazard.riskLevel == "MEDIUM" }
            .sortedWith(compareByDescending<SharedHazard> { it.riskLevel == "HIGH" }.thenBy { it.distanceFromQueryMeters ?: Float.MAX_VALUE })
            .take(MAX_NOTIFICATIONS_PER_SCAN)
            .forEach { hazard ->
                val lastNotifiedAt = notifiedHazards[hazard.id] ?: 0L
                if (now - lastNotifiedAt < NOTIFICATION_COOLDOWN_MILLIS) return@forEach
                notifiedHazards[hazard.id] = now
                showNotification(hazard)
            }
    }

    private fun showNotification(hazard: SharedHazard) {
        val distance = hazard.distanceFromQueryMeters?.let { "${it.toInt()} m away" } ?: "nearby"
        val title = when (hazard.riskLevel) {
            "HIGH" -> "High animal hazard ahead"
            "MEDIUM" -> "Animal hazard nearby"
            else -> "Airacare hazard nearby"
        }
        val text = "${hazard.label.uppercase()} reported $distance"
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. Slow down and watch the road."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(appContext).notify(hazard.id.hashCode(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nearby hazard alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for nearby Airacare animal/person hazard reports"
        }
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "airacare_nearby_hazards"
        private const val NOTIFICATION_COOLDOWN_MILLIS = 5 * 60 * 1000L
        private const val MAX_NOTIFICATIONS_PER_SCAN = 2
    }
}
