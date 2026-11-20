package com.oemplus.roadster.location

import android.content.Context
import android.location.Location
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

@Immutable
data class Trip(
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val movingMs: Long = 0L,
    val maxMps: Float = 0f,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val avgMps: Float get() = if (elapsedMs > 30_000) (distanceM / (elapsedMs / 1000.0)).toFloat() else 0f
}

@Immutable
data class TripState(
    val drive: Trip = Trip(),
    val tripA: Trip = Trip(),
    val odoM: Double = 0.0,
    val topMps: Float = 0f,
    val topAt: Long = 0L,
)

class TripComputer(ctx: Context, gps: GpsHub, scope: CoroutineScope) {
    private val sp = ctx.getSharedPreferences("trips", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TripState> = _state.asStateFlow()
    private var last: Fix? = null
    private val gate = TopSpeedGate()
    private var lastSeen = sp.getLong("lastSeen", 0L)
    private var lastSave = 0L

    init {
        scope.launch { gps.fixes.collect { onFix(it) } }
    }

    private fun onFix(f: Fix) {
        val now = System.currentTimeMillis()
        var s = _state.value
        var newTop = false
        // New drive after 30 minutes with no fix.
        if (lastSeen != 0L && now - lastSeen > NEW_DRIVE_GAP_MS) {
            s = s.copy(drive = Trip(startedAt = now))
            last = null
            gate.reset()
        }
        lastSeen = now
        val p = last
        last = f
        val d = if (p == null) 0.0 else FloatArray(1).also { Location.distanceBetween(p.lat, p.lon, f.lat, f.lon, it) }[0].toDouble()
        val top = gate.push(TopSpeedGate.Reading(f.elapsedNs, f.speedMps, f.accuracyM), d) ?: 0f
        if (p != null) {
            val dtMs = (f.elapsedNs - p.elapsedNs) / 1_000_000
            if (dtMs in 1..60_000) {
                val moving = f.speedMps > 0.9f && f.accuracyM < 35f && d > 0.5 && d / (dtMs / 1000.0) < 90.0
                val add = if (moving) d else 0.0
                fun Trip.step() = copy(
                    distanceM = distanceM + add,
                    elapsedMs = elapsedMs + dtMs,
                    movingMs = movingMs + if (moving) dtMs else 0L,
                    maxMps = max(maxMps, top),
                )
                s = s.copy(drive = s.drive.step(), tripA = s.tripA.step(), odoM = s.odoM + add)
                if (top > s.topMps) {
                    s = s.copy(topMps = top, topAt = now)
                    newTop = true
                }
            }
        }
        _state.value = s
        if (newTop || now - lastSave > 15_000) save(s)
    }

    fun resetTripA() {
        _state.value = _state.value.copy(tripA = Trip())
        save(_state.value)
    }

    fun resetTopSpeed() {
        _state.value = _state.value.copy(topMps = 0f, topAt = 0L)
        save(_state.value)
    }

    private fun load(): TripState {
        fun trip(p: String) = Trip(
            distanceM = sp.getFloat("${p}_d", 0f).toDouble(),
            elapsedMs = sp.getLong("${p}_e", 0L),
            movingMs = sp.getLong("${p}_m", 0L),
            maxMps = sp.getFloat("${p}_x", 0f),
            startedAt = sp.getLong("${p}_s", System.currentTimeMillis()),
        )
        return TripState(
            trip("drive"), trip("a"), sp.getFloat("odo", 0f).toDouble(),
            topMps = sp.getFloat("top_v", 0f), topAt = sp.getLong("top_at", 0L),
        )
    }

    fun save(s: TripState = _state.value) {
        lastSave = System.currentTimeMillis()
        val e = sp.edit()
        fun put(p: String, t: Trip) {
            e.putFloat("${p}_d", t.distanceM.toFloat())
                .putLong("${p}_e", t.elapsedMs)
                .putLong("${p}_m", t.movingMs)
                .putFloat("${p}_x", t.maxMps)
                .putLong("${p}_s", t.startedAt)
        }
        put("drive", s.drive)
        put("a", s.tripA)
        e.putFloat("odo", s.odoM.toFloat()).putLong("lastSeen", lastSeen)
            .putFloat("top_v", s.topMps).putLong("top_at", s.topAt)
            .apply()
    }

    companion object {
        const val NEW_DRIVE_GAP_MS = 30 * 60_000L
    }
}
