package com.airacare.animalsafety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class SafetyMapActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var listText: TextView
    private lateinit var mapView: WebView
    private lateinit var hazardClient: HazardSharingClient
    private lateinit var locationManager: LocationManager

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadNearbyHazards()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hazardClient = HazardSharingClient()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildLayout()
        if (hasLocationPermission()) {
            loadNearbyHazards()
        } else {
            statusText.text = "Location permission required for nearby hazards"
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hazardClient.close()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05080A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        statusText = TextView(this).apply {
            text = "Loading Safety Map"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 10)
        }
        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listText = TextView(this).apply {
            setTextColor(0xFFE0EEE8.toInt())
            textSize = 12f
            setPadding(16, 10, 16, 16)
            minLines = 4
        }
        container.addView(statusText)
        container.addView(mapView)
        container.addView(listText)
        setContentView(container)
    }

    private fun loadNearbyHazards() {
        val location = lastKnownLocation()
        if (location == null) {
            statusText.text = "GPS location unavailable"
            listText.text = "Move outdoors and open Safety Map again after GPS gets a lock."
            renderMap(DEFAULT_LATITUDE, DEFAULT_LONGITUDE, emptyList(), hasRealLocation = false)
            return
        }

        statusText.text = "Loading nearby hazards"
        hazardClient.fetchNearbyHazards(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = DEFAULT_RADIUS_METERS
        ) { ok, hazards, message ->
            runOnUiThread {
                if (!ok) {
                    statusText.text = "Safety Map unavailable"
                    listText.text = message
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    renderMap(location.latitude, location.longitude, emptyList(), hasRealLocation = true)
                } else {
                    statusText.text = "Safety Map: ${hazards.size} nearby reports"
                    listText.text = hazardSummary(hazards)
                    renderMap(location.latitude, location.longitude, hazards, hasRealLocation = true)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { location -> location.time }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hazardSummary(hazards: List<SharedHazard>): String {
        if (hazards.isEmpty()) return "No nearby Airacare hazard reports."
        return hazards.take(MAX_LIST_ITEMS).joinToString("\n") { hazard ->
            val distance = hazard.distanceFromQueryMeters?.let { "${it.toInt()} m" } ?: "distance N/A"
            val speed = hazard.speedKmh?.let { "${it.toInt()} km/h" } ?: "speed N/A"
            "${hazard.riskLevel} ${hazard.label} - $distance - $speed - confirmations ${hazard.confirmations}"
        }
    }

    private fun renderMap(latitude: Double, longitude: Double, hazards: List<SharedHazard>, hasRealLocation: Boolean) {
        val zoom = if (hasRealLocation) 15 else 7
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <style>
                html, body, #map { margin: 0; height: 100%; background: #05080a; }
                .leaflet-popup-content { font-family: sans-serif; }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                const center = [$latitude, $longitude];
                const hazards = ${hazardsJson(hazards)};
                const map = L.map('map').setView(center, $zoom);
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                  maxZoom: 19,
                  attribution: 'OpenStreetMap'
                }).addTo(map);
                L.circleMarker(center, { radius: 8, color: '#38bdf8', fillColor: '#38bdf8', fillOpacity: 0.9 }).addTo(map).bindPopup('${if (hasRealLocation) "Your location" else "Default map center"}');
                hazards.forEach(h => {
                  const color = h.risk_level === 'HIGH' ? '#dc2626' : h.risk_level === 'MEDIUM' ? '#f59e0b' : '#22c55e';
                  const marker = L.circleMarker([h.latitude, h.longitude], { radius: markerRadius(h), color, fillColor: color, fillOpacity: 0.85 }).addTo(map);
                  marker.bindPopup("<strong>" + escapeHtml(h.risk_level) + " " + escapeHtml(h.label) + "</strong><br/>Confidence: " + Math.round(h.confidence * 100) + "%<br/>Distance: " + Math.round(h.distance_from_query_meters || 0) + " m<br/>Confirmations: " + h.confirmations);
                });
                function markerRadius(h) { return Math.max(7, Math.min(14, 7 + (h.confirmations || 0))); }
                function escapeHtml(value) { return String(value).replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c])); }
              </script>
            </body>
            </html>
        """.trimIndent()
        mapView.loadDataWithBaseURL("https://airacare.local/", html, "text/html", "UTF-8", null)
    }

    private fun hazardsJson(hazards: List<SharedHazard>): String {
        val array = JSONArray()
        hazards.forEach { hazard ->
            array.put(JSONObject().apply {
                put("id", hazard.id)
                put("label", hazard.label)
                put("confidence", hazard.confidence.toDouble())
                put("risk_level", hazard.riskLevel)
                put("latitude", hazard.latitude)
                put("longitude", hazard.longitude)
                put("confirmations", hazard.confirmations)
                put("distance_from_query_meters", hazard.distanceFromQueryMeters ?: JSONObject.NULL)
            })
        }
        return array.toString()
    }

    companion object {
        private const val DEFAULT_RADIUS_METERS = 3000f
        private const val MAX_LIST_ITEMS = 8
        private const val DEFAULT_LATITUDE = 6.9271
        private const val DEFAULT_LONGITUDE = 79.8612
    }
}

