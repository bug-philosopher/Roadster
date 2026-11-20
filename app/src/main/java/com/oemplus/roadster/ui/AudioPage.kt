package com.oemplus.roadster.ui

import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.core.SpeedUnit
import com.oemplus.roadster.location.GpsState
import com.oemplus.roadster.perf.PerfEngine
import com.oemplus.roadster.perf.PerfMode
import com.oemplus.roadster.perf.PerfTimer
import com.oemplus.roadster.location.WeatherState
import com.oemplus.roadster.location.WeatherHub
import com.oemplus.roadster.media.Maneuver
import com.oemplus.roadster.media.NavInfo
import com.oemplus.roadster.media.SessionView
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun AudioPage(openSources: () -> Unit, openDisp: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val designW = maxWidth.value / LocalU.current
        val rightW = (designW * 0.355f).coerceIn(420f, 620f)
        Row(Modifier.fillMaxSize()) {
            AudioDisplay(Modifier.weight(1f).fillMaxHeight(), openSources, openDisp)
            Spacer(Modifier.width(12.u))
            Column(Modifier.width(rightW.u).fillMaxHeight()) {
                NaviPanel(Modifier.fillMaxWidth().weight(0.44f))
                Spacer(Modifier.height(12.u))
                InfoGlance(Modifier.fillMaxWidth().weight(0.56f))
            }
        }
    }
}

@Composable
private fun AudioDisplay(modifier: Modifier, openSources: () -> Unit, openDisp: () -> Unit) {
    val access by Graph.media.access.collectAsStateWithLifecycle()
    val cur by Graph.media.current.collectAsStateWithLifecycle()
    val sessions by Graph.media.sessions.collectAsStateWithLifecycle()
    val v = cur
    val caption = when (v?.state) {
        null -> "Audio"
        android.media.session.PlaybackState.STATE_PAUSED -> "Paused"
        android.media.session.PlaybackState.STATE_BUFFERING,
        android.media.session.PlaybackState.STATE_CONNECTING -> "Buffering"
        android.media.session.PlaybackState.STATE_STOPPED,
        android.media.session.PlaybackState.STATE_NONE -> "Stopped"
        android.media.session.PlaybackState.STATE_ERROR -> "Playback error"
        else -> "Now playing"
    }
    GlassPanel(
        modifier,
        caption = caption,
        trailing = {
            HardKey(onClick = openSources, modifier = Modifier.size(112.u, 44.u)) { ink ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("SRC", 19, color = ink, bold = true, tracking = 0.08f)
                    if (sessions.size > 1) {
                        Spacer(Modifier.width(8.u))
                        Txt("${sessions.size}", 16, color = ink.copy(alpha = 0.7f))
                    }
                }
            }
        },
    ) {
        when {
            v != null -> NowPlaying(v, openDisp)
            !access -> MediaAccessPrompt()
            else -> NothingPlaying(openSources)
        }
    }
}

@Composable
private fun NowPlaying(v: SessionView, openDisp: () -> Unit) {
    val host = LocalHost.current
    val openApp = {
        val pi = Graph.media.sessionActivity(v.key)
        if ((pi == null || !host.sendPendingIntent(pi)) && v.pkg.isNotEmpty()) host.launchApp(v.pkg)
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.35f))
        Row(Modifier.fillMaxWidth().height(250.u)) {
            AlbumArt(
                v.art,
                Modifier.size(250.u).clickable(remember { MutableInteractionSource() }, null) { openApp() },
                playing = v.playing,
                progress = { if (v.durationMs > 0) v.positionNow().toFloat() / v.durationMs else 0.12f },
                title = v.title,
                artist = v.artist,
            )
            Spacer(Modifier.width(28.u))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Title(v.title ?: "Untitled")
                Spacer(Modifier.height(14.u))
                MetaLine(Glyphs.Person, v.artist ?: "Unknown artist", 28, Pal.Cream)
                if (v.album != null) {
                    Spacer(Modifier.height(10.u))
                    MetaLine(Glyphs.Disc, v.album, 24, Pal.Cream2)
                }
                Spacer(Modifier.weight(1f))
                MiniSpectrum(Modifier.fillMaxWidth().height(74.u), openDisp)
            }
        }
        Spacer(Modifier.weight(0.5f))
        ProgressRow(v)
        Spacer(Modifier.weight(0.5f))
        TransportRow(v)
        if (v.upNext != null) {
            Spacer(Modifier.height(12.u))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Up next", 17, color = Pal.Cream3, bold = true)
                Spacer(Modifier.width(12.u))
                Txt(v.upNext, 18, color = Pal.Cream2)
            }
        }
        Spacer(Modifier.weight(0.25f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Title(text: String) {
    val scroll = LocalMarquee.current
    Txt(
        text, 46, bold = true,
        modifier = if (scroll) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
        ellipsis = !scroll,
    )
}

@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, size: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, size * 0.9f, Pal.Cream3)
        Spacer(Modifier.width(14.u))
        Txt(text, size, color = color)
    }
}

@Composable
fun AlbumArt(
    art: ImageBitmap?,
    modifier: Modifier,
    playing: Boolean = false,
    progress: () -> Float = { 0.12f },
    title: String? = null,
    artist: String? = null,
) {
    val k = LocalU.current
    Box(
        modifier
            .drawWithCache {
                val d = density * k
                onDrawBehind {
                    drawRect(Color.Black)
                    drawRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(d, d), size = Size(size.width - 2 * d, size.height - 2 * d), style = Stroke(d))
                }
            }
            .padding(3.u),
    ) {
        if (art != null) {
            Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val g = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f), 0.45f to Color.Transparent,
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    )
                    onDrawBehind { drawRect(g) }
                },
            )
        } else {
            Turntable(playing, progress, title, artist, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MiniSpectrum(modifier: Modifier, openDisp: () -> Unit) {
    val (anim, frame) = rememberSpectrum(24)
    val status by Graph.analyzer.status.collectAsStateWithLifecycle()
    Box(modifier.clickable(remember { MutableInteractionSource() }, null) { openDisp() }) {
        SpectrumMeter(anim, frame, rows = 9, modifier = Modifier.fillMaxSize(), gapX = 5f, gapY = 3f)
        if (analyzerNote(status) != null) {
            Txt("SIM", 13, color = Pal.Cream3, bold = true, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun ProgressRow(v: SessionView) {
    val now by rememberElapsed(if (v.playing) 250 else 1000)
    val pos = v.positionNow(now)
    val dur = v.durationMs
    Row(Modifier.fillMaxWidth().height(36.u), verticalAlignment = Alignment.CenterVertically) {
        SegText(Fmt.track(pos), 25, Modifier.widthIn(min = 92.u))
        Spacer(Modifier.width(16.u))
        SegmentProgress(
            fraction = if (dur > 0) pos / dur.toFloat() else 0f,
            modifier = Modifier.weight(1f).height(15.u),
            onSeek = if (v.canSeek) { f -> Graph.media.seekTo((f * dur).toLong()) } else null,
        )
        Spacer(Modifier.width(16.u))
        SegText(if (dur > 0) "-" + Fmt.track(max(0L, dur - pos)) else "-:--", 25)
    }
}

@Composable
fun TransportRow(v: SessionView?) {
    val m = Graph.media
    Row(Modifier.fillMaxWidth().height(86.u), horizontalArrangement = Arrangement.spacedBy(12.u)) {
        val canShuffle = v?.canShuffle == true
        val shuffleOn = v != null && v.shuffle > PlaybackStateCompat.SHUFFLE_MODE_NONE
        HardKey(
            onClick = { m.toggleShuffle() },
            modifier = Modifier.weight(0.64f).fillMaxHeight(),
            enabled = canShuffle,
            led = if (canShuffle && v!!.shuffle >= 0) shuffleOn else null,
        ) { ink -> Glyph(Glyphs.Shuffle, 34, ink) }
        HardKey(
            onClick = { m.previous() },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { ink -> Glyph(Glyphs.Prev, 40, ink) }
        HardKey(
            onClick = { m.playPause() },
            modifier = Modifier.weight(1.18f).fillMaxHeight(),
            primary = true,
        ) { ink ->
            val g = when {
                v == null -> Glyphs.PlayPause
                v.playing -> Glyphs.Pause
                else -> Glyphs.Play
            }
            Glyph(g, 44, ink)
        }
        HardKey(
            onClick = { m.next() },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { ink -> Glyph(Glyphs.Next, 40, ink) }
        val canRepeat = v?.canRepeat == true
        val repeatMode = v?.repeat ?: -1
        HardKey(
            onClick = { m.cycleRepeat() },
            modifier = Modifier.weight(0.64f).fillMaxHeight(),
            enabled = canRepeat,
            led = if (canRepeat && repeatMode >= 0) repeatMode > PlaybackStateCompat.REPEAT_MODE_NONE else null,
        ) { ink ->
            Box(contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Repeat, 34, ink)
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
                    Txt("1", 13, color = ink, bold = true)
                }
            }
        }
    }
}

@Composable
private fun NothingPlaying(openSources: () -> Unit) {
    val host = LocalHost.current
    val settings = LocalSettings.current
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    val shortcuts = remember(apps, settings.sources) {
        val pinned = settings.sources.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
        (pinned.ifEmpty { Graph.apps.suggestSources() }).take(4)
    }
    val last = settings.lastSourcePkg?.let { Graph.apps.labelFor(it) }
    val btReady by Graph.media.btReady.collectAsStateWithLifecycle()
    val btPhone by Graph.media.btPhone.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.5f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(null, Modifier.size(170.u))
            Spacer(Modifier.width(28.u))
            Column {
                Txt("Nothing playing", 40, bold = true)
                Spacer(Modifier.height(12.u))
                Txt(
                    when {
                        btReady && btPhone == true -> "Your phone is on Bluetooth. Press BT Audio or play to hear it."
                        btReady && btPhone == null -> "Press BT Audio to switch the sound to your phone."
                        last != null -> "Play resumes $last, or pick a source below."
                        else -> "Pick a source below, or start music on your phone."
                    },
                    22, color = Pal.Cream2, maxLines = 2,
                )
                Spacer(Modifier.height(10.u))
                Txt(
                    "No track info from the unit's Bluetooth player? These keys still control it.",
                    18, color = Pal.Cream3, maxLines = 2, lineHeight = 1.3f,
                )
            }
        }
        Spacer(Modifier.weight(0.6f))
        Row(Modifier.fillMaxWidth().height(70.u), horizontalArrangement = Arrangement.spacedBy(12.u)) {
            if (btReady) {
                HardKey(onClick = { Graph.media.startBluetooth() }, modifier = Modifier.weight(1f).fillMaxHeight(), lit = true) { ink ->
                    Row(Modifier.padding(horizontal = 12.u), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Glyphs.Bluetooth, 28, ink)
                        Spacer(Modifier.width(8.u))
                        Txt("BT Audio", 19, color = ink, bold = true)
                    }
                }
            }
            shortcuts.take(if (btReady) 3 else 4).forEach { a ->
                HardKey(onClick = { host.launchApp(a.pkg) }, modifier = Modifier.weight(1f).fillMaxHeight()) { ink ->
                    Row(Modifier.padding(horizontal = 12.u), verticalAlignment = Alignment.CenterVertically) {
                        a.icon?.let { Image(it, null, Modifier.size(34.u)) }
                        Spacer(Modifier.width(10.u))
                        Txt(a.label, 19, color = ink, bold = true)
                    }
                }
            }
            if (shortcuts.size < 4) {
                HardKey(onClick = openSources, modifier = Modifier.weight(1f).fillMaxHeight()) { ink ->
                    Txt("All sources", 19, color = ink, bold = true)
                }
            }
        }
        Spacer(Modifier.weight(0.4f))
        TransportRow(null)
        Spacer(Modifier.weight(0.2f))
    }
}

@Composable
private fun MediaAccessPrompt() {
    val host = LocalHost.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Txt("Media access is off", 38, bold = true)
        Spacer(Modifier.height(14.u))
        Txt(
            "Android only shows what's playing, and the next turn from your maps app, to apps with " +
                "notification access. Roadster reads it on the unit and never stores or sends it.",
            22, color = Pal.Cream2, maxLines = 4, lineHeight = 1.4f,
        )
        Spacer(Modifier.height(26.u))
        TextKey("Allow notification access", host::openNotificationAccess, lit = true, width = 360f)
        Spacer(Modifier.height(18.u))
        Txt(
            "No such settings page on your unit? From a computer with adb:\n" +
                "adb shell cmd notification allow_listener com.oemplus.roadster/.media.MediaListener",
            16, color = Pal.Cream3, maxLines = 3, lineHeight = 1.4f,
        )
    }
}

@Composable
private fun NaviPanel(modifier: Modifier) {
    val nav by Graph.nav.info.collectAsStateWithLifecycle()
    val gps by Graph.gps.state.collectAsStateWithLifecycle()
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val host = LocalHost.current
    val naviPkg = remember(apps, settings.naviPkg) { settings.naviPkg ?: Graph.apps.defaultNavi() }
    val naviLabel = naviPkg?.let { Graph.apps.labelFor(it) }
    val n = nav
    GlassPanel(
        modifier.clickable(remember { MutableInteractionSource() }, null) {
            val pkg = n?.pkg ?: naviPkg
            if (pkg != null) host.launchApp(pkg)
        },
        caption = "Navigation",
        trailing = { (n?.app ?: naviLabel)?.let { Txt(it, 17, color = Pal.Cream3) } },
    ) {
        when {
            n != null -> RouteView(n)
            !gps.permission -> LocationPrompt()
            else -> HeadingView(gps, naviLabel)
        }
    }
}

@Composable
private fun RouteView(n: NavInfo) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(n, Modifier.size(92.u))
            Spacer(Modifier.width(16.u))
            EngravedVRule(Modifier.height(84.u))
            Spacer(Modifier.width(18.u))
            Column(Modifier.weight(1f)) {
                Txt(n.distance ?: "Next", if (n.distance != null) 40 else 22, bold = n.distance != null, color = if (n.distance != null) Pal.Cream else Pal.Cream3)
                Spacer(Modifier.height(8.u))
                Txt(n.road ?: n.instruction ?: "", 26, maxLines = 2, lineHeight = 1.25f)
            }
        }
        EngravedRule(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(46.u), verticalAlignment = Alignment.CenterVertically) {
            Txt("ETA", 17, color = Pal.Cream3, bold = true)
            Spacer(Modifier.width(12.u))
            Txt(n.eta ?: "--:--", 26, bold = true)
            Spacer(Modifier.weight(1f))
            n.remaining?.let { Txt(it, 22, color = Pal.Cream2) }
        }
    }
}

@Composable
private fun ManeuverIcon(n: NavInfo, modifier: Modifier) {
    if (n.maneuver == Maneuver.UNKNOWN && n.icon != null) {
        Image(n.icon, null, modifier, colorFilter = ColorFilter.tint(Pal.Cream))
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { Glyph(Glyphs.maneuver(n.maneuver), 92, Pal.Cream) }
    }
}

@Composable
private fun HeadingView(gps: GpsState, naviLabel: String?) {
    val settings = LocalSettings.current
    val fix = gps.fix?.takeIf { it.gps && it.ageMs() < 5000 }
    val heading = gps.heading.takeIf { fix != null }
    Column(Modifier.fillMaxSize()) {
        CompassTape(heading, Modifier.fillMaxWidth().height(64.u))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                Txt("Heading", 16, color = Pal.Cream3, bold = true)
                Spacer(Modifier.height(6.u))
                Txt(heading?.let { "${Fmt.compass(it)}  ${it.roundToInt()}°" } ?: "—", 30, bold = true)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Txt("GPS speed", 16, color = Pal.Cream3, bold = true)
                Spacer(Modifier.height(6.u))
                Row(verticalAlignment = Alignment.Bottom) {
                    SpeedReadout(fix?.speedMps, settings.speedUnit, 30)
                    Spacer(Modifier.width(8.u))
                    Txt(Fmt.speedUnit(settings.speedUnit), 18, color = Pal.Cream2)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Txt(
            when {
                !gps.enabled -> "Location is switched off in Android settings"
                fix == null -> "Waiting for GPS · ${gps.satsUsed}/${gps.satsInView} satellites"
                naviLabel != null -> "No route · tap to open $naviLabel"
                else -> "No route · hold NAVI to choose a maps app"
            },
            17, color = Pal.Cream3,
        )
    }
}

@Composable
private fun LocationPrompt() {
    val host = LocalHost.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Txt("Location is off", 28, bold = true)
        Spacer(Modifier.height(8.u))
        Txt("Heading, GPS speed, trip and sunset need it.", 19, color = Pal.Cream2, maxLines = 2)
        Spacer(Modifier.height(16.u))
        TextKey("Allow location", host::requestLocation, lit = true, width = 230f)
    }
}

@Composable
private fun InfoGlance(modifier: Modifier) {
    val settings = LocalSettings.current
    val now by rememberWallClock(30_000)
    val weather by Graph.weather.state.collectAsStateWithLifecycle()
    val trips by Graph.trips.state.collectAsStateWithLifecycle()
    val sun by Graph.sun.state.collectAsStateWithLifecycle()
    val bests by Graph.perf.bests.collectAsStateWithLifecycle()
    GlassPanel(modifier, caption = "Information") {
        Column(Modifier.fillMaxSize()) {
            InfoRow(Glyphs.Calendar, Fmt.date(now), null, null)
            EngravedRule(Modifier.fillMaxWidth())
            if (settings.weather) {
                val w = weather
                val reading = (w as? WeatherState.Ok)?.now ?: (w as? WeatherState.Offline)?.last
                val (wValue, wUnit) = when {
                    reading != null -> "${Fmt.temp(reading.tempC, settings.tempUnit)}" to Fmt.tempUnit(settings.tempUnit)
                    else -> "—" to null
                }
                val wNote = when {
                    reading != null && reading.stale -> "old"
                    reading != null -> WeatherHub.describe(reading.code)
                    w is WeatherState.Offline -> "offline"
                    w is WeatherState.NoPosition -> "no GPS yet"
                    else -> null
                }
                InfoRow(
                    reading?.let { Glyphs.weather(it.code, it.isDay) } ?: Glyphs.Thermo,
                    "Outside", wValue, wUnit, note = wNote,
                )
            } else {
                val metric = settings.speedUnit == SpeedUnit.KMH
                val best = bests[PerfTimer.key(PerfMode.SHORT, metric)]
                InfoRow(
                    Glyphs.Stopwatch, "${PerfEngine.label(PerfMode.SHORT, metric)} best",
                    best?.let { String.format(java.util.Locale.US, "%.2f", it.ms / 1000.0) } ?: "—",
                    if (best != null) "s" else null,
                    note = best?.let { Fmt.dateShort(it.at) } ?: "time one in PERF",
                )
            }
            EngravedRule(Modifier.fillMaxWidth())
            val d = trips.drive
            InfoRow(
                Glyphs.Route, "This drive",
                "${Fmt.dist(d.distanceM, settings.speedUnit)} ${Fmt.distUnit(settings.speedUnit)}",
                null, note = Fmt.hmm(d.elapsedMs),
            )
            EngravedRule(Modifier.fillMaxWidth())
            val sunsetNext = sun.sunset != null && (sun.sunrise == null || now < (sun.sunset ?: 0L)) && now >= (sun.sunrise ?: 0L)
            val label = if (sunsetNext) "Sunset" else "Sunrise"
            val at = if (sunsetNext) sun.sunset else sun.sunrise
            InfoRow(
                if (sunsetNext) Glyphs.Sunset else Glyphs.Sunrise, label,
                at?.let { Fmt.time(it, settings.clock24) } ?: "—", null,
                note = if (at == null) "needs GPS" else null,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    unit: String?,
    note: String? = null,
) {
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 27, Pal.Cream2)
        Spacer(Modifier.width(16.u))
        Txt(label, 23, color = Pal.Cream)
        Spacer(Modifier.weight(1f))
        if (note != null) {
            Txt(note, 17, color = Pal.Cream3, align = TextAlign.End)
            Spacer(Modifier.width(12.u))
        }
        if (value != null) Txt(value, 25, bold = true)
        if (unit != null) {
            Spacer(Modifier.width(4.u))
            Txt(unit, 18, color = Pal.Cream2)
        }
    }
}
