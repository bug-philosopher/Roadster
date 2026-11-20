package com.oemplus.roadster.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.BuildConfig
import com.oemplus.roadster.Graph
import com.oemplus.roadster.core.AnalyzerInput
import com.oemplus.roadster.core.Dimmer
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.core.Illumination
import com.oemplus.roadster.core.SpeedUnit
import com.oemplus.roadster.core.TempUnit
import com.oemplus.roadster.location.TripService
import com.oemplus.roadster.media.AnalyzerStatus
import com.oemplus.roadster.system.Diagnostics

private enum class Section(val en: String, val jp: String) {
    ACCESS("Access", "許可"),
    KEYS("Keys", "キー"),
    DISPLAY("Display", "画面"),
    UNITS("Units", "単位"),
    STARTUP("Startup", "起動"),
    DIAG("Diagnostics", "診断"),
    ABOUT("About", "情報"),
}

private var lastSection by mutableStateOf(Section.ACCESS)

@Composable
fun SetPage(onPick: (KeyTarget) -> Unit) {
    var section by remember { mutableStateOf(lastSection) }
    LaunchedEffect(section) { lastSection = section }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(250.u).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.u)) {
            Section.entries.forEach { s ->
                HardKey(onClick = { section = s }, modifier = Modifier.fillMaxWidth().weight(1f), lit = s == section) { ink ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.u), verticalAlignment = Alignment.CenterVertically) {
                        Txt(s.en, 23, color = ink, bold = true)
                        Spacer(Modifier.weight(1f))
                        Txt(s.jp, 17, color = ink.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.u))
        GlassPanel(Modifier.weight(1f).fillMaxHeight(), caption = section.en) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (section) {
                    Section.ACCESS -> AccessSection()
                    Section.KEYS -> KeysSection(onPick)
                    Section.DISPLAY -> DisplaySection()
                    Section.UNITS -> UnitsSection()
                    Section.STARTUP -> StartupSection()
                    Section.DIAG -> DiagnosticsSection()
                    Section.ABOUT -> AboutSection()
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    detail: String? = null,
    ok: Boolean? = null,
    status: String? = null,
    control: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.u), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, 23, bold = true)
                if (ok != null) {
                    Spacer(Modifier.width(14.u))
                    Lamp(ok)
                    if (status != null) {
                        Spacer(Modifier.width(8.u))
                        Txt(status, 17, color = if (ok) Pal.Ok else Pal.Warn)
                    }
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(7.u))
                Txt(detail, 18, color = Pal.Cream2, maxLines = 4, lineHeight = 1.35f)
            }
        }
        Spacer(Modifier.width(22.u))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.u)) { control() }
    }
    EngravedRule(Modifier.fillMaxWidth())
}

@Composable
private fun AccessSection() {
    val host = LocalHost.current
    val access by Graph.access.state.collectAsStateWithLifecycle()
    val analyzer by Graph.analyzer.status.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    SettingRow(
        "Media & navigation",
        if (access.notifications) "Reads what's playing and the next turn from your maps app. Stays on the unit."
        else "Notification access lets Roadster show and control what's playing, and show the next turn. " +
            "No settings page on the unit? adb shell cmd notification allow_listener com.oemplus.roadster/.media.MediaListener",
        ok = access.notifications, status = if (access.notifications) "Allowed" else "Off",
    ) { TextKey(if (access.notifications) "Settings" else "Allow", host::openNotificationAccess, lit = !access.notifications, width = 150f) }
    SettingRow(
        "Location",
        "GPS speed, heading, altitude, trip computer, sunset and local weather.",
        ok = access.location, status = if (access.location) "Allowed" else "Off",
    ) { if (!access.location) TextKey("Allow", host::requestLocation, lit = true, width = 150f) }
    SettingRow(
        "Spectrum analyzer",
        "Android only gives the audio-output visualizer to apps holding the microphone permission. " +
            "Roadster never records. Some units refuse output capture anyway; the analyzer then says “Simulated”.",
        ok = analyzer == AnalyzerStatus.LIVE || analyzer == AnalyzerStatus.OFF && access.microphone,
        status = when (analyzer) {
            AnalyzerStatus.LIVE -> "Live"
            AnalyzerStatus.SIMULATED -> "Simulated"
            AnalyzerStatus.NEEDS_PERMISSION -> "No permission"
            AnalyzerStatus.UNAVAILABLE -> "No signal"
            AnalyzerStatus.OFF -> if (access.microphone) "Ready" else "No permission"
        },
    ) {
        if (!access.microphone) TextKey("Allow", host::requestMicrophone, lit = true, width = 130f)
        ChoiceKeys(
            listOf(AnalyzerInput.AUTO, AnalyzerInput.SIMULATED), settings.analyzerInput,
            { if (it == AnalyzerInput.AUTO) "Audio" else "Simulated" },
            { v -> Graph.prefs.update { it.copy(analyzerInput = v) }; Graph.analyzer.restart() },
            keyWidth = 132f,
        )
    }
    if (Build.VERSION.SDK_INT >= 33) {
        SettingRow(
            "Notifications",
            "Only used for the small “Trip computer on” notice Android requires while GPS runs in the background.",
            ok = access.postNotifications, status = if (access.postNotifications) "Allowed" else "Off",
        ) { if (!access.postNotifications) TextKey("Allow", host::requestLocation, lit = true, width = 150f) }
    }
}

@Composable
private fun KeysSection(onPick: (KeyTarget) -> Unit) {
    val settings = LocalSettings.current
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    @Composable
    fun appName(pkg: String?, auto: String?): String = when {
        pkg != null -> Graph.apps.labelFor(pkg)
        auto != null -> "${Graph.apps.labelFor(auto)} (automatic)"
        else -> "Not set"
    }
    SettingRow(
        "NAVI key",
        "Opens: ${appName(settings.naviPkg, Graph.apps.defaultNavi())}. Its turn-by-turn notification feeds the Navigation panel.",
    ) { TextKey("Change", { onPick(KeyTarget.NAVI) }, width = 150f) }
    SettingRow(
        "LINK key",
        "Opens: ${appName(settings.linkPkg, Graph.apps.defaultLink())}. Pick the unit's CarPlay / Android Auto app " +
            "(often called ZLINK, TLink, AutoKit or “Car Auto”).",
    ) { TextKey("Change", { onPick(KeyTarget.LINK) }, width = 150f) }
    val names = settings.sources.mapNotNull { p -> apps.firstOrNull { it.pkg == p }?.label }
    SettingRow(
        "SRC list",
        if (names.isEmpty()) "Automatic: media apps found on the unit. Pin your own to put them first."
        else names.joinToString(" · "),
    ) { TextKey("Edit", { onPick(KeyTarget.SOURCE) }, width = 150f) }
}

@Composable
private fun DisplaySection() {
    val s = LocalSettings.current
    SettingRow("Illumination", s.illumination.note) {
        ChoiceKeys(Illumination.entries, s.illumination, { it.label }, { v -> Graph.prefs.update { it.copy(illumination = v) } }, keyWidth = 104f)
    }
    SettingRow(
        "Day / night",
        "Auto: bright from sunrise to sunset, then dark and dimmed. Bright suits sunlight, Dark keeps " +
            "the dark face all day, Night is dark and dimmed.",
    ) {
        ChoiceKeys(
            listOf(Dimmer.AUTO, Dimmer.DAY, Dimmer.DARK, Dimmer.NIGHT), s.dimmer,
            { when (it) { Dimmer.AUTO -> "Auto"; Dimmer.DAY -> "Bright"; Dimmer.DARK -> "Dark"; Dimmer.NIGHT -> "Night" } },
            { v -> Graph.prefs.update { it.copy(dimmer = v) } }, keyWidth = 104f,
        )
    }
    SettingRow("Full screen", "Hide Android's bars. Swipe in from the edge to bring them back.") {
        ToggleKey(s.fullscreen, { v -> Graph.prefs.update { it.copy(fullscreen = v) } })
    }
    SettingRow("Scroll long titles", "Long song titles scroll across, like the old VFD did. Off cuts them short.") {
        ToggleKey(s.marquee, { v -> Graph.prefs.update { it.copy(marquee = v) } })
    }
    SettingRow("Clock") {
        ChoiceKeys(listOf(false, true), s.clock24, { if (it) "24 h" else "12 h" }, { v -> Graph.prefs.update { it.copy(clock24 = v) } }, keyWidth = 110f)
    }
    SettingRow("Faceplate name", "Printed at the top left.") {
        BrandField(s.brand)
    }
}

@Composable
private fun BrandField(current: String) {
    var text by remember(current) { mutableStateOf(current) }
    val k = LocalU.current
    BasicTextField(
        value = text,
        onValueChange = { v ->
            val t = v.uppercase().take(16)
            text = t
            Graph.prefs.update { it.copy(brand = t) }
        },
        singleLine = true,
        textStyle = TextStyle(color = Pal.Cream, fontFamily = Fonts.UD, fontWeight = FontWeight.Bold, fontSize = 21.us, letterSpacing = 0.2f.em, textAlign = TextAlign.Start),
        cursorBrush = SolidColor(LocalIllum.current.base),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        modifier = Modifier
            .size(300.u, 54.u)
            .drawWithCache {
                val d = density * k
                onDrawBehind {
                    drawRect(Color.Black)
                    drawRect(Pal.Glass, topLeft = Offset(d, d), size = Size(size.width - 2 * d, size.height - 2 * d))
                    drawRect(Color.White.copy(alpha = 0.08f), topLeft = Offset(0f, size.height - d), size = Size(size.width, d))
                    drawRect(Color.Black, style = Stroke(d))
                }
            }
            .padding(horizontal = 16.u, vertical = 14.u),
    )
}

@Composable
private fun UnitsSection() {
    val s = LocalSettings.current
    SettingRow("Speed & distance") {
        ChoiceKeys(listOf(SpeedUnit.MPH, SpeedUnit.KMH), s.speedUnit, { if (it == SpeedUnit.MPH) "mph · mi" else "km/h · km" }, { v -> Graph.prefs.update { it.copy(speedUnit = v) } }, keyWidth = 140f)
    }
    SettingRow("Temperature") {
        ChoiceKeys(listOf(TempUnit.F, TempUnit.C), s.tempUnit, { Fmt.tempUnit(it) }, { v -> Graph.prefs.update { it.copy(tempUnit = v) } }, keyWidth = 110f)
    }
    SettingRow(
        "Outside temperature",
        "Off by default: in the car the unit is usually offline. With a phone hotspot or SIM it shows outside " +
            "temperature from open-meteo.com, sending your position rounded to about 1 km at most every 20 minutes.",
    ) { ToggleKey(s.weather, { v -> Graph.prefs.update { it.copy(weather = v) } }) }
}

@Composable
private fun StartupSection() {
    val s = LocalSettings.current
    val host = LocalHost.current
    val access by Graph.access.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingRow(
        "Open on start-up",
        "Opens Roadster after the unit boots. Android needs “display over other apps” for that. " +
            "Units that sleep instead of booting use their own auto-start app setting.",
        ok = if (s.autostart) access.overlay else null,
        status = if (s.autostart) (if (access.overlay) "Ready" else "Needs permission") else null,
    ) {
        if (s.autostart && !access.overlay) TextKey("Allow", host::openOverlayPermission, lit = true, width = 130f)
        ToggleKey(s.autostart, { v -> Graph.prefs.update { it.copy(autostart = v) }; if (v && !access.overlay) host.openOverlayPermission() })
    }
    SettingRow(
        "Use as Home screen",
        "Home brings up Roadster instead of the unit's launcher. Switch on, then choose Roadster as the home app. " +
            "APPS still lists everything.",
    ) {
        if (s.homeScreen) TextKey("Choose", host::openHomeSettings, width = 130f)
        ToggleKey(s.homeScreen, { v ->
            Graph.prefs.update { it.copy(homeScreen = v) }
            host.setHomeAlias(v)
            if (v) host.openHomeSettings()
        })
    }
    SettingRow(
        "Trip computer in background",
        "Keeps GPS counting while CarPlay or Android Auto has the screen. Shows a quiet notification.",
    ) {
        ToggleKey(s.tripTracking, { v -> Graph.prefs.update { it.copy(tripTracking = v) }; TripService.sync(ctx) })
    }
}

@Composable
private fun AboutSection() {
    val trips by Graph.trips.state.collectAsStateWithLifecycle()
    val s = LocalSettings.current
    SettingRow("Roadster ${BuildConfig.VERSION_NAME}", "A home screen for an Android head unit, styled after early-2000s Japanese double-DIN units.")
    SettingRow("GPS odometer", "${Fmt.dist(trips.odoM, s.speedUnit)} ${Fmt.distUnit(s.speedUnit)} counted since install.")
    SettingRow(
        "Type",
        "BIZ UDPGothic by Morisawa, and DSEG7 by keshikan. Both under the SIL Open Font License 1.1.",
    )
    SettingRow("Weather data", "Open-Meteo.com, CC BY 4.0.")
    Spacer(Modifier.height(10.u))
}

@Composable
private fun DiagnosticsSection() {
    val ctx = LocalContext.current
    val bt by Graph.media.bt.collectAsStateWithLifecycle()
    val mtc by Graph.media.mtcState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(Diagnostics.report(ctx)) }
    var saved by remember { mutableStateOf<String?>(null) }
    if (mtc.present) {
        SettingRow(
            "Bluetooth player",
            when {
                mtc.ready && mtc.connected -> "This unit's own Bluetooth service (HCT). Phone connected" +
                    (mtc.title?.let { " · $it" } ?: "") + ". BT Audio under SRC makes it the source."
                mtc.ready -> "This unit's own Bluetooth service (HCT). No phone connected right now."
                mtc.bound -> "This unit's Bluetooth service answered, but not in a way Roadster knows yet. Save the report below and attach it to a GitHub issue."
                else -> "This unit's Bluetooth service is there but wouldn't accept a connection. Save the report below and attach it to a GitHub issue."
            },
            ok = mtc.ready,
            status = if (mtc.ready) "Linked" else "Not linked",
        ) { TextKey("Refresh", { Graph.media.connect(); report = Diagnostics.report(ctx) }, width = 130f) }
    } else {
        SettingRow(
            "Bluetooth player",
            "${bt.service ?: "No Bluetooth media service found"} · ${bt.status}. Roadster connects to the unit's " +
                "Bluetooth player directly; pressing play or picking it under SRC turns the sound on.",
            ok = bt.status == "Connected",
            status = if (bt.status == "Connected") "Linked" else "Not linked",
        ) { TextKey("Reconnect", { Graph.media.reconnectBluetooth(); report = Diagnostics.report(ctx) }, width = 150f) }
    }
    SettingRow(
        "Audio report",
        "What Roadster can see of this unit's players. If Bluetooth still isn't right, play a song from your " +
            "phone, press Save with a USB stick plugged in, and attach the file to a GitHub issue. Apps and song titles only, never messages.",
        ok = saved?.let { it.startsWith("Saved") },
        status = saved,
    ) {
        TextKey("Refresh", { report = Diagnostics.report(ctx); saved = null }, width = 130f)
        TextKey("Save", {
            saved = "Saving…"
            scope.launch {
                val full = withContext(Dispatchers.IO) { Diagnostics.report(ctx, deep = true) }
                report = Diagnostics.report(ctx)
                saved = withContext(Dispatchers.IO) { Diagnostics.save(ctx, full) }?.let { "Saved to $it" } ?: "Couldn't save"
            }
        }, width = 120f, lit = true)
    }
    Spacer(Modifier.height(12.u))
    Txt(report, 15, color = Pal.Cream2, maxLines = 400, lineHeight = 1.4f, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.u))
}
