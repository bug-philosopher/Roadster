package com.oemplus.roadster.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val CX = 0.43f
private const val CY = 0.55f
private const val REC = 0.40f
private const val LABEL = 0.36f
private const val PX = 0.865f
private const val PY = 0.20f
private const val ARM = 0.60f
private const val REST = 91f
private const val REST_LIFT = 0.25f
private const val DEG_PER_SEC = 200f

private fun stylusRadius(deg: Float): Float {
    val t = Math.toRadians(deg.toDouble())
    val x = PX + ARM * cos(t).toFloat() - CX
    val y = PY + ARM * sin(t).toFloat() - CY
    return sqrt(x * x + y * y)
}

private fun angleFor(r: Float): Float {
    var lo = 95f
    var hi = 135f
    repeat(28) {
        val mid = (lo + hi) / 2
        if (stylusRadius(mid) > r) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

private val OUTER = angleFor(REC * 0.93f)
private val INNER = angleFor(REC * 0.47f)

private fun grooveAngle(progress: Float) = OUTER + (INNER - OUTER) * progress.coerceIn(0f, 1f)

@Composable
fun Turntable(
    playing: Boolean,
    progress: () -> Float,
    title: String?,
    artist: String?,
    modifier: Modifier = Modifier,
) {
    val still = LocalReduceMotion.current
    val il = LocalIllum.current
    val brand = LocalSettings.current.brand.ifBlank { "ROADSTER" }
    val measurer = rememberTextMeasurer()
    val angle = remember { mutableFloatStateOf(0f) }
    val omega = remember { floatArrayOf(0f) }
    val arm = remember { Animatable(REST) }
    val lift = remember { Animatable(REST_LIFT) }
    val progressNow by rememberUpdatedState(progress)

    LaunchedEffect(playing, still) {
        if (still) {
            omega[0] = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (playing || omega[0] > 0f) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0f, 0.05f)
                last = t
                val target = if (playing) DEG_PER_SEC else 0f
                val tau = if (playing) 0.35f else 0.9f
                omega[0] += (target - omega[0]) * (1f - exp(-dt / tau))
                if (!playing && omega[0] < 3f) omega[0] = 0f
                angle.floatValue = (angle.floatValue + omega[0] * dt) % 360f
            }
        }
    }

    LaunchedEffect(playing, still) {
        if (playing) {
            val target = grooveAngle(progressNow())
            if (still) {
                arm.snapTo(target)
                lift.snapTo(0f)
            } else {
                if (lift.value < 0.95f) lift.animateTo(1f, tween(200))
                arm.animateTo(target, tween(820, easing = FastOutSlowInEasing))
                lift.animateTo(0f, tween(420, easing = LinearOutSlowInEasing))
            }
            while (true) {
                delay(500)
                val t = grooveAngle(progressNow())
                if (abs(t - arm.value) > 2.5f && !still) {
                    lift.animateTo(1f, tween(180))
                    arm.animateTo(t, tween(560, easing = FastOutSlowInEasing))
                    lift.animateTo(0f, tween(320, easing = LinearOutSlowInEasing))
                } else {
                    arm.snapTo(t)
                }
            }
        } else if (still) {
            arm.snapTo(REST)
            lift.snapTo(REST_LIFT)
        } else {
            lift.animateTo(1f, tween(240))
            arm.animateTo(REST, tween(900, easing = FastOutSlowInEasing))
            lift.animateTo(REST_LIFT, tween(320))
        }
    }

    Box(modifier.drawWithCache {
        val plinth = Brush.linearGradient(
            listOf(Pal.palette.deckTop, Pal.palette.deckBottom),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val s = size.minDimension
        val c = Offset(CX * s, CY * s)
        val well = Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            center = c + Offset(0.012f * s, 0.018f * s), radius = REC * s * 1.18f,
        )
        onDrawBehind {
            drawRect(plinth)
            drawCircle(well, REC * s * 1.18f, c + Offset(0.012f * s, 0.018f * s))
        }
    }) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = angle.floatValue
                    transformOrigin = TransformOrigin(CX, CY)
                }
                .drawWithCache {
                    val s = size.minDimension
                    val c = Offset(CX * s, CY * s)
                    val r = REC * s
                    val lr = LABEL * r
                    val hair = maxOf(0.6f, s / 420f)
                    val ink = Color(0xFF2A2621)
                    val titleStyle = TextStyle(fontFamily = Fonts.UD, fontWeight = FontWeight.Bold, fontSize = (lr * 0.25f).toSp(), color = ink)
                    val smallStyle = TextStyle(fontFamily = Fonts.UD, fontSize = (lr * 0.19f).toSp(), color = ink.copy(alpha = 0.8f))
                    val tinyStyle = TextStyle(fontFamily = Fonts.UD, fontWeight = FontWeight.Bold, fontSize = (lr * 0.15f).toSp(), color = ink.copy(alpha = 0.7f))
                    val maxW = Constraints(maxWidth = (lr * 1.45f).toInt().coerceAtLeast(1))
                    val line1 = measurer.measure(title ?: brand, titleStyle, TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = maxW)
                    val line2 = measurer.measure(artist ?: "SIDE A", smallStyle, TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = maxW)
                    val rpm = measurer.measure("33⅓", tinyStyle)
                    val platter = Brush.radialGradient(
                        0.90f to Color(0xFF2A2825), 0.97f to Color(0xFF6E6A63), 1f to Color(0xFF34312D),
                        center = c, radius = r * 1.04f,
                    )
                    val vinyl = Brush.radialGradient(listOf(Color(0xFF161616), Color(0xFF0C0C0C)), center = c, radius = r)
                    val paper = Brush.radialGradient(listOf(Color(0xFFEFE6D1), Color(0xFFD8CBAE)), center = c, radius = lr)
                    val spindle = Brush.radialGradient(listOf(Color(0xFFE8E4DC), Color(0xFF7D7973)), center = c - Offset(r * 0.01f, r * 0.01f), radius = r * 0.035f)
                    onDrawBehind {
                        drawCircle(platter, r * 1.04f, c)
                        for (i in 0 until 72) {
                            val a = Math.toRadians(i * 5.0)
                            drawCircle(
                                NightPalette.cream.copy(alpha = 0.28f), r * 0.009f,
                                c + Offset((cos(a) * r * 1.018f).toFloat(), (sin(a) * r * 1.018f).toFloat()),
                            )
                        }
                        drawCircle(vinyl, r, c)
                        var g = lr * 1.22f
                        var n = 0
                        while (g < r * 0.975f) {
                            drawCircle(Color.White.copy(alpha = if (n % 2 == 0) 0.035f else 0.018f), g, c, style = Stroke(hair))
                            g += r * 0.0145f
                            n++
                        }
                        for (band in floatArrayOf(0.56f, 0.69f, 0.81f)) {
                            drawCircle(Color.Black.copy(alpha = 0.55f), r * band, c, style = Stroke(r * 0.012f))
                        }
                        drawCircle(Color.Black.copy(alpha = 0.6f), r * 0.985f, c, style = Stroke(r * 0.02f))
                        drawCircle(Color.Black.copy(alpha = 0.5f), lr * 1.12f, c, style = Stroke(lr * 0.16f))
                        drawCircle(paper, lr, c)
                        drawCircle(il.base.copy(alpha = 0.85f), lr * 0.86f, c, style = Stroke(hair * 1.6f))
                        drawText(line1, topLeft = Offset(c.x - line1.size.width / 2f, c.y - lr * 0.52f))
                        drawText(line2, topLeft = Offset(c.x - line2.size.width / 2f, c.y + lr * 0.16f))
                        drawText(rpm, topLeft = Offset(c.x - rpm.size.width / 2f, c.y + lr * 0.50f))
                        drawCircle(Color(0xFF0B0A09), r * 0.05f, c)
                        drawCircle(spindle, r * 0.03f, c)
                    }
                },
        )
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val s = size.minDimension
                val c = Offset(CX * s, CY * s)
                val r = REC * s
                val lr = LABEL * r
                val sheen = Brush.sweepGradient(
                    0.00f to Color.Transparent, 0.07f to Color.Transparent, 0.125f to Color.White.copy(alpha = 0.075f),
                    0.18f to Color.Transparent, 0.57f to Color.Transparent, 0.625f to Color.White.copy(alpha = 0.11f),
                    0.68f to Color.Transparent, 1f to Color.Transparent,
                    center = c,
                )
                val ring = (r + lr * 1.05f) / 2f
                val w = r - lr * 1.05f
                onDrawBehind { drawCircle(sheen, ring, c, style = Stroke(w)) }
            },
        )
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawTonearm(arm.value, lift.value, il.base)
            },
        )
    }
}

private fun DrawScope.drawTonearm(deg: Float, lift: Float, accent: Color) {
    val s = size.minDimension
    val p = Offset(PX * s, PY * s)
    val len = ARM * s
    val tubeW = 0.021f * s
    val headLen = 0.11f * s
    val tubeEnd = len - 0.095f * s
    val restDir = Math.toRadians(REST.toDouble())
    val post = p + Offset((cos(restDir) * len * 0.62f).toFloat(), (sin(restDir) * len * 0.62f).toFloat())

    drawCircle(Color.Black.copy(alpha = 0.5f), 0.03f * s, post + Offset(0.006f * s, 0.008f * s))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF5A5650), Color(0xFF1F1D1A)), center = post, radius = 0.028f * s), 0.026f * s, post)

    fun arm(color: Color?, metal: Boolean) {
        rotate(deg, pivot = p) {
            scale(1f + 0.035f * lift, pivot = p) {
                val tube = if (metal) Brush.verticalGradient(
                    0f to Color(0xFF6F6B65), 0.35f to Color(0xFFEDE8DE), 0.6f to Color(0xFFB3ADA3), 1f to Color(0xFF5E5A54),
                    startY = p.y - tubeW / 2, endY = p.y + tubeW / 2,
                ) else null
                val cwTop = p.y - 0.038f * s
                if (color != null) {
                    drawRoundRect(color, Offset(p.x - 0.155f * s, cwTop), Size(0.088f * s, 0.076f * s), CornerRadius(0.012f * s))
                    drawRect(color, Offset(p.x - 0.067f * s, p.y - tubeW * 0.35f), Size(0.067f * s, tubeW * 0.7f))
                    drawRect(color, Offset(p.x, p.y - tubeW / 2), Size(tubeEnd, tubeW))
                } else {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFF55514B), Color(0xFF2A2724), Color(0xFF141210)), startY = cwTop, endY = cwTop + 0.076f * s),
                        Offset(p.x - 0.155f * s, cwTop), Size(0.088f * s, 0.076f * s), CornerRadius(0.012f * s),
                    )
                    for (i in 1..4) {
                        val x = p.x - 0.155f * s + i * 0.0176f * s
                        drawLine(Color.Black.copy(alpha = 0.45f), Offset(x, cwTop + 0.006f * s), Offset(x, cwTop + 0.070f * s), strokeWidth = s / 380f)
                    }
                    drawRect(Color(0xFF3B3833), Offset(p.x - 0.067f * s, p.y - tubeW * 0.35f), Size(0.067f * s, tubeW * 0.7f))
                    drawRect(tube!!, Offset(p.x, p.y - tubeW / 2), Size(tubeEnd, tubeW))
                }
                val hs = Offset(p.x + tubeEnd, p.y)
                rotate(21f, pivot = hs) {
                    val top = hs.y - 0.026f * s
                    if (color != null) {
                        drawRoundRect(color, Offset(hs.x - 0.01f * s, top), Size(headLen, 0.052f * s), CornerRadius(0.008f * s))
                    } else {
                        drawRoundRect(Color(0xFF1E1C1A), Offset(hs.x - 0.01f * s, top), Size(headLen, 0.052f * s), CornerRadius(0.008f * s))
                        drawRoundRect(Color(0xFF2E2B27), Offset(hs.x + 0.035f * s, top + 0.008f * s), Size(0.06f * s, 0.036f * s), CornerRadius(0.004f * s))
                        drawRect(accent.copy(alpha = 0.9f), Offset(hs.x + 0.035f * s, top + 0.008f * s), Size(0.012f * s, 0.036f * s))
                        drawLine(Color(0xFFD9D3C8), Offset(hs.x + 0.03f * s, top + 0.052f * s), Offset(hs.x + 0.055f * s, top + 0.082f * s), strokeWidth = 0.008f * s)
                    }
                }
            }
        }
    }

    translate(left = (0.010f + 0.022f * lift) * s, top = (0.014f + 0.030f * lift) * s) {
        arm(Color.Black.copy(alpha = 0.42f - 0.12f * lift), metal = false)
    }
    arm(null, metal = true)

    drawCircle(Color.Black.copy(alpha = 0.5f), 0.066f * s, p + Offset(0.008f * s, 0.012f * s))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF6A665F), Color(0xFF26231F)), center = p - Offset(0.015f * s, 0.015f * s), radius = 0.07f * s), 0.062f * s, p)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFE6E1D8), Color(0xFF8A857D)), center = p - Offset(0.008f * s, 0.008f * s), radius = 0.03f * s), 0.026f * s, p)
    drawCircle(lerp(Color(0xFF2A2724), Color.Black, 0.3f), 0.026f * s, p, style = Stroke(s / 300f))
}
