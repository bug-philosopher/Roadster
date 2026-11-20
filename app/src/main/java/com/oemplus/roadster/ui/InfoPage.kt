package com.oemplus.roadster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.core.SpeedUnit
import com.oemplus.roadster.location.Trip
import com.oemplus.roadster.location.WeatherHub
import com.oemplus.roadster.location.WeatherState
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

@Composable
fun InfoPage() {
    Row(Modifier.fillMaxSize()) {
        GpsPanel(Modifier.weight(1.06f).fillMaxHeight())
        Spacer(Modifier.width(12.u))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TripPanel(Modifier.fillMaxWidth().weight(1.22f))
            Spacer(Modifier.height(12.u))
            SunWeatherPanel(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun GpsPanel(modifier: Modifier) {
    val settings = LocalSettings.current
    val host = LocalHost.current
    val gps by Graph.gps.state.collectAsStateWithLifecycle()
    val fix = gps.fix?.takeIf { it.gps && it.ageMs() < 5000 }
    val anyFix = gps.fix
    GlassPanel(
        modifier,
        caption = "GPS",
        trailing = {
            Lamp(fix != null)
            Spacer(Modifier.width(10.u))
            Txt(if (fix != null) "Fix · ${gps.satsUsed} of ${gps.satsInView} satellites" else "No fix · ${gps.satsInView} in view", 17, color = Pal.Cream3)
        },
    ) {
        if (!gps.permission) {
            Spacer(Modifier.weight(1f))
            Txt("Location is off", 30, bold = true)
            Spacer(Modifier.height(10.u))
            Txt("Speed, heading, altitude and the trip computer all come from the unit's GPS.", 20, color = Pal.Cream2, maxLines = 2)
            Spacer(Modifier.height(18.u))
            TextKey("Allow location", host::requestLocation, lit = true, width = 240f)
            Spacer(Modifier.weight(1f))
            return@GlassPanel
        }
        CompassTape(gps.heading?.takeIf { fix != null }, Modifier.fillMaxWidth().height(70.u))
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Speed", 17, color = Pal.Cream3, bold = true)
                Spacer(Modifier.height(12.u))
                Row(verticalAlignment = Alignment.Bottom) {
                    SpeedReadout(fix?.speedMps, settings.speedUnit, 112, italic = true)
                    Spacer(Modifier.width(12.u))
                    Txt(Fmt.speedUnit(settings.speedUnit), 24, color = Pal.Cream2)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Readout("Heading", gps.heading?.takeIf { fix != null }?.let { "${Fmt.compass(it)} ${it.roundToInt()}°" } ?: "—", 34)
                Spacer(Modifier.height(22.u))
                Readout("Altitude", anyFix?.altM?.let { Fmt.altitude(it, settings.speedUnit) } ?: "—", 30)
            }
        }
        EngravedRule(Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.u))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(anyFix?.let { Fmt.latLon(it.lat, it.lon) } ?: "Waiting for first position", 18, color = Pal.Cream2)
            Spacer(Modifier.weight(1f))
            anyFix?.let {
                val acc = if (settings.speedUnit == SpeedUnit.MPH) "±${(it.accuracyM * 3.28084f).roundToInt()} ft" else "±${it.accuracyM.roundToInt()} m"
                Txt(acc, 18, color = Pal.Cream3)
            }
        }
    }
}

@Composable
private fun Readout(label: String, value: String, size: Int) {
    Column(horizontalAlignment = Alignment.End) {
        Txt(label, 17, color = Pal.Cream3, bold = true)
        Spacer(Modifier.height(6.u))
        Txt(value, size, bold = true)
    }
}

@Composable
private fun TripPanel(modifier: Modifier) {
    val trips by Graph.trips.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    GlassPanel(
        modifier,
        caption = "Trip computer",
        trailing = {
            Txt("GPS odometer ${Fmt.dist(trips.odoM, settings.speedUnit)} ${Fmt.distUnit(settings.speedUnit)}", 17, color = Pal.Cream3)
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            TripColumn("This drive", trips.drive, Modifier.weight(1f), note = "Restarts after 30 min parked")
            Spacer(Modifier.width(18.u))
            EngravedVRule(Modifier.fillMaxHeight())
            Spacer(Modifier.width(18.u))
            TripColumn("Trip A", trips.tripA, Modifier.weight(1f), reset = { Graph.trips.resetTripA() })
        }
    }
}

@Composable
private fun TripColumn(title: String, t: Trip, modifier: Modifier, note: String? = null, reset: (() -> Unit)? = null) {
    val s = LocalSettings.current
    val unit = Fmt.speedUnit(s.speedUnit)
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().height(40.u), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 21, bold = true)
            Spacer(Modifier.weight(1f))
            if (reset != null) {
                HardKey(onClick = {}, onLongClick = reset, modifier = Modifier.size(120.u, 40.u)) { ink ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("RESET", 15, color = ink, bold = true, tracking = 0.06f)
                        Spacer(Modifier.width(6.u))
                        Txt("長押し", 13, color = ink.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.u))
        Row(verticalAlignment = Alignment.Bottom) {
            SegText(Fmt.dist(t.distanceM, s.speedUnit).replace(",", ""), 40)
            Spacer(Modifier.width(10.u))
            Txt(Fmt.distUnit(s.speedUnit), 19, color = Pal.Cream2)
        }
        Spacer(Modifier.weight(1f))
        TripStat("Time", Fmt.hmm(t.elapsedMs))
        TripStat("Average", if (t.avgMps > 0f) "${Fmt.speed(t.avgMps, s.speedUnit)} $unit" else "—")
        TripStat("Top speed", if (t.maxMps > 0f) "${Fmt.speed(t.maxMps, s.speedUnit)} $unit" else "—")
        Spacer(Modifier.height(4.u))
        Txt(note ?: "Hold RESET to zero", 14, color = Pal.Cream3)
    }
}

@Composable
private fun TripStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(30.u), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 18, color = Pal.Cream2)
        Spacer(Modifier.weight(1f))
        Txt(value, 20, bold = true)
    }
}

@Composable
private fun SunWeatherPanel(modifier: Modifier) {
    val settings = LocalSettings.current
    val sun by Graph.sun.state.collectAsStateWithLifecycle()
    val weather by Graph.weather.state.collectAsStateWithLifecycle()
    val now by rememberWallClock(60_000)
    GlassPanel(modifier, caption = if (settings.weather) "Sun & weather" else "Sun") {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                SunLine(Glyphs.Sunrise, "Sunrise", sun.sunrise?.let { Fmt.time(it, settings.clock24) })
                SunLine(Glyphs.Sunset, "Sunset", sun.sunset?.let { Fmt.time(it, settings.clock24) })
                val set = sun.sunset
                val rise = sun.sunrise
                Txt(
                    when {
                        !sun.located -> "Estimated until GPS has a position"
                        set != null && rise != null && now in rise until set -> {
                            val m = (set - now) / 60_000
                            "${m / 60} h ${m % 60} min of daylight left"
                        }
                        else -> "After dark · display dimmed"
                    },
                    16, color = Pal.Cream3,
                )
            }
            Spacer(Modifier.width(16.u))
            EngravedVRule(Modifier.fillMaxHeight())
            Spacer(Modifier.width(16.u))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (settings.weather) WeatherBlock(weather)
                else SunArc(sun.sunrise, sun.sunset, now, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SunArc(rise: Long?, set: Long?, now: Long, modifier: Modifier) {
    val k = pxPerU()
    val il = LocalIllum.current
    Canvas(modifier) {
        val cx = size.width / 2f
        val base = size.height * 0.86f
        val r = minOf(size.width * 0.42f, size.height * 0.74f)
        val topLeft = Offset(cx - r, base - r)
        val box = Size(2 * r, 2 * r)
        drawLine(Pal.Cream3, Offset(cx - r - 16f * k, base), Offset(cx + r + 16f * k, base), strokeWidth = 1.6f * k)
        drawArc(
            Pal.Cream.copy(alpha = 0.22f), 180f, 180f, false, topLeft, box,
            style = Stroke(width = 1.8f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * k, 6f * k))),
        )
        if (rise != null && set != null && now in rise until set) {
            val f = (now - rise).toFloat() / (set - rise)
            drawArc(il.base.copy(alpha = 0.85f), 180f, 180f * f, false, topLeft, box, style = Stroke(width = 2.6f * k, cap = StrokeCap.Round))
            val a = Math.toRadians(180.0 + 180.0 * f)
            val p = Offset(cx + r * cos(a).toFloat(), base + r * sin(a).toFloat())
            drawCircle(Pal.Window.copy(alpha = 0.22f), 17f * k, p)
            drawCircle(Pal.Window, 8.5f * k, p)
        } else {
            drawCircle(Pal.Cream3.copy(alpha = 0.6f), 6f * k, Offset(cx, base + 10f * k))
        }
    }
}

@Composable
private fun SunLine(icon: ImageVector, label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 28, Pal.Cream2)
        Spacer(Modifier.width(14.u))
        Txt(label, 21)
        Spacer(Modifier.weight(1f))
        Txt(value ?: "—", 24, bold = true)
    }
}

@Composable
private fun ColumnScope.WeatherBlock(state: WeatherState) {
    val s = LocalSettings.current
    val w = (state as? WeatherState.Ok)?.now ?: (state as? WeatherState.Offline)?.last
    if (w == null) {
        Spacer(Modifier.weight(1f))
        Txt(
            when (state) {
                WeatherState.Off -> "Weather is off (SET › Units)"
                WeatherState.NoPosition -> "Waiting for a GPS position"
                is WeatherState.Offline -> "Offline: no data connection"
                else -> "Fetching weather…"
            },
            19, color = Pal.Cream2, maxLines = 2,
        )
        Spacer(Modifier.weight(1f))
        return
    }
    Spacer(Modifier.weight(0.5f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(Glyphs.weather(w.code, w.isDay), 44, Pal.Cream2)
        Spacer(Modifier.width(14.u))
        Txt("${Fmt.temp(w.tempC, s.tempUnit)}", 44, bold = true, color = if (w.stale) Pal.Cream2 else Pal.Cream)
        Spacer(Modifier.width(4.u))
        Txt(Fmt.tempUnit(s.tempUnit), 22, color = Pal.Cream2)
        Spacer(Modifier.width(14.u))
        Txt(WeatherHub.describe(w.code), 20, color = Pal.Cream)
    }
    Spacer(Modifier.height(10.u))
    if (w.hiC != null && w.loC != null) {
        Txt("High ${Fmt.temp(w.hiC, s.tempUnit)}°   Low ${Fmt.temp(w.loC, s.tempUnit)}°", 18, color = Pal.Cream2)
        Spacer(Modifier.height(4.u))
    }
    val wind = if (s.speedUnit == SpeedUnit.MPH) "${(w.windKmh / 1.609).roundToInt()} mph" else "${w.windKmh.roundToInt()} km/h"
    Txt("Wind $wind", 18, color = Pal.Cream2)
    Spacer(Modifier.weight(1f))
    Txt(
        "Open-Meteo · " + (if (state is WeatherState.Offline) "last " else "") + Fmt.time(w.fetchedAt, s.clock24),
        14, color = Pal.Cream3,
    )
}
