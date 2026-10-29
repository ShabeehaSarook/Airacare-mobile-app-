package com.airacare.animalsafety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class GpsSpeedProvider(
    context: Context,
    private val onUpdate: (VehicleSpeedSnapshot) -> Unit,
    private val onStatus: (String) -> Unit
) : LocationListener {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var latestSnapshot: VehicleSpeedSnapshot = VehicleSpeedSnapshot.unavailable()
    private var lastAcceptedLocation: Location? = null
    private var started = false

    fun current(): VehicleSpeedSnapshot = latestSnapshot

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            latestSnapshot = VehicleSpeedSnapshot.unavailable("permission_missing")
            onStatus("GPS unavailable: permission missing")
            Log.w(TAG, "GPS start skipped: location permission missing")
            return
        }

        if (started) {
            Log.d(TAG, "GPS provider already started latestAvailable=${latestSnapshot.available} reason=${latestSnapshot.reason} source=${latestSnapshot.source}")
            onStatus("GPS already active")
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                try {
                    locationManager.isProviderEnabled(provider)
                } catch (_: Exception) {
                    false
                }
            }

        if (providers.isEmpty()) {
            latestSnapshot = VehicleSpeedSnapshot.unavailable("provider_disabled")
            onStatus("GPS unavailable: provider disabled")
            Log.w(TAG, "GPS start skipped: no enabled GPS/network providers")
            return
        }

        started = true
        providers.forEach { provider ->
            try {
                locationManager.getLastKnownLocation(provider)?.let { location ->
                    if (!isStale(location, MAX_LAST_KNOWN_AGE_MILLIS)) {
                        updateFromLocation(location, "${provider}_last_known")
                    } else {
                        Log.i(TAG, "Ignored stale last-known location source=$provider ageMs=${locationAgeMillis(location)}")
                    }
                } ?: run {
                    Log.i(TAG, "No last-known location available source=$provider")
                }
                locationManager.requestLocationUpdates(provider, MIN_UPDATE_MS, MIN_UPDATE_METERS, this, Looper.getMainLooper())
                Log.i(TAG, "GPS provider started source=$provider")
            } catch (exception: Exception) {
                Log.w(TAG, "Could not start GPS provider source=$provider", exception)
            }
        }
        onStatus("GPS active")
    }

    fun stop() {
        try {
            locationManager.removeUpdates(this)
            started = false
        } catch (exception: Exception) {
            Log.w(TAG, "Could not stop GPS provider", exception)
        }
    }

    override fun onLocationChanged(location: Location) {
        updateFromLocation(location, location.provider ?: "location")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) {
        onStatus("GPS provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        latestSnapshot = VehicleSpeedSnapshot.unavailable("provider_disabled")
        onUpdate(latestSnapshot)
        onStatus("GPS provider disabled: $provider")
    }

    private fun updateFromLocation(location: Location, source: String) {
        if (!isUsableLocation(location, source)) {
            Log.d(TAG, "GPS update rejected source=$source reason=${rejectReason(location, source)} accuracy=${accuracyText(location)} ageMs=${locationAgeMillis(location)}")
            return
        }
        val previousLocation = lastAcceptedLocation
        val speedFromLocation = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else null
        val speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else {
            null
        }
        val derivedSpeed = deriveSpeedMetersPerSecond(previousLocation, location)
        val speed = when {
            speedFromLocation != null && (speedAccuracy == null || speedAccuracy <= MAX_SPEED_ACCURACY_MPS) -> speedFromLocation
            derivedSpeed != null -> derivedSpeed
            else -> speedFromLocation
        }
        val bearing = if (location.hasBearing()) location.bearing else previousLocation?.bearingTo(location)
        val bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
            location.bearingAccuracyDegrees
        } else {
            null
        }
        latestSnapshot = VehicleSpeedSnapshot(
            speedMetersPerSecond = speed,
            speedKmh = speed?.let { it * KMH_PER_MPS },
            latitude = location.latitude,
            longitude = location.longitude,
            bearingDegrees = bearing,
            horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            bearingAccuracyDegrees = bearingAccuracy,
            source = source,
            accuracyMetersPerSecond = speedAccuracy,
            timestampMillis = location.time,
            available = true,
            reason = when {
                speed == null -> "speed_missing"
                speedFromLocation == null && derivedSpeed != null -> "derived_speed"
                speedAccuracy != null && speedAccuracy > MAX_SPEED_ACCURACY_MPS && derivedSpeed != null -> "derived_speed_due_low_accuracy"
                else -> "valid"
            }
        )
        lastAcceptedLocation = Location(location)
        Log.d(TAG, "GPS update speed=${latestSnapshot.speedMetersPerSecond ?: "N/A"}m/s kmh=${latestSnapshot.speedKmh ?: "N/A"} lat=${latestSnapshot.latitude ?: "N/A"} lon=${latestSnapshot.longitude ?: "N/A"} bearing=${latestSnapshot.bearingDegrees ?: "N/A"} source=${latestSnapshot.source} speedAccuracy=${latestSnapshot.accuracyMetersPerSecond ?: "N/A"} horizontalAccuracy=${latestSnapshot.horizontalAccuracyMeters ?: "N/A"} reason=${latestSnapshot.reason}")
        onUpdate(latestSnapshot)
    }

    private fun isUsableLocation(location: Location, source: String): Boolean {
        if (isStale(location, MAX_LOCATION_AGE_MILLIS)) return false
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (accuracy != null && accuracy > MAX_HORIZONTAL_ACCURACY_METERS) return false
        val latest = latestSnapshot
        val latestIsFresh = latest.timestampMillis > 0L && System.currentTimeMillis() - latest.timestampMillis <= MAX_LOCATION_AGE_MILLIS
        val latestAccuracy = latest.horizontalAccuracyMeters
        val incomingIsNetwork = source.contains(LocationManager.NETWORK_PROVIDER, ignoreCase = true)
        val latestIsGps = latest.source.contains(LocationManager.GPS_PROVIDER, ignoreCase = true)
        if (incomingIsNetwork && latestIsGps && latestIsFresh) {
            if (latestAccuracy == null || accuracy == null || accuracy > latestAccuracy + NETWORK_ACCURACY_IMPROVEMENT_MARGIN_METERS) {
                return false
            }
        }
        return true
    }

    private fun rejectReason(location: Location, source: String): String {
        if (isStale(location, MAX_LOCATION_AGE_MILLIS)) return "stale_${locationAgeMillis(location)}ms"
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (accuracy != null && accuracy > MAX_HORIZONTAL_ACCURACY_METERS) return "low_accuracy_${accuracy}m"
        val latest = latestSnapshot
        val latestIsFresh = latest.timestampMillis > 0L && System.currentTimeMillis() - latest.timestampMillis <= MAX_LOCATION_AGE_MILLIS
        val incomingIsNetwork = source.contains(LocationManager.NETWORK_PROVIDER, ignoreCase = true)
        val latestIsGps = latest.source.contains(LocationManager.GPS_PROVIDER, ignoreCase = true)
        if (incomingIsNetwork && latestIsGps && latestIsFresh) return "network_worse_than_fresh_gps"
        return "unknown"
    }

    private fun isStale(location: Location, maxAgeMillis: Long): Boolean {
        val age = locationAgeMillis(location)
        return age != null && age > maxAgeMillis
    }

    private fun locationAgeMillis(location: Location): Long? {
        if (location.time <= 0L) return null
        return System.currentTimeMillis() - location.time
    }

    private fun deriveSpeedMetersPerSecond(previous: Location?, current: Location): Float? {
        if (previous == null) return null
        val previousTime = previous.time
        val currentTime = current.time
        if (previousTime <= 0L || currentTime <= previousTime) return null
        val dtSeconds = (currentTime - previousTime) / 1000f
        if (dtSeconds < MIN_DERIVED_SPEED_DT_SECONDS || dtSeconds > MAX_DERIVED_SPEED_DT_SECONDS) return null
        val distanceMeters = previous.distanceTo(current)
        val speed = distanceMeters / dtSeconds
        if (speed > MAX_REASONABLE_VEHICLE_SPEED_MPS) return null
        if (distanceMeters < MIN_DERIVED_SPEED_DISTANCE_METERS && speed < MIN_DERIVED_SPEED_MPS) return 0f
        return speed.coerceAtLeast(0f)
    }

    private fun accuracyText(location: Location): String {
        return if (location.hasAccuracy()) location.accuracy.toString() else "N/A"
    }

    companion object {
        private const val TAG = "GpsSpeedProvider"
        private const val MIN_UPDATE_MS = 1000L
        private const val MIN_UPDATE_METERS = 0f
        private const val KMH_PER_MPS = 3.6f
        private const val MAX_LAST_KNOWN_AGE_MILLIS = 10_000L
        private const val MAX_LOCATION_AGE_MILLIS = 5_000L
        private const val MAX_HORIZONTAL_ACCURACY_METERS = 50f
        private const val NETWORK_ACCURACY_IMPROVEMENT_MARGIN_METERS = 10f
        private const val MAX_SPEED_ACCURACY_MPS = 3.0f
        private const val MIN_DERIVED_SPEED_DT_SECONDS = 0.8f
        private const val MAX_DERIVED_SPEED_DT_SECONDS = 5.0f
        private const val MIN_DERIVED_SPEED_DISTANCE_METERS = 2.0f
        private const val MIN_DERIVED_SPEED_MPS = 0.5f
        private const val MAX_REASONABLE_VEHICLE_SPEED_MPS = 45.0f
    }
}

data class VehicleSpeedSnapshot(
    val speedMetersPerSecond: Float?,
    val speedKmh: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val bearingDegrees: Float?,
    val horizontalAccuracyMeters: Float?,
    val bearingAccuracyDegrees: Float?,
    val source: String,
    val accuracyMetersPerSecond: Float?,
    val timestampMillis: Long,
    val available: Boolean,
    val reason: String
) {
    companion object {
        fun unavailable(reason: String = "unavailable", source: String = "none"): VehicleSpeedSnapshot {
            return VehicleSpeedSnapshot(
                speedMetersPerSecond = null,
                speedKmh = null,
                latitude = null,
                longitude = null,
                bearingDegrees = null,
                horizontalAccuracyMeters = null,
                bearingAccuracyDegrees = null,
                source = source,
                accuracyMetersPerSecond = null,
                timestampMillis = 0L,
                available = false,
                reason = reason
            )
        }
    }
}
