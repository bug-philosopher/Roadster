package com.oemplus.roadster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.LifecycleStartEffect
import com.oemplus.roadster.Graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.oemplus.roadster.media.Analyzer
import com.oemplus.roadster.media.AnalyzerStatus
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class SpectrumAnim(val bands: Int) {
    val level = FloatArray(bands)
    val peak = FloatArray(bands)
    private val hold = FloatArray(bands)
    private val raw = FloatArray(Analyzer.BANDS)
    val wave = FloatArray(Analyzer.WAVE)
    var timeSec = 0.0
        private set

    fun idle(): Boolean {
        for (b in 0 until bands) if (level[b] > 0.002f || peak[b] > 0.002f) return false
        return true
    }

    fun step(dt: Float, analyzer: Analyzer) {
        timeSec += dt
        analyzer.read(raw, wave)
        for (b in 0 until bands) {
            val i0 = b * Analyzer.BANDS / bands
            val i1 = max(i0 + 1, (b + 1) * Analyzer.BANDS / bands)
            var t = 0f
            for (i in i0 until i1) t = max(t, raw[i])
            val cur = level[b]
            level[b] = if (t > cur) cur + (t - cur) * min(1f, dt * 30f) else max(t, cur - dt * 1.6f)
            if (level[b] >= peak[b]) {
                peak[b] = level[b]
                hold[b] = 0.6f
            } else {
                hold[b] -= dt
                if (hold[b] <= 0f) peak[b] = max(level[b], peak[b] - dt * 0.6f)
            }
        }
    }
}

@Composable
fun rememberSpectrum(bands: Int, ambientFps: Int = 0): Pair<SpectrumAnim, LongState> {
    val analyzer = Graph.analyzer
    val anim = remember(bands) { SpectrumAnim(bands) }
    val frame = remember { mutableLongStateOf(0L) }
    LifecycleStartEffect(analyzer) {
        analyzer.acquire()
        onStopOrDispose { analyzer.release() }
    }
    LaunchedEffect(anim, ambientFps) {
        var last = 0L
        while (true) {
            val busy = Graph.media.current.value?.playing == true || Graph.analyzer.audible.value
            if (!busy && anim.idle()) {
                if (ambientFps > 0) {
                    delay(1000L / ambientFps)
                } else {
                    combine(Graph.media.current, Graph.analyzer.audible) { c, heard -> c?.playing == true || heard }
                        .first { it }
                    last = 0L
                }
            }
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
                last = t
                anim.step(dt, analyzer)
                frame.longValue = t
            }
        }
    }
    return anim to frame
}

@Composable
fun SpectrumMeter(
    anim: SpectrumAnim,
    frame: LongState,
    rows: Int,
    modifier: Modifier = Modifier,
    color: Color = Pal.Meter,
    gapX: Float = 4f,
    gapY: Float = 3f,
) {
    val k = pxPerU()
    Canvas(modifier) {
        frame.longValue
        val n = anim.bands
        val gx = gapX * k
        val gy = gapY * k
        val bw = (size.width - gx * (n - 1)) / n
        val rh = (size.height - gy * (rows - 1)) / rows
        val ghost = color.copy(alpha = 0.075f)
        val cap = lerp(color, Color.White, 0.4f)
        for (b in 0 until n) {
            val x = b * (bw + gx)
            val lit = ceil(anim.level[b] * rows - 0.2f).toInt()
            val pk = if (anim.peak[b] > 0.04f) min(rows - 1, (anim.peak[b] * rows).toInt()) else -1
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * rh - r * gy
                val c = when {
                    r < lit -> color
                    r == pk -> cap
                    else -> ghost
                }
                drawRect(c, Offset(x, y), Size(bw, rh))
            }
        }
    }
}

@Composable
fun FreqScale(bands: Int, modifier: Modifier = Modifier, gapX: Float = 4f) {
    val k = pxPerU()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Fonts.UD, fontSize = 15.us, color = Pal.Cream3)
    val marks = listOf(63f to "63", 125f to "125", 250f to "250", 500f to "500", 1000f to "1k", 2000f to "2k", 4000f to "4k", 8000f to "8k", 16000f to "16k")
    val layouts = remember(style) { marks.map { measurer.measure(it.second, style) } }
    Canvas(modifier) {
        val gx = gapX * k
        val pitch = (size.width + gx) / bands
        val span = ln(Analyzer.HIGH_HZ / Analyzer.LOW_HZ)
        marks.forEachIndexed { idx, (hz, _) ->
            val cx = (ln(hz / Analyzer.LOW_HZ) / span) * bands * pitch - gx / 2f
            val l = layouts[idx]
            val x = (cx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width)
            drawText(l, topLeft = Offset(x, 0f))
        }
    }
}

@Composable
fun ScopeView(anim: SpectrumAnim, frame: LongState, modifier: Modifier = Modifier, color: Color = Pal.Meter) {
    val k = pxPerU()
    val history = remember { Array(5) { FloatArray(Analyzer.WAVE) } }
    val head = remember { intArrayOf(0) }
    val path = remember { Path() }
    Canvas(modifier.clipToBounds()) {
        frame.longValue
        anim.wave.copyInto(history[head[0]])
        head[0] = (head[0] + 1) % history.size

        val cols = 10
        val rows = 8
        for (i in 0..cols) {
            val x = size.width * i / cols
            drawLine(Pal.Cream.copy(alpha = if (i == cols / 2) 0.10f else 0.045f), Offset(x, 0f), Offset(x, size.height), strokeWidth = k)
        }
        for (j in 0..rows) {
            val y = size.height * j / rows
            drawLine(Pal.Cream.copy(alpha = if (j == rows / 2) 0.10f else 0.045f), Offset(0f, y), Offset(size.width, y), strokeWidth = k)
        }
        val mid = size.height / 2f
        val amp = size.height * 0.42f
        val n = Analyzer.WAVE
        for (age in history.indices.reversed()) {
            val w = history[(head[0] - 1 - age + history.size * 2) % history.size]
            path.reset()
            for (i in 0 until n) {
                val x = size.width * i / (n - 1)
                val y = mid - w[i] * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (age == 0) {
                drawPath(path, color.copy(alpha = 0.16f), style = Stroke(width = 8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, color, style = Stroke(width = 2.2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                drawPath(path, color.copy(alpha = 0.22f / (age + 1)), style = Stroke(width = 2f * k))
            }
        }
    }
}

@Composable
fun CompassTape(heading: Float?, modifier: Modifier = Modifier) {
    val il = LocalIllum.current
    val k = pxPerU()
    val measurer = rememberTextMeasurer()
    val big = TextStyle(fontFamily = Fonts.UD, fontWeight = FontWeight.Bold, fontSize = 21.us, color = Pal.Cream)
    val small = TextStyle(fontFamily = Fonts.UD, fontWeight = FontWeight.Bold, fontSize = 15.us, color = Pal.Cream2)
    val labels = remember(big, small) {
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").mapIndexed { i, s ->
            measurer.measure(s, if (i % 2 == 0) big else small)
        }
    }
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val span = 140f
        val ppd = size.width / span
        val hd = heading ?: 0f
        val cx = size.width / 2f
        val alpha = if (heading == null) 0.25f else 1f
        val base = size.height - 4f * k
        var d = floor((hd - span / 2f) / 5f) * 5f
        while (d <= hd + span / 2f + 5f) {
            val x = cx + (d - hd) * ppd
            val dn = ((d % 360f) + 360f) % 360f
            val major = dn % 45f == 0f
            val mid = dn % 15f == 0f
            val th = (if (major) 18f else if (mid) 12f else 7f) * k
            drawLine(
                Pal.Cream.copy(alpha = alpha * if (major) 0.85f else if (mid) 0.5f else 0.28f),
                Offset(x, base - th), Offset(x, base), strokeWidth = (if (major) 2.2f else 1.4f) * k,
            )
            if (major) {
                val l = labels[(dn / 45f).roundToInt() % 8]
                drawText(l, topLeft = Offset(x - l.size.width / 2f, base - th - 6f * k - l.size.height), alpha = alpha)
            }
            d += 5f
        }
        drawLine(Color.Black.copy(alpha = 0.8f), Offset(0f, base + 1.5f * k), Offset(size.width, base + 1.5f * k), strokeWidth = 2f * k)
        if (heading != null) {
            val p = Path().apply {
                moveTo(cx - 8f * k, 0f); lineTo(cx + 8f * k, 0f); lineTo(cx, 10f * k); close()
            }
            drawPath(p, il.base)
            drawLine(il.base, Offset(cx, 10f * k), Offset(cx, base), strokeWidth = 2.4f * k)
        }
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.16f to Color.Black, 0.84f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

fun analyzerNote(status: AnalyzerStatus): String? = when (status) {
    AnalyzerStatus.SIMULATED -> "Simulated"
    AnalyzerStatus.NEEDS_PERMISSION -> "Simulated · microphone permission off"
    AnalyzerStatus.UNAVAILABLE -> "Simulated · unit won't share its audio output"
    else -> null
}
