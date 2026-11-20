package com.oemplus.roadster.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.oemplus.roadster.media.Maneuver

object Glyphs {
    private class Part(val d: String, val fill: Boolean, val w: Float, val alpha: Float)

    private fun f(d: String, alpha: Float = 1f) = Part(d, true, 0f, alpha)
    private fun s(d: String, w: Float = 1.8f, alpha: Float = 1f) = Part(d, false, w, alpha)

    private fun glyph(name: String, vararg parts: Part): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        val white = SolidColor(Color.White)
        for (p in parts) {
            b.addPath(
                pathData = addPathNodes(p.d),
                fill = if (p.fill) white else null,
                fillAlpha = p.alpha,
                stroke = if (p.fill) null else white,
                strokeAlpha = p.alpha,
                strokeLineWidth = p.w,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Play = glyph("play", f("M8 5.3v13.4c0 .8.9 1.3 1.6.8l10-6.7c.6-.4.6-1.2 0-1.6l-10-6.7C8.9 4 8 4.5 8 5.3z"))
    val Pause = glyph("pause", f("M6.5 5h4v14h-4z"), f("M13.5 5h4v14h-4z"))
    val PlayPause = glyph(
        "playpause",
        f("M2.5 6.8v10.4c0 .6.7 1 1.2.6l7-5.2c.4-.3.4-.9 0-1.2l-7-5.2c-.5-.4-1.2 0-1.2.6z"),
        f("M13.5 6.5h3v11h-3z"), f("M18.5 6.5h3v11h-3z"),
    )
    val Prev = glyph("prev", f("M4.5 5.5h2.4v13H4.5z"), f("M8.2 12l6.3-6.3v12.6z"), f("M13.9 12l6.3-6.3v12.6z"))
    val Next = glyph("next", f("M19.5 5.5h-2.4v13h2.4z"), f("M15.8 12L9.5 5.7v12.6z"), f("M10.1 12L3.8 5.7v12.6z"))
    val Shuffle = glyph(
        "shuffle",
        s("M3.5 7.5h3.2c1.9 0 3 .9 4 2.4l2.6 4.2c1 1.5 2.1 2.4 4 2.4h3.2", 1.9f),
        s("M3.5 16.5h3.2c1.2 0 2.1-.4 2.8-1", 1.9f),
        s("M14.5 8.9c.7-.9 1.6-1.4 3-1.4h3", 1.9f),
        s("M18 5l2.5 2.5L18 10", 1.9f),
        s("M18 14l2.5 2.5L18 19", 1.9f),
    )
    val Repeat = glyph(
        "repeat",
        s("M4.5 12v-1.5a3.5 3.5 0 0 1 3.5-3.5h11", 1.9f),
        s("M16.5 4.5L19 7l-2.5 2.5", 1.9f),
        s("M19.5 12v1.5a3.5 3.5 0 0 1-3.5 3.5H5", 1.9f),
        s("M7.5 14.5L5 17l2.5 2.5", 1.9f),
    )

    val Person = glyph("person", f("M12 11.5a4 4 0 1 0 0-8a4 4 0 1 0 0 8z"), f("M4.5 20.5c.4-3.9 3.6-6.5 7.5-6.5s7.1 2.6 7.5 6.5z"))
    val Disc = glyph(
        "disc",
        s("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", 1.7f),
        f("M12 9.6a2.4 2.4 0 1 0 0 4.8a2.4 2.4 0 1 0 0-4.8z"),
        s("M6.9 9.4a5.6 5.6 0 0 1 2.5-2.5", 1.4f, 0.7f),
    )
    val Note = glyph(
        "note",
        f("M9.5 17.2a2.8 2.3 0 1 1-5.6 0a2.8 2.3 0 1 1 5.6 0z"),
        f("M19.5 15.2a2.8 2.3 0 1 1-5.6 0a2.8 2.3 0 1 1 5.6 0z"),
        s("M9.5 17.2V6.8l10-2.4v10.8", 1.8f),
        f("M9.5 6.8l10-2.4v2.9l-10 2.4z"),
    )

    val Calendar = glyph(
        "calendar",
        s("M5 6.5h14v13H5z", 1.7f), s("M5 10.5h14", 1.7f), s("M9 4v4.5M15 4v4.5", 1.7f),
        f("M8 13h2v2H8zM11 13h2v2h-2zM14 13h2v2h-2zM8 16h2v2H8zM11 16h2v2h-2z", 0.8f),
    )
    val Thermo = glyph(
        "thermo",
        s("M12 3.5a2 2 0 0 0-2 2v8.6a3.6 3.6 0 1 0 4 0V5.5a2 2 0 0 0-2-2z", 1.7f),
        f("M12 18.9a1.8 1.8 0 1 0 0-3.6a1.8 1.8 0 1 0 0 3.6z"),
        s("M12 15.6V9", 2.2f),
    )
    val Route = glyph(
        "route",
        s("M6 20.5a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", 1.7f),
        s("M18 7.5a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", 1.7f),
        s("M8 18.5h7.5a3 3 0 0 0 0-6h-7a3 3 0 0 1 0-6H16", 1.7f),
    )
    val Sunset = glyph(
        "sunset",
        s("M3 18.5h18", 1.7f), s("M7 18.5a5 5 0 0 1 10 0", 1.7f),
        s("M12 3v6", 1.7f), s("M9.6 6.8L12 9.2l2.4-2.4", 1.7f),
        s("M3.8 13.2l1.6.9M20.2 13.2l-1.6.9", 1.7f),
    )
    val Sunrise = glyph(
        "sunrise",
        s("M3 18.5h18", 1.7f), s("M7 18.5a5 5 0 0 1 10 0", 1.7f),
        s("M12 9.2V3", 1.7f), s("M9.6 5.4L12 3l2.4 2.4", 1.7f),
        s("M3.8 13.2l1.6.9M20.2 13.2l-1.6.9", 1.7f),
    )
    val Gps = glyph(
        "gps",
        s("M12 5.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", 1.7f),
        s("M12 2v3.5M12 18.5V22M2 12h3.5M18.5 12H22", 1.7f),
        f("M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    )
    val Bluetooth = glyph("bt", s("M7 7.5l10 9-5 4.5V3l5 4.5-10 9", 1.8f))
    val Stopwatch = glyph(
        "stopwatch",
        s("M12 7.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", 1.7f),
        s("M10 3.5h4M12 3.5v4", 1.7f),
        s("M18.1 6.7l1.5-1.5", 1.7f),
        s("M12 14l2.7-2.7", 2.0f),
    )

    val Sun = glyph(
        "sun",
        f("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z"),
        s("M12 2.5V5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8", 1.7f),
    )
    val Moon = glyph("moon", f("M19.5 14.6A8 8 0 0 1 9.4 4.5a8 8 0 1 0 10.1 10.1z"))
    private const val CLOUD = "M7 17.5h10a3.8 3.8 0 0 0 .5-7.6A5.3 5.3 0 0 0 7.3 10a3.75 3.75 0 0 0-.3 7.5z"
    private const val CLOUD_HIGH = "M7 14.5h10a3.8 3.8 0 0 0 .5-7.6A5.3 5.3 0 0 0 7.3 7a3.75 3.75 0 0 0-.3 7.5z"
    val Cloud = glyph("cloud", s(CLOUD, 1.7f))
    val PartCloud = glyph(
        "partcloud",
        s("M7.5 3.5v1.6M3.4 5.4l1.1 1.1M2 9.5h1.6", 1.6f),
        s("M5.3 11.6a3.2 3.2 0 0 1 4.9-4", 1.6f),
        s(CLOUD, 1.7f),
    )
    val Rain = glyph("rain", s(CLOUD_HIGH, 1.7f), s("M8.5 17.5l-1 3M12.5 17.5l-1 3M16.5 17.5l-1 3", 1.7f))
    val Snow = glyph("snow", s(CLOUD_HIGH, 1.7f), f("M8 18.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM12 19.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM16 18.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2z"))
    val Fog = glyph("fog", s("M4 8.5h16M6 12.5h12M4 16.5h16", 1.8f))
    val Storm = glyph("storm", s(CLOUD_HIGH, 1.7f), f("M12.6 15l-2.7 4.2h2.6l-1.3 3.3 4.1-5h-2.7l1.3-2.5z"))

    fun weather(code: Int, day: Boolean) = when (code) {
        0, 1 -> if (day) Sun else Moon
        2 -> PartCloud
        3 -> Cloud
        45, 48 -> Fog
        in 51..67, in 80..82 -> Rain
        in 71..77, 85, 86 -> Snow
        in 95..99 -> Storm
        else -> Cloud
    }

    private const val SHAFT = 2.8f
    val Straight = glyph("m_straight", s("M12 21V9.5", SHAFT), f("M7.3 10.2L12 3.5l4.7 6.7z"))
    val Right = glyph("m_right", s("M7 21v-7.5A4 4 0 0 1 11 9.5h4", SHAFT), f("M14.3 4.8l6.2 4.7-6.2 4.7z"))
    val Left = glyph("m_left", s("M17 21v-7.5a4 4 0 0 0-4-4H9", SHAFT), f("M9.7 4.8L3.5 9.5l6.2 4.7z"))
    val SlightRight = glyph("m_sright", s("M9 21v-6.5c0-1.6.6-3 1.8-4.2l3.4-3.4", SHAFT), f("M19 2.2l-.85 5.95-5.1-5.1z"))
    val SlightLeft = glyph("m_sleft", s("M15 21v-6.5c0-1.6-.6-3-1.8-4.2L9.8 6.9", SHAFT), f("M5 2.2l.85 5.95 5.1-5.1z"))
    val SharpRight = glyph("m_shright", s("M8 21V8.5a3 3 0 0 1 5.1-2.1l2.6 2.6", SHAFT), f("M20 13.3l-5.95-.85 5.1-5.1z"))
    val SharpLeft = glyph("m_shleft", s("M16 21V8.5a3 3 0 0 0-5.1-2.1L8.3 9", SHAFT), f("M4 13.3l5.95-.85-5.1-5.1z"))
    val UTurn = glyph("m_uturn", s("M16 21V10a4 4 0 0 0-8 0v3.5", SHAFT), f("M3.8 12.8h8.4L8 19.3z"))
    val KeepRight = glyph(
        "m_kright",
        s("M12 21v-5.5", SHAFT), s("M12 15.5l4.5-4.5V9", SHAFT), s("M12 15.5L7.5 11V5", SHAFT, 0.32f),
        f("M12.3 9.6l4.2-6.1 4.2 6.1z"),
    )
    val KeepLeft = glyph(
        "m_kleft",
        s("M12 21v-5.5", SHAFT), s("M12 15.5L7.5 11V9", SHAFT), s("M12 15.5l4.5-4.5V5", SHAFT, 0.32f),
        f("M3.3 9.6l4.2-6.1 4.2 6.1z"),
    )
    val Merge = glyph(
        "m_merge",
        s("M8 21v-4l4-4V9", SHAFT), s("M16 21v-4l-4-4", SHAFT, 0.32f),
        f("M7.5 9.6L12 3.2l4.5 6.4z"),
    )
    val ExitRight = glyph(
        "m_exitr",
        s("M8 21V3", 2.4f, 0.32f), s("M8 17l6.6-6.6", SHAFT), f("M19.2 5.8l-.85 5.95-5.1-5.1z"),
    )
    val ExitLeft = glyph(
        "m_exitl",
        s("M16 21V3", 2.4f, 0.32f), s("M16 17L9.4 10.4", SHAFT), f("M4.8 5.8l.85 5.95 5.1-5.1z"),
    )
    val Roundabout = glyph(
        "m_round",
        s("M12 7.8a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 1 0 0-6.8z", 2.2f),
        s("M12 21v-6.4", SHAFT), s("M14.4 9.4l2.3-2.3", SHAFT), f("M20.6 2.4l-.85 5.95-5.1-5.1z"),
    )
    val Arrive = glyph("m_arrive", s("M7 21.5V3.5", 2.4f), f("M7 3.5h11l-2.8 4 2.8 4H7z"))

    fun maneuver(m: Maneuver) = when (m) {
        Maneuver.STRAIGHT -> Straight
        Maneuver.SLIGHT_LEFT -> SlightLeft
        Maneuver.LEFT -> Left
        Maneuver.SHARP_LEFT -> SharpLeft
        Maneuver.SLIGHT_RIGHT -> SlightRight
        Maneuver.RIGHT -> Right
        Maneuver.SHARP_RIGHT -> SharpRight
        Maneuver.UTURN -> UTurn
        Maneuver.KEEP_LEFT -> KeepLeft
        Maneuver.KEEP_RIGHT -> KeepRight
        Maneuver.MERGE -> Merge
        Maneuver.EXIT_LEFT -> ExitLeft
        Maneuver.EXIT_RIGHT -> ExitRight
        Maneuver.ROUNDABOUT -> Roundabout
        Maneuver.ARRIVE -> Arrive
        Maneuver.UNKNOWN -> Straight
    }
}
