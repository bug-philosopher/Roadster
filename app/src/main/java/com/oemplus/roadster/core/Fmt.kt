package com.oemplus.roadster.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {
    fun speed(mps: Float, u: SpeedUnit): Int =
        (mps * if (u == SpeedUnit.MPH) 2.236936f else 3.6f).roundToInt()

    fun speedUnit(u: SpeedUnit) = if (u == SpeedUnit.MPH) "mph" else "km/h"
    fun distUnit(u: SpeedUnit) = if (u == SpeedUnit.MPH) "mi" else "km"

    fun dist(m: Double, u: SpeedUnit): String {
        val v = if (u == SpeedUnit.MPH) m / 1609.344 else m / 1000.0
        return if (v < 100) String.format(Locale.US, "%.1f", v)
        else String.format(Locale.US, "%,d", v.roundToInt())
    }

    fun altitude(m: Double, u: SpeedUnit): String =
        if (u == SpeedUnit.MPH) String.format(Locale.US, "%,d ft", (m * 3.28084).roundToInt())
        else String.format(Locale.US, "%,d m", m.roundToInt())

    fun hmm(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    }

    fun track(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun temp(c: Double, u: TempUnit): Int = (if (u == TempUnit.F) c * 9 / 5 + 32 else c).roundToInt()
    fun tempUnit(u: TempUnit) = if (u == TempUnit.F) "°F" else "°C"

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compass(deg: Float): String {
        val d = ((deg % 360f) + 360f) % 360f
        return POINTS[((d + 22.5f) / 45f).toInt() % 8]
    }

    fun latLon(lat: Double, lon: Double): String = String.format(
        Locale.US, "%.4f° %s   %.4f° %s",
        abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
    )

    fun clock(nowMs: Long, h24: Boolean): Pair<String, String?> {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        if (h24) return String.format(Locale.US, "%02d:%02d", h, m) to null
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%2d:%02d", h12, m) to if (h < 12) "AM" else "PM"
    }

    fun time(ms: Long, h24: Boolean): String =
        SimpleDateFormat(if (h24) "H:mm" else "h:mm a", Locale.US).format(Date(ms))

    fun date(ms: Long): String = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(Date(ms))
    fun dateShort(ms: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(ms))
    fun dateJp(ms: Long): String = SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(Date(ms))
}
