package com.oemplus.roadster.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.location.GpsState
import com.oemplus.roadster.perf.PerfEngine
import com.oemplus.roadster.perf.PerfMode
import com.oemplus.roadster.perf.PerfPhase
import com.oemplus.roadster.perf.PerfResult
import com.oemplus.roadster.perf.PerfState
import com.oemplus.roadster.perf.PerfTimer
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val StageAmber = Color(0xFFF0A43A)

@Composable
fun PerfPage() {
    val st by Graph.perf.state.collectAsStateWithLifecycle()
    val last by Graph.perf.last.collectAsStateWithLifecycle()
    val bests by Graph.perf.bests.collectAsStateWithLifecycle()
    val gps by Graph.gps.state.collectAsStateWithLifecycle()
    val trips by Graph.trips.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize()) {
        RunPanel(st, gps, Modifier.weight(1.45f).fillMaxHeight())
        Spacer(Modifier.width(12.u))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TimeslipPanel(st.result ?: last, bests, Modifier.fillMaxWidth().weight(1.55f))
            Spacer(Modifier.height(12.u))
            BestPanel(bests, st.metric, trips.topMps, trips.topAt, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun RunPanel(st: PerfState, gps: GpsState, modifier: Modifier) {
    val host = LocalHost.current
    val running = st.phase == PerfPhase.RUNNING
    val armed = st.phase == PerfPhase.STAGING || st.phase == PerfPhase.READY || running || st.phase == PerfPhase.ABORTED
    val fresh = gps.fix?.let { it.gps && it.ageMs() < 3000 } == true
    GlassPanel(
        modifier,
        caption = "Timed run",
        trailing = {
            Lamp(fresh)
            Spacer(Modifier.width(10.u))
            Txt(
                if (!fresh) "No GPS fix" else "GPS ${(if (st.hz > 0f) st.hz else gps.hz).let { if (it > 0f) it.roundToInt().coerceAtLeast(1) else 1 }} Hz",
                17, color = Pal.Cream3,
            )
        },
    ) {
        if (!gps.permission) {
            Spacer(Modifier.weight(1f))
            Txt("Location is off", 30, bold = true)
            Spacer(Modifier.height(10.u))
            Txt("Runs are timed with the unit's GPS.", 20, color = Pal.Cream2)
            Spacer(Modifier.height(18.u))
            TextKey("Allow location", host::requestLocation, lit = true, width = 240f)
            Spacer(Modifier.weight(1f))
            return@GlassPanel
        }
        Row(Modifier.fillMaxWidth().height(68.u), horizontalArrangement = Arrangement.spacedBy(10.u)) {
            PerfMode.entries.forEach { m ->
                HardKey(
                    onClick = { Graph.perf.setMode(m) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    lit = m == st.mode,
                    enabled = !running,
                ) { ink ->
                    KeyLegend(
                        PerfEngine.label(m, st.metric),
                        if (m == PerfMode.DRAG) "ゼロヨン" else if (st.metric) "km/h" else "mph",
                        ink, enSize = 24, jpSize = 15,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.u))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Tree(st.phase, Modifier.width(86.u).fillMaxHeight())
            Spacer(Modifier.width(16.u))
            EngravedVRule(Modifier.fillMaxHeight())
            Spacer(Modifier.width(24.u))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                PhaseText(st)
                Spacer(Modifier.weight(1f))
                Timer(st)
                Spacer(Modifier.weight(1f))
                LiveReadouts(st)
            }
        }
        Spacer(Modifier.height(14.u))
        HardKey(
            onClick = { if (armed) Graph.perf.cancel() else Graph.perf.stage() },
            modifier = Modifier.fillMaxWidth().height(76.u),
            lit = armed,
        ) { ink -> KeyLegend(if (armed) "CANCEL" else "STAGE", if (armed) "中止" else "計測待機", ink, enSize = 26, jpSize = 16) }
    }
}

@Composable
private fun PhaseText(st: PerfState) {
    val (title, color) = when (st.phase) {
        PerfPhase.IDLE -> "Standby" to Pal.Cream2
        PerfPhase.STAGING -> "Staging" to LocalIllum.current.ink
        PerfPhase.READY -> "Ready" to Pal.Ok
        PerfPhase.RUNNING -> "Running" to Pal.Cream
        PerfPhase.FINISHED -> "Done" to Pal.Cream
        PerfPhase.ABORTED -> "Aborted" to Pal.Warn
    }
    val hint = when (st.phase) {
        PerfPhase.IDLE -> "Press STAGE. The timer arms the next time you come to a stop."
        PerfPhase.STAGING -> when {
            !st.haveFix -> "Waiting for a GPS fix…"
            st.speedMps >= PerfEngine.STILL -> "Come to a full stop."
            else -> "Hold still…"
        }
        PerfPhase.READY -> "Launch when it's clear. Timing starts the moment you move."
        PerfPhase.RUNNING -> "Timing to ${targetText(st.mode, st.metric)}. Lifting cancels the run."
        PerfPhase.FINISHED -> st.result?.let { headline(it) } ?: ""
        PerfPhase.ABORTED -> "${st.note ?: "Run thrown out"}. Stop again to restage."
    }
    Txt(title, 34, bold = true, color = color)
    Spacer(Modifier.height(8.u))
    Txt(hint, 20, color = Pal.Cream2, maxLines = 2, lineHeight = 1.3f)
}

@Composable
private fun Timer(st: PerfState) {
    val nowNs by produceState(SystemClock.elapsedRealtimeNanos(), st.phase, st.t0Ns) {
        if (st.phase == PerfPhase.RUNNING) {
            while (true) {
                withFrameNanos { }
                value = SystemClock.elapsedRealtimeNanos()
            }
        }
    }
    val sec = when (st.phase) {
        PerfPhase.RUNNING -> max(0.0, (nowNs - st.t0Ns) / 1e9)
        PerfPhase.FINISHED -> (st.result?.ms ?: 0L) / 1000.0
        else -> 0.0
    }
    Row(verticalAlignment = Alignment.Bottom) {
        SegText(String.format(Locale.US, "%5.2f", sec.coerceAtMost(99.99)), 124, italic = true)
        Spacer(Modifier.width(12.u))
        Txt("s", 28, color = Pal.Cream2)
    }
}

@Composable
private fun LiveReadouts(st: PerfState) {
    val metric = st.metric
    val target = PerfEngine.target(st.mode, metric)
    val done = st.result.takeIf { st.phase == PerfPhase.FINISHED }
    val mps = done?.let { it.trapMps ?: target.speedMps } ?: st.speedMps
    val meters = done?.distanceM ?: st.distanceM
    val speed = speedIn(mps, metric)
    val dist = if (metric) meters else meters * 3.28084
    val fraction = when (st.phase) {
        PerfPhase.RUNNING -> target.speedMps?.let { st.speedMps / it } ?: (st.distanceM / target.distanceM!!).toFloat()
        PerfPhase.FINISHED -> 1f
        else -> 0f
    }
    Row(verticalAlignment = Alignment.Bottom) {
        GhostSegText(String.format(Locale.US, "%3d", rollingSpeed(speed).coerceIn(0, 999)), 44)
        Spacer(Modifier.width(8.u))
        Txt(if (metric) "km/h" else "mph", 20, color = Pal.Cream2)
        Spacer(Modifier.width(40.u))
        GhostSegText(String.format(Locale.US, "%4d", dist.roundToInt().coerceIn(0, 9999)), 44)
        Spacer(Modifier.width(8.u))
        Txt(if (metric) "m" else "ft", 20, color = Pal.Cream2)
    }
    Spacer(Modifier.height(12.u))
    SegmentProgress(fraction.coerceIn(0f, 1f), Modifier.fillMaxWidth().height(12.u))
}

@Composable
private fun Tree(phase: PerfPhase, modifier: Modifier) {
    val still = LocalReduceMotion.current
    val waiting = phase == PerfPhase.STAGING || phase == PerfPhase.ABORTED
    val blink by produceState(true, waiting, still) {
        value = true
        if (waiting && !still) {
            while (true) {
                delay(550)
                value = !value
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
        Bulb(StageAmber, phase == PerfPhase.READY || (waiting && blink), "STAGE")
        Bulb(Color(0xFFB2D68C), phase == PerfPhase.READY || phase == PerfPhase.RUNNING, "GO")
        Bulb(Pal.Warn, phase == PerfPhase.ABORTED, "FOUL")
    }
}

@Composable
private fun Bulb(color: Color, on: Boolean, label: String) {
    val k = pxPerU()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(58.u)) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(Pal.Ink, r)
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF45413B), Color(0xFF141210))), r * 0.93f)
            val lens = r * 0.76f
            val hot = c + Offset(-lens * 0.18f, -lens * 0.22f)
            if (on) {
                drawCircle(color.copy(alpha = 0.30f), r * 0.90f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(lerp(color, Color.White, 0.6f), color, lerp(color, Color.Black, 0.35f)),
                        center = hot, radius = lens * 1.25f,
                    ),
                    lens,
                )
            } else {
                drawCircle(
                    Brush.radialGradient(
                        listOf(lerp(color, Color.Black, 0.70f), lerp(color, Color.Black, 0.88f)),
                        center = hot, radius = lens * 1.2f,
                    ),
                    lens,
                )
            }
            for (i in 1..3) {
                drawCircle(Color.White.copy(alpha = if (on) 0.12f else 0.05f), lens * i / 4f, style = Stroke(width = k))
            }
            drawOval(
                Color.White.copy(alpha = if (on) 0.5f else 0.14f),
                topLeft = c + Offset(-lens * 0.55f, -lens * 0.72f),
                size = Size(lens * 0.62f, lens * 0.30f),
            )
        }
        Spacer(Modifier.height(6.u))
        Txt(label, 14, color = if (on) lerp(color, Pal.Cream, 0.35f) else Pal.Cream3, bold = true, tracking = 0.1f)
    }
}

@Composable
private fun TimeslipPanel(r: PerfResult?, bests: Map<String, PerfResult>, modifier: Modifier) {
    val s = LocalSettings.current
    GlassPanel(
        modifier,
        caption = "Timeslip",
        trailing = { r?.let { Txt("${Fmt.dateShort(it.at)} · ${Fmt.time(it.at, s.clock24)}", 16, color = Pal.Cream3) } },
    ) {
        if (r == null) {
            Spacer(Modifier.weight(1f))
            Txt("No runs yet", 26, bold = true)
            Spacer(Modifier.height(10.u))
            Txt(
                "Pick a run, press STAGE, come to a stop and launch. Splits, trap speed and your best times " +
                    "land here. For drag strips and closed courses.",
                18, color = Pal.Cream2, maxLines = 4, lineHeight = 1.35f,
            )
            Spacer(Modifier.weight(1f))
            return@GlassPanel
        }
        val best = bests[PerfTimer.key(r.mode, r.metric)]
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(runName(r.mode, r.metric), 21, bold = true)
            Spacer(Modifier.weight(1f))
            when {
                best != null && best.at == r.at -> {
                    Led(true)
                    Spacer(Modifier.width(8.u))
                    Txt("New best", 17, color = LocalIllum.current.ink, bold = true)
                }
                best != null && r.ms > best.ms ->
                    Txt(String.format(Locale.US, "+%.2f s off best", (r.ms - best.ms) / 1000.0), 16, color = Pal.Cream3)
            }
        }
        Spacer(Modifier.height(6.u))
        Column(Modifier.weight(1f)) {
            r.splits.forEachIndexed { i, sp ->
                val finish = i == r.splits.lastIndex
                Row(Modifier.fillMaxWidth().height(29.u), verticalAlignment = Alignment.CenterVertically) {
                    Txt(sp.label, 18, color = if (finish) Pal.Cream else Pal.Cream2, bold = finish)
                    Spacer(Modifier.weight(1f))
                    sp.speedMps?.let {
                        Txt("@ " + PerfEngine.speedText(it, r.metric), 16, color = Pal.Cream3)
                        Spacer(Modifier.width(12.u))
                    }
                    SegText(String.format(Locale.US, "%5.2f", sp.ms / 1000.0), if (finish) 26 else 22, color = if (finish) Pal.Cream else Pal.Cream2)
                }
            }
        }
        Spacer(Modifier.height(6.u))
        val elev = r.altDeltaM?.let {
            val ft = if (r.metric) "${it.roundToInt()} m" else "${(it * 3.28084).roundToInt()} ft"
            " · elevation ${if (it >= 0) "+" else ""}$ft"
        } ?: ""
        Txt("Timed at ${r.hz.roundToInt().coerceAtLeast(1)} Hz GPS$elev", 15, color = Pal.Cream3)
    }
}

@Composable
private fun BestPanel(bests: Map<String, PerfResult>, metric: Boolean, topMps: Float, topAt: Long, modifier: Modifier) {
    GlassPanel(
        modifier,
        caption = "Best",
        trailing = {
            HardKey(
                onClick = {},
                onLongClick = {
                    Graph.perf.resetBests(metric)
                    Graph.trips.resetTopSpeed()
                },
                modifier = Modifier.size(120.u, 40.u),
            ) { ink ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("RESET", 14, color = ink, bold = true, tracking = 0.06f)
                    Spacer(Modifier.width(6.u))
                    Txt("長押し", 12, color = ink.copy(alpha = 0.7f))
                }
            }
        },
    ) {
        PerfMode.entries.forEach { m ->
            val b = bests[PerfTimer.key(m, metric)]
            BestRow(
                runName(m, metric),
                note = b?.let { r -> Fmt.dateShort(r.at) + (r.trapMps?.let { " · @ " + PerfEngine.speedText(it, metric) } ?: "") },
                value = b?.let { String.format(Locale.US, "%5.2f", it.ms / 1000.0) },
                unit = "s",
            )
            EngravedRule(Modifier.fillMaxWidth())
        }
        BestRow(
            "Top speed",
            note = if (topAt > 0L) Fmt.dateShort(topAt) else null,
            value = if (topMps > 0f) String.format(Locale.US, "%3d", speedIn(topMps, metric).roundToInt().coerceIn(0, 999)) else null,
            unit = if (metric) "km/h" else "mph",
        )
    }
}

@Composable
private fun ColumnScope.BestRow(label: String, note: String?, value: String?, unit: String) {
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 19)
        Spacer(Modifier.weight(1f))
        if (note != null) {
            Txt(note, 14, color = Pal.Cream3)
            Spacer(Modifier.width(14.u))
        }
        if (value != null) SegText(value, 24) else Txt("—", 22, color = Pal.Cream3)
        Spacer(Modifier.width(7.u))
        Box(Modifier.width(40.u)) {
            if (value != null) Txt(unit, 14, color = Pal.Cream2)
        }
    }
}

private fun speedIn(mps: Float, metric: Boolean): Float = if (metric) mps * 3.6f else mps / PerfEngine.MPH

fun runName(mode: PerfMode, metric: Boolean): String = when (mode) {
    PerfMode.SHORT, PerfMode.LONG -> PerfEngine.label(mode, metric) + if (metric) " km/h" else " mph"
    PerfMode.DRAG -> PerfEngine.label(mode, metric)
}

private fun targetText(mode: PerfMode, metric: Boolean): String = when (mode) {
    PerfMode.SHORT -> if (metric) "100 km/h" else "60 mph"
    PerfMode.LONG -> if (metric) "160 km/h" else "100 mph"
    PerfMode.DRAG -> if (metric) "400 m" else "¼ mile"
}

private fun headline(r: PerfResult): String {
    val t = String.format(Locale.US, "%.2f s", r.ms / 1000.0)
    return when (r.mode) {
        PerfMode.DRAG -> "${runName(r.mode, r.metric)} in $t @ ${PerfEngine.speedText(r.trapMps ?: 0f, r.metric)}."
        else -> "${runName(r.mode, r.metric)} in $t, ${if (r.metric) "${r.distanceM.roundToInt()} m" else "${(r.distanceM * 3.28084).roundToInt()} ft"} used."
    }
}
