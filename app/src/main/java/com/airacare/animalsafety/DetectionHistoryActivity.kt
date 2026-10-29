package com.airacare.animalsafety

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetectionHistoryActivity : ComponentActivity() {
    private lateinit var repository: DetectionHistoryRepository
    private lateinit var firestoreRepository: FirestoreDetectionRepository
    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var firebaseTestStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection_history)

        repository = DetectionHistoryRepository(this)
        firestoreRepository = FirestoreDetectionRepository()
        summaryText = findViewById(R.id.historySummaryText)
        emptyText = findViewById(R.id.historyEmptyText)
        listContainer = findViewById(R.id.historyListContainer)
        firebaseTestStatusText = findViewById(R.id.firebaseTestStatusText)

        findViewById<Button>(R.id.historyBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.historyRefreshButton).setOnClickListener { loadHistory() }
        findViewById<Button>(R.id.firebaseTestButton).setOnClickListener { testFirebaseWrite() }

        loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.close()
    }

    private fun loadHistory() {
        val items = repository.recentDetections()
        listContainer.removeAllViews()
        summaryText.text = "Saved detections: ${items.size}"
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            listContainer.addView(createRow(item))
        }
    }

    private fun testFirebaseWrite() {
        firebaseTestStatusText.text = "Saving Firebase test document..."
        firestoreRepository.saveTestDetection { ok, message ->
            runOnUiThread {
                firebaseTestStatusText.text = if (ok) {
                    "Firebase test saved. Check collection: detections"
                } else {
                    "Firebase test failed: $message"
                }
            }
        }
    }

    private fun createRow(item: DetectionHistoryItem): View {
        val context = this
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundResource(R.drawable.status_background)

            addView(TextView(context).apply {
                text = "${item.label.uppercase(Locale.US)}  ${(item.confidence * 100f).format(1)}%  ${item.riskLevel}"
                setTextColor(getColor(R.color.airacare_white))
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(detailText("Time", item.detectedAtEpochMillis.asDateTime()))
            addView(detailText("Distance", item.distanceMeters?.let { "${it.format(1)} m" } ?: "N/A"))
            addView(detailText("Speed", item.vehicleSpeedKmh?.let { "${it.format(1)} km/h" } ?: "N/A"))
            addView(detailText("GPS", gpsText(item)))
            addView(detailText("Movement", "${item.movementDirection} / ${item.pathZone}"))
            addView(detailText("Warning", item.warningLevel))
            addView(detailText("Source", item.cameraSource))
            addView(detailText("Sync", item.syncStatus))

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }
    }

    private fun detailText(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label: $value"
            setTextColor(getColor(R.color.airacare_soft_white))
            textSize = 13f
            setPadding(0, 4, 0, 0)
        }
    }

    private fun gpsText(item: DetectionHistoryItem): String {
        val latitude = item.latitude
        val longitude = item.longitude
        return if (latitude == null || longitude == null) {
            "N/A"
        } else {
            "${latitude.format(5)}, ${longitude.format(5)}"
        }
    }

    private fun Long.asDateTime(): String = DATE_FORMAT.format(Date(this))

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
