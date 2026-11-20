package com.oemplus.roadster.ui

import android.app.PendingIntent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.apps.AppEntry
import com.oemplus.roadster.core.resolve
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.location.WeatherState
import kotlinx.coroutines.delay
import kotlin.math.min

enum class Page(val en: String, val jp: String) {
    AUDIO("AUDIO", "オーディオ"),
    DISP("DISP", "表示"),
    APPS("APPS", "アプリ"),
    INFO("INFO", "情報"),
    PERF("PERF", "計測"),
    SET("SET", "設定"),
}

enum class KeyTarget { NAVI, LINK, SOURCE }

sealed interface Sheet {
    data object Sources : Sheet
    data class Pick(val target: KeyTarget) : Sheet
    data class Actions(val app: AppEntry) : Sheet
}

interface Host {
    fun requestLocation()
    fun requestMicrophone()
    fun openNotificationAccess()
    fun openOverlayPermission()
    fun openHomeSettings()
    fun setHomeAlias(on: Boolean)
    fun launchApp(pkg: String): Boolean
    fun openAppInfo(pkg: String)
    fun sendPendingIntent(pi: PendingIntent): Boolean
    fun toBackground()
}

val LocalHost = staticCompositionLocalOf<Host> { error("Host not provided") }

@Composable
fun RoadsterRoot(host: Host) {
    val settings by Graph.prefs.settings.collectAsStateWithLifecycle()
    val sun by Graph.sun.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val (dark, dim) = settings.dimmer.resolve(sun.night)
    val palette = if (dark) NightPalette else DayPalette
    SideEffect { if (Pal.palette != palette) Pal.palette = palette }
    BoxWithConstraints(Modifier.fillMaxSize().background(Pal.Bezel)) {
        val u = min(maxWidth.value / 1280f, maxHeight.value / 720f)
        CompositionLocalProvider(
            LocalU provides u,
            LocalIllum provides settings.illumination.illum(day = !dark),
            LocalSettings provides settings,
            LocalHost provides host,
            LocalMarquee provides (settings.marquee && !reduceMotion),
            LocalReduceMotion provides reduceMotion,
        ) {
            Shell(reduceMotion)
        }
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = NightPalette.dim)))
    }
}

@Composable
private fun Shell(reduceMotion: Boolean) {
    var page by rememberSaveable { mutableStateOf(Page.AUDIO) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var fullDisp by remember { mutableStateOf(false) }
    val host = LocalHost.current

    BackHandler {
        when {
            sheet != null -> sheet = null
            fullDisp -> fullDisp = false
            page != Page.AUDIO -> page = Page.AUDIO
            else -> host.toBackground()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.u)) {
            if (!fullDisp) Header(page)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = if (fullDisp) 14.u else 0.dp),
            ) {
                when (page) {
                    Page.AUDIO -> AudioPage(
                        openSources = { sheet = Sheet.Sources },
                        openDisp = { page = Page.DISP },
                    )
                    Page.DISP -> DispPage(full = fullDisp, onToggleFull = { fullDisp = !fullDisp }, reduceMotion = reduceMotion)
                    Page.APPS -> AppsPage(onActions = { sheet = Sheet.Actions(it) })
                    Page.INFO -> InfoPage()
                    Page.PERF -> PerfPage()
                    Page.SET -> SetPage(onPick = { sheet = Sheet.Pick(it) })
                }
            }
            if (!fullDisp) {
                KeyRow(
                    page = page,
                    onPage = { page = it; fullDisp = false },
                    onPick = { sheet = Sheet.Pick(it) },
                )
            }
        }
        when (val s = sheet) {
            null -> Unit
            Sheet.Sources -> SourcesSheet(onDismiss = { sheet = null }, onEdit = { sheet = Sheet.Pick(KeyTarget.SOURCE) })
            is Sheet.Pick -> PickAppSheet(s.target, onDismiss = { sheet = null })
            is Sheet.Actions -> AppActionsSheet(s.app, onDismiss = { sheet = null })
        }
    }
}

@Composable
private fun Header(page: Page) {
    val settings = LocalSettings.current
    val current by Graph.media.current.collectAsStateWithLifecycle()
    val title = when (page) {
        Page.AUDIO -> current?.source?.uppercase() ?: "AUDIO"
        Page.DISP -> "DISPLAY · " + settings.dispMode.label.uppercase()
        Page.APPS -> "APPLICATIONS"
        Page.INFO -> "INFORMATION"
        Page.PERF -> "PERFORMANCE"
        Page.SET -> "SETTINGS"
    }
    Row(Modifier.fillMaxWidth().height(66.u), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(300.u).padding(start = 8.u)) {
            val px = pxPerU()
            Txt(
                settings.brand.uppercase().ifBlank { "ROADSTER" }, 23f, color = Pal.Cream2, bold = true, tracking = 0.24f,
                shadow = androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(0f, 1.6f * px), 0f),
            )
        }
        EngravedVRule(Modifier.height(36.u))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Txt(title, 29, bold = true, tracking = 0.07f, align = TextAlign.Center)
        }
        EngravedVRule(Modifier.height(36.u))
        StatusCluster(Modifier.width(300.u))
    }
}

@Composable
private fun StatusCluster(modifier: Modifier) {
    val settings = LocalSettings.current
    val now by rememberWallClock(1000)
    val gps by Graph.gps.state.collectAsStateWithLifecycle()
    val weather by Graph.weather.state.collectAsStateWithLifecycle()
    val fixFresh = gps.fix?.let { it.gps && it.ageMs() < 5000 } == true
    Row(modifier.padding(end = 8.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        Glyph(Glyphs.Gps, 24, if (fixFresh) Pal.Ok else Pal.Cream4)
        Spacer(Modifier.width(18.u))
        val (digits, ampm) = Fmt.clock(now, settings.clock24)
        SegText(digits, 30)
        if (ampm != null) {
            Spacer(Modifier.width(6.u))
            Txt(ampm, 15, color = Pal.Cream2, bold = true)
        }
        val w = weather
        val reading = when (w) {
            is WeatherState.Ok -> w.now
            is WeatherState.Offline -> w.last
            else -> null
        }
        if (reading != null) {
            Spacer(Modifier.width(20.u))
            Txt(
                "${Fmt.temp(reading.tempC, settings.tempUnit)}${Fmt.tempUnit(settings.tempUnit)}",
                27,
                color = if (reading.stale) Pal.Cream3 else Pal.Cream,
            )
        }
    }
}

@Composable
private fun KeyRow(page: Page, onPage: (Page) -> Unit, onPick: (KeyTarget) -> Unit) {
    val host = LocalHost.current
    val settings = LocalSettings.current
    fun launchOrPick(target: KeyTarget, chosen: String?, fallback: () -> String?) {
        val pkg = chosen?.takeIf { Graph.apps.isInstalled(it) } ?: fallback()
        if (pkg == null || !host.launchApp(pkg)) onPick(target)
    }
    Row(
        Modifier.fillMaxWidth().height(100.u).padding(top = 10.u, bottom = 12.u),
        horizontalArrangement = Arrangement.spacedBy(9.u),
    ) {
        val m = Modifier.weight(1f).fillMaxHeight()
        PageKey(Page.AUDIO, page, onPage, m)
        HardKey(
            onClick = { launchOrPick(KeyTarget.NAVI, settings.naviPkg) { Graph.apps.defaultNavi() } },
            onLongClick = { onPick(KeyTarget.NAVI) },
            modifier = m,
        ) { ink -> KeyLegend("NAVI", "ナビ", ink) }
        HardKey(
            onClick = { launchOrPick(KeyTarget.LINK, settings.linkPkg) { Graph.apps.defaultLink() } },
            onLongClick = { onPick(KeyTarget.LINK) },
            modifier = m,
        ) { ink -> KeyLegend("LINK", "スマホ連携", ink) }
        PageKey(Page.DISP, page, onPage, m)
        PageKey(Page.APPS, page, onPage, m)
        PageKey(Page.INFO, page, onPage, m)
        PageKey(Page.PERF, page, onPage, m)
        PageKey(Page.SET, page, onPage, Modifier.weight(0.78f).fillMaxHeight())
    }
}

@Composable
private fun PageKey(p: Page, current: Page, onPage: (Page) -> Unit, modifier: Modifier) {
    HardKey(onClick = { onPage(p) }, modifier = modifier, lit = p == current) { ink ->
        KeyLegend(p.en, p.jp, ink)
    }
}

@Composable
fun rememberWallClock(periodMs: Long): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs - (value % periodMs) + 5)
    }
}

@Composable
fun rememberElapsed(periodMs: Long): State<Long> = produceState(SystemClock.elapsedRealtime(), periodMs) {
    while (true) {
        value = SystemClock.elapsedRealtime()
        delay(periodMs)
    }
}
