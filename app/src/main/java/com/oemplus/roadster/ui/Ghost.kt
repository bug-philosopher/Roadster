package com.oemplus.roadster.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import com.oemplus.roadster.core.Fmt
import com.oemplus.roadster.core.SpeedUnit
import java.util.Locale
import kotlin.math.roundToInt

private const val FADE_IN_MS = 140f

@Composable
fun GhostSegText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = Pal.Cream,
    italic: Boolean = false,
    persistMs: Float = 650f,
) {
    if (LocalReduceMotion.current) {
        SegText(text, size, modifier, color, italic = italic)
        return
    }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val style = TextStyle(
        fontFamily = if (italic) Fonts.SegItalic else Fonts.Seg,
        fontWeight = FontWeight.Bold,
        fontSize = size.us,
        lineHeight = (size * 1.08f).us,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    val shown = text.replace(' ', '!')
    val unlit = buildString { shown.forEach { c -> append(if (c.isDigit() || c == '-' || c == '!') '8' else c) } }
    val box = remember(unlit, style) { measurer.measure(unlit, style) }
    val trail = remember { GhostTrail() }
    val tick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(shown) {
        trail.push(shown, System.nanoTime())
        while (trail.fading(System.nanoTime(), persistMs)) {
            withFrameNanos { }
            tick.longValue = System.nanoTime()
        }
        tick.longValue = System.nanoTime()
    }

    val d = LocalDensity.current
    Canvas(modifier.size(with(d) { box.size.width.toDp() }, with(d) { box.size.height.toDp() })) {
        tick.longValue
        val now = System.nanoTime()
        drawText(box, color = color.copy(alpha = 0.09f))
        trail.layers(now, persistMs) { s, a ->
            drawText(measurer.measure(s, style), color = color.copy(alpha = color.alpha * a))
        }
    }
}

private class GhostTrail {
    private val items = ArrayList<Pair<String, Long>>()

    fun push(s: String, t: Long) {
        if (items.lastOrNull()?.first == s) return
        items += s to t
        while (items.size > 6) items.removeAt(0)
    }

    fun fading(now: Long, persistMs: Float): Boolean {
        prune(now, persistMs)
        val cur = items.lastOrNull() ?: return false
        return items.size > 1 || (now - cur.second) / 1e6f < FADE_IN_MS
    }

    inline fun layers(now: Long, persistMs: Float, draw: (String, Float) -> Unit) {
        for (i in 0 until items.size - 1) {
            val age = (now - items[i + 1].second) / 1e6f
            if (age >= persistMs) continue
            val f = 1f - age / persistMs
            draw(items[i].first, 0.6f * f * f)
        }
        val cur = items.lastOrNull() ?: return
        val age = (now - cur.second) / 1e6f
        draw(cur.first, (0.35f + 0.65f * age / FADE_IN_MS).coerceAtMost(1f))
    }

    private fun prune(now: Long, persistMs: Float) {
        while (items.size > 1 && (now - items[1].second) / 1e6f >= persistMs) items.removeAt(0)
    }
}

@Composable
fun SpeedReadout(mps: Float?, unit: SpeedUnit, size: Int, modifier: Modifier = Modifier, italic: Boolean = false) {
    if (mps == null) {
        GhostSegText("---", size, modifier, italic = italic)
        return
    }
    GhostSegText(String.format(Locale.US, "%3d", rollingSpeed(Fmt.speed(mps, unit).toFloat()).coerceIn(0, 999)), size, modifier, italic = italic)
}

@Composable
fun rollingSpeed(target: Float): Int {
    val still = LocalReduceMotion.current
    val v by animateFloatAsState(target, tween(650, easing = LinearOutSlowInEasing), label = "speed")
    return (if (still) target else v).roundToInt()
}
