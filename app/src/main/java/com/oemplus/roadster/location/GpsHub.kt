package com.oemplus.roadster.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class Fix(
    val lat: Double,
    val lon: Double,
    val altM: Double?,
    val speedMps: Float,
    val bearing: Float?,
    val accuracyM: Float,
    val wallTime: Long,
    val elapsedNs: Long,
    val gps: Boolean,
) {
    fun ageMs(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1_000_000
}

@Immutable
data class GpsState(
    val permission: Boolean = false,
    val enabled: Boolean = true,
    val fix: Fix? = null,
    val heading: Float? = null,
    val satsInView: Int = 0,
    val satsUsed: Int = 0,
    val hz: Float = 0f,
)

class GpsHub(private val ctx: Context) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val users = mutableSetOf<String>()
    private val fastUsers = mutableSetOf<String>()
    private var running = false
    private var last: Fix? = null
    private val intervals = ArrayDeque<Long>()

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state.asStateFlow()
    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 32)
    val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun acquire(tag: String) {
        users += tag
        refresh()
    }

    fun release(tag: String) {
        users -= tag
        refresh()
    }

    fun setFast(tag: String, on: Boolean) {
        val before = fastUsers.isNotEmpty()
        if (on) fastUsers += tag else fastUsers -= tag
        if (before != fastUsers.isNotEmpty() && running) {
            stop()
            start()
        }
    }

    fun refresh() {
        val perm = hasPermission()
        if (users.isNotEmpty() && perm && !running) start()
        if ((users.isEmpty() || !perm) && running) stop()
        val enabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            false
        }
        _state.update { it.copy(permission = perm, enabled = enabled) }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location, seed = false)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { _state.update { it.copy(enabled = true) } }
        override fun onProviderDisabled(provider: String) { refresh() }
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _state.update { it.copy(satsInView = status.satelliteCount, satsUsed = used) }
        }

        override fun onStopped() {
            _state.update { it.copy(satsInView = 0, satsUsed = 0) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        try {
            val providers = lm.allProviders
            if (LocationManager.GPS_PROVIDER in providers) {
                val every = if (fastUsers.isNotEmpty()) 0L else 1000L
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 0f, listener, Looper.getMainLooper())
            }
            if (LocationManager.NETWORK_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 100f, listener, Looper.getMainLooper())
            }
            try { lm.registerGnssStatusCallback(gnss, main) } catch (_: Throwable) {}
            running = true
            val seed = (if (LocationManager.GPS_PROVIDER in providers) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                ?: (if (LocationManager.NETWORK_PROVIDER in providers) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null)
            seed?.let { onLocation(it, seed = true) }
        } catch (_: SecurityException) {
            running = false
        }
    }

    private fun stop() {
        try { lm.removeUpdates(listener) } catch (_: Throwable) {}
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Throwable) {}
        running = false
    }

    private fun onLocation(l: Location, seed: Boolean) {
        val isGps = l.provider == LocationManager.GPS_PROVIDER
        val prev = last
        // Drop a repeated fix. A moving car cannot be at the same point two times.
        if (isGps && !seed && prev != null && prev.gps && prev.speedMps > 0.5f &&
            l.latitude == prev.lat && l.longitude == prev.lon
        ) return
        if (!isGps && prev != null && prev.gps && (l.elapsedRealtimeNanos - prev.elapsedNs) < 15_000_000_000L) return

        var speed = if (l.hasSpeed()) l.speed else -1f
        // A bearing of 0 means no course.
        var bearing: Float? = if (l.hasBearing() && l.bearing != 0f) l.bearing else null
        if (prev != null && isGps && prev.gps) {
            val dt = (l.elapsedRealtimeNanos - prev.elapsedNs) / 1e9
            if (dt in 0.2..10.0) {
                val res = FloatArray(2)
                Location.distanceBetween(prev.lat, prev.lon, l.latitude, l.longitude, res)
                if (speed < 0f) speed = (res[0] / dt).toFloat()
                if (bearing == null && res[0] > 3f) bearing = (res[1] + 360f) % 360f
            }
        }
        if (speed < 0f || !isGps) speed = 0f
        val heldHeading = if (bearing != null && speed > 1.5f) bearing else _state.value.heading

        val alt: Double? = when {
            Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters
            l.hasAltitude() -> l.altitude
            else -> null
        }
        val fix = Fix(
            lat = l.latitude,
            lon = l.longitude,
            altM = alt,
            speedMps = speed,
            bearing = bearing,
            accuracyM = if (l.hasAccuracy()) l.accuracy else 15f,
            wallTime = l.time,
            elapsedNs = l.elapsedRealtimeNanos,
            gps = isGps,
        )
        var hz = _state.value.hz
        if (!seed && isGps && prev != null && prev.gps) {
            val dt = fix.elapsedNs - prev.elapsedNs
            if (dt in 1..5_000_000_000L) {
                intervals.addLast(dt)
                if (intervals.size > 8) intervals.removeFirst()
                hz = (1e9 / intervals.sorted()[intervals.size / 2]).toFloat()
            }
        }
        last = fix
        _state.update { it.copy(fix = fix, heading = heldHeading, hz = hz) }
        if (!seed && isGps) _fixes.tryEmit(fix)
    }
}
