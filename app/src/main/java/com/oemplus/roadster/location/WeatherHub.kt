package com.oemplus.roadster.location

import android.content.Context
import android.location.Location
import androidx.compose.runtime.Immutable
import com.oemplus.roadster.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Immutable
data class WeatherNow(
    val tempC: Double,
    val code: Int,
    val windKmh: Double,
    val hiC: Double?,
    val loC: Double?,
    val isDay: Boolean,
    val fetchedAt: Long,
) {
    val stale: Boolean get() = System.currentTimeMillis() - fetchedAt > 2 * 3600_000L
}

sealed interface WeatherState {
    data object Off : WeatherState
    data object NoPosition : WeatherState
    data object Loading : WeatherState
    data class Ok(val now: WeatherNow) : WeatherState
    data class Offline(val last: WeatherNow?) : WeatherState
}

class WeatherHub(
    ctx: Context,
    private val prefs: Prefs,
    private val gps: GpsHub,
    scope: CoroutineScope,
) {
    private val sp = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private var last: WeatherNow? = loadCached()
    private var lastLat = sp.getFloat("lat", Float.NaN).toDouble()
    private var lastLon = sp.getFloat("lon", Float.NaN).toDouble()
    private var lastAttempt = 0L

    private val _state = MutableStateFlow<WeatherState>(last?.let { WeatherState.Ok(it) } ?: WeatherState.Loading)
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private var busy = false

    init {
        scope.launch {
            while (true) {
                tick()
                delay(15_000)
            }
        }
        scope.launch {
            gps.state.map { it.fix != null }.distinctUntilChanged().filter { it }.collect { tick() }
        }
    }

    private suspend fun tick() {
        if (busy) return
        busy = true
        try {
            tickInner()
        } finally {
            busy = false
        }
    }

    private suspend fun tickInner() {
        if (!prefs.current.weather) {
            _state.value = WeatherState.Off
            return
        }
        val fix = gps.state.value.fix
        if (fix == null) {
            if (last == null) _state.value = WeatherState.NoPosition
            else if (_state.value is WeatherState.Off) _state.value = WeatherState.Ok(last!!)
            return
        }
        val now = System.currentTimeMillis()
        val moved = if (lastLat.isNaN()) Float.MAX_VALUE else FloatArray(1).also {
            Location.distanceBetween(lastLat, lastLon, fix.lat, fix.lon, it)
        }[0]
        val age = last?.let { now - it.fetchedAt } ?: Long.MAX_VALUE
        val due = age > 20 * 60_000L || moved > 25_000f
        val backoff = now - lastAttempt < if (_state.value is WeatherState.Offline) 5 * 60_000L else 60_000L
        if (!due) {
            if (_state.value !is WeatherState.Ok) last?.let { _state.value = WeatherState.Ok(it) }
            return
        }
        if (backoff) return
        lastAttempt = now
        if (last == null) _state.value = WeatherState.Loading
        val got = withContext(Dispatchers.IO) { fetch(fix.lat, fix.lon) }
        if (got != null) {
            last = got
            lastLat = fix.lat
            lastLon = fix.lon
            save(got)
            sp.edit().putFloat("lat", fix.lat.toFloat()).putFloat("lon", fix.lon.toFloat()).apply()
            _state.value = WeatherState.Ok(got)
        } else {
            _state.value = WeatherState.Offline(last)
        }
    }

    private fun fetch(lat: Double, lon: Double): WeatherNow? {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                lat, lon,
            ),
        )
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Roadster-HeadUnit/1.0")
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val cur = j.getJSONObject("current")
            val daily = j.optJSONObject("daily")
            WeatherNow(
                tempC = cur.getDouble("temperature_2m"),
                code = cur.optInt("weather_code", -1),
                windKmh = cur.optDouble("wind_speed_10m", 0.0),
                hiC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
                loC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() },
                isDay = cur.optInt("is_day", 1) == 1,
                fetchedAt = System.currentTimeMillis(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun save(w: WeatherNow) {
        sp.edit()
            .putFloat("t", w.tempC.toFloat()).putInt("c", w.code).putFloat("w", w.windKmh.toFloat())
            .putFloat("hi", (w.hiC ?: Double.NaN).toFloat()).putFloat("lo", (w.loC ?: Double.NaN).toFloat())
            .putBoolean("d", w.isDay).putLong("at", w.fetchedAt).apply()
    }

    private fun loadCached(): WeatherNow? {
        val at = sp.getLong("at", 0L)
        if (at == 0L) return null
        return WeatherNow(
            tempC = sp.getFloat("t", 0f).toDouble(),
            code = sp.getInt("c", -1),
            windKmh = sp.getFloat("w", 0f).toDouble(),
            hiC = sp.getFloat("hi", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
            loC = sp.getFloat("lo", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
            isDay = sp.getBoolean("d", true),
            fetchedAt = at,
        )
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Storm, hail"
            else -> "—"
        }
    }
}
