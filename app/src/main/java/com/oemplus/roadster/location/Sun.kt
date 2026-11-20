package com.oemplus.roadster.location

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

@Immutable
data class SunState(
    val sunrise: Long? = null,
    val sunset: Long? = null,
    val night: Boolean = false,
    val located: Boolean = false,
)

class SunHub(private val gps: GpsHub, scope: CoroutineScope) {
    private val _state = MutableStateFlow(SunState())
    val state: StateFlow<SunState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                _state.value = compute(System.currentTimeMillis())
                delay(30_000)
            }
        }
        scope.launch {
            gps.state.map { it.fix != null }.distinctUntilChanged().collect {
                _state.value = compute(System.currentTimeMillis())
            }
        }
    }

    fun compute(now: Long): SunState {
        val fix = gps.state.value.fix
        val zone = ZoneId.systemDefault()
        val today = ZonedDateTime.now(zone).toLocalDate()
        if (fix == null) {
            val h = ZonedDateTime.now(zone).hour
            return SunState(night = h >= 19 || h < 6, located = false)
        }
        val (rise, set) = SunCalc.times(today, fix.lat, fix.lon, zone)
        val night = when {
            rise == null && set == null -> false
            rise == null -> now >= set!!
            set == null -> now < rise
            else -> now < rise || now >= set
        }
        return SunState(rise, set, night, true)
    }
}

object SunCalc {
    private const val ZENITH = 90.833

    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<Long?, Long?> {
        val n = date.dayOfYear
        fun at(ut: Double?): Long? {
            ut ?: return null
            var z = date.atStartOfDay(ZoneOffset.UTC).plusSeconds((ut * 3600).toLong()).withZoneSameInstant(zone)
            if (z.toLocalDate().isBefore(date)) z = z.plusDays(1)
            if (z.toLocalDate().isAfter(date)) z = z.minusDays(1)
            return z.toInstant().toEpochMilli()
        }
        return at(utHours(n, lat, lon, rising = true)) to at(utHours(n, lat, lon, rising = false))
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    private fun utHours(n: Int, lat: Double, lon: Double, rising: Boolean): Double? {
        val lngHour = lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return norm(localT - lngHour, 24.0)
    }
}
