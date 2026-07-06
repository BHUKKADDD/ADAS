package com.example.adas.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * A single GNSS/GPS fix: position plus optional speed-over-ground.
 *
 * @param speedKmh speed derived from the fix (km/h), or null if the provider
 *                 didn't report a speed for this update.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Int?,
    val accuracyM: Float,
    val timeMs: Long
)

/**
 * Model-agnostic geolocation infrastructure. Wraps the framework [LocationManager]
 * (no Google Play Services dependency) and exposes a live [location] fix used to
 * tag anomaly packets and — because a GPS fix also carries speed-over-ground — as a
 * HUD speed source when no OBD adapter is connected. Never touches the detector or
 * the IDD model.
 */
@SuppressLint("MissingPermission") // guarded by hasLocationPermission(); the UI requests the grant
class GnssLocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _location = MutableStateFlow<GeoLocation?>(null)
    val location: StateFlow<GeoLocation?> = _location.asStateFlow()

    // Explicit object (not a SAM lambda): pre-API-30 LocationListener has four
    // abstract methods, so a lambda would AbstractMethodError on older devices.
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { _location.value = loc.toGeo() }
        @Deprecated("Required by the pre-API-30 LocationListener contract")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        // Seed immediately with the last known fix so the HUD isn't blank while
        // we wait for the first live update.
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
            ?.let { _location.value = it.toGeo() }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
                )
            }
        } catch (_: Exception) {
            // Provider unavailable / permission revoked mid-flight — stay on last fix.
        }
    }

    fun stop() {
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) {}
    }

    fun hasLocationPermission(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun Location.toGeo() = GeoLocation(
        latitude = latitude,
        longitude = longitude,
        speedKmh = if (hasSpeed()) (speed * 3.6f).roundToInt() else null,
        accuracyM = if (hasAccuracy()) accuracy else 0f,
        timeMs = System.currentTimeMillis()
    )
}
