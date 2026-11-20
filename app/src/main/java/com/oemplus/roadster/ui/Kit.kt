package com.oemplus.roadster.ui

import android.view.SoundEffectConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    pad: Float = 20f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = LocalU.current
    Column(
        modifier
            .drawWithCache {
                val d = density * k
                val one = max(1f, d)
                val r = CornerRadius(7f * d)
                val rIn = CornerRadius(max(0f, 7f * d - one))
                val body = Brush.verticalGradient(
                    0f to Pal.GlassTop, 0.3f to Pal.Glass, 1f to Pal.GlassBottom,
                )
                val shadowH = 20f * d
                val inner = Brush.verticalGradient(
                    listOf(Pal.PanelShade, Color.Transparent),
                    startY = one, endY = one + shadowH,
                )
                val innerSize = Size(size.width - 2 * one, size.height - 2 * one)
                onDrawBehind {
                    drawRoundRect(Pal.PanelLip, topLeft = Offset(0f, one), size = size, cornerRadius = r)
                    drawRoundRect(Pal.PanelRim, size = size, cornerRadius = r)
                    drawRoundRect(body, topLeft = Offset(one, one), size = innerSize, cornerRadius = rIn)
                    drawRoundRect(
                        inner, topLeft = Offset(one, one),
                        size = Size(innerSize.width, min(shadowH, innerSize.height)), cornerRadius = rIn,
                    )
                }
            }
            .padding(horizontal = pad.u, vertical = (pad * 0.75f).u),
    ) {
        if (caption != null) {
            CaptionRow(caption, trailing)
            Spacer(Modifier.height(10.u))
        }
        content()
    }
}

@Composable
fun CaptionRow(caption: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(36.u), verticalAlignment = Alignment.CenterVertically) {
        Txt(caption, 21, color = Pal.Cream.copy(alpha = 0.80f), bold = true)
        Spacer(Modifier.width(14.u))
        EngravedRule(Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(14.u))
            trailing()
        }
    }
}

@Composable
fun EngravedRule(modifier: Modifier = Modifier) {
    Canvas(modifier.height(2.u)) {
        val h = max(1f, size.height / 2f)
        drawRect(Pal.palette.groove, size = Size(size.width, h))
        drawRect(Pal.palette.grooveLight, topLeft = Offset(0f, h), size = Size(size.width, h))
    }
}

@Composable
fun EngravedVRule(modifier: Modifier = Modifier) {
    Canvas(modifier.width(2.u)) {
        val w = max(1f, size.width / 2f)
        drawRect(Pal.palette.groove, size = Size(w, size.height))
        drawRect(Pal.palette.grooveLight, topLeft = Offset(w, 0f), size = Size(w, size.height))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HardKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
    led: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    radius: Float = 6f,
    content: @Composable BoxScope.(ink: Color) -> Unit,
) {
    val il = LocalIllum.current
    val k = LocalU.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    val ink = when {
        !enabled -> Pal.Cream4
        lit -> il.text
        else -> Pal.Cream
    }
    Box(
        modifier
            .drawWithCache {
                val pal = Pal.palette
                val d = density * k
                val one = max(1f, d)
                val r = CornerRadius(radius * d)
                val rIn = CornerRadius(max(0f, radius * d - one))
                val o = Offset(one, one)
                val inner = Size(size.width - 2 * one, size.height - 2 * one)
                val body = when {
                    lit && pressed -> Brush.verticalGradient(listOf(il.keyBottom, il.keyTop))
                    lit -> Brush.verticalGradient(listOf(il.keyTop, il.keyBottom))
                    pressed -> Brush.verticalGradient(listOf(pal.keyPressTop, pal.keyPressBottom))
                    primary -> Brush.verticalGradient(listOf(pal.keyPrimaryTop, pal.keyPrimaryMid, pal.keyPrimaryBottom))
                    else -> Brush.verticalGradient(listOf(pal.keyTop, pal.keyMid, pal.keyBottom))
                }
                val bevel = Brush.verticalGradient(
                    0f to (if (lit) il.edge.copy(alpha = 0.75f) else pal.keyBevel),
                    0.5f to Color.Transparent,
                    endY = size.height,
                )
                val glow = Brush.radialGradient(
                    listOf(il.glow, Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.62f),
                    radius = max(size.width, size.height) * 0.6f,
                )
                val pressShade = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = if (pal.dark) 0.6f else 0.2f), Color.Transparent), startY = one, endY = one + 12 * d,
                )
                onDrawBehind {
                    if (!pressed) {
                        drawRoundRect(pal.keyShadow, topLeft = Offset(0f, 2 * one), size = size, cornerRadius = r)
                    }
                    drawRoundRect(pal.keyRim, size = size, cornerRadius = r)
                    drawRoundRect(body, topLeft = o, size = inner, cornerRadius = rIn)
                    if (lit) drawRoundRect(glow, topLeft = o, size = inner, cornerRadius = rIn)
                    if (pressed) {
                        drawRoundRect(pressShade, topLeft = o, size = inner, cornerRadius = rIn)
                    } else {
                        drawRoundRect(bevel, topLeft = o, size = inner, cornerRadius = rIn, style = Stroke(one))
                    }
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick?.let { lc ->
                    {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        lc()
                    }
                },
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                },
            )
            .padding(top = if (pressed) (2f * k).dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        content(ink)
        if (led != null) Led(led, Modifier.align(Alignment.TopEnd).padding(top = 9.u, end = 10.u))
    }
}

@Composable
fun KeyLegend(en: String, jp: String?, ink: Color, enSize: Int = 25, jpSize: Int = 16) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(en, enSize, color = ink, bold = true, tracking = 0.06f)
        if (jp != null) {
            Spacer(Modifier.height(5.u))
            Txt(jp, jpSize, color = ink.copy(alpha = 0.78f), tracking = 0.08f)
        }
    }
}

@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier) {
    val il = LocalIllum.current
    Canvas(modifier.size(18.u, 6.u)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Pal.Ink, cornerRadius = r)
        val i = max(1f, size.height * 0.18f)
        val inner = Size(size.width - 2 * i, size.height - 2 * i)
        if (on) {
            drawRoundRect(il.led, topLeft = Offset(i, i), size = inner, cornerRadius = r)
            drawRoundRect(
                Color.White.copy(alpha = 0.45f), topLeft = Offset(size.width * 0.25f, i),
                size = Size(size.width * 0.5f, inner.height * 0.4f), cornerRadius = r,
            )
        } else {
            drawRoundRect(Pal.palette.ledOff, topLeft = Offset(i, i), size = inner, cornerRadius = r)
        }
    }
}

@Composable
fun Lamp(ok: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.u)) {
        drawCircle(Pal.Ink)
        drawCircle(if (ok) Pal.Ok else Pal.Warn, radius = size.minDimension * 0.36f)
    }
}

@Composable
fun Glyph(icon: ImageVector, size: Float, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        modifier = modifier.size(size.u),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
fun Glyph(icon: ImageVector, size: Int, tint: Color, modifier: Modifier = Modifier) =
    Glyph(icon, size.toFloat(), tint, modifier)

@Composable
fun SegmentProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Pal.Meter,
    onSeek: ((Float) -> Unit)? = null,
) {
    val k = pxPerU()
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val input = if (onSeek != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { p -> seek?.invoke((p.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> drag = (p.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { f -> seek?.invoke(f) }; drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    } else Modifier
    Canvas(modifier.then(input)) {
        val f = (drag ?: fraction).coerceIn(0f, 1f)
        val gap = 4f * k
        val n = ((size.width + gap) / (24f * k + gap)).toInt().coerceAtLeast(4)
        val w = (size.width - gap * (n - 1)) / n
        val ghost = color.copy(alpha = 0.10f)
        for (i in 0 until n) {
            val x = i * (w + gap)
            val a = ((f * n) - i).coerceIn(0f, 1f)
            drawRect(ghost, Offset(x, 0f), Size(w, size.height))
            if (a > 0f) drawRect(color.copy(alpha = if (a >= 1f) 0.95f else 0.25f + 0.7f * a), Offset(x, 0f), Size(w, size.height))
        }
        if (drag != null) {
            val x = f * size.width
            drawRect(Pal.Cream, Offset(x - k, -3f * k), Size(2f * k, size.height + 6f * k))
        }
    }
}

@Composable
fun ToggleKey(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    HardKey(onClick = { onChange(!on) }, modifier = modifier.size(136.u, 54.u), enabled = enabled) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(on)
            Spacer(Modifier.width(12.u))
            Txt(if (on) "ON" else "OFF", 20, color = ink, bold = true, tracking = 0.08f)
        }
    }
}

@Composable
fun <T> ChoiceKeys(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    keyWidth: Float = 122f,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.u)) {
        options.forEach { o ->
            HardKey(onClick = { onSelect(o) }, modifier = Modifier.size(keyWidth.u, 54.u), lit = o == selected) { ink ->
                Txt(label(o), 19, color = ink, bold = true)
            }
        }
    }
}

@Composable
fun TextKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, lit: Boolean = false, width: Float = 190f, enabled: Boolean = true) {
    HardKey(onClick = onClick, modifier = modifier.size(width.u, 54.u), lit = lit, enabled = enabled) { ink ->
        Txt(label, 19, color = ink, bold = true)
    }
}

@Composable
fun SheetFrame(
    title: String,
    onDismiss: () -> Unit,
    width: Float = 860f,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = LocalU.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(width.u)
                .heightIn(max = 620.u)
                .pointerInput(Unit) { detectTapGestures { } }
                .drawWithCache {
                    val d = density * k
                    val r = CornerRadius(10f * d)
                    val body = Brush.verticalGradient(listOf(Pal.palette.sheetTop, Pal.palette.sheetBottom))
                    onDrawBehind {
                        drawRoundRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(0f, 6f * d), size = size, cornerRadius = r)
                        drawRoundRect(Pal.palette.keyRim, size = size, cornerRadius = r)
                        drawRoundRect(body, topLeft = Offset(d, d), size = Size(size.width - 2 * d, size.height - 2 * d), cornerRadius = r)
                        drawRoundRect(
                            Brush.verticalGradient(0f to Pal.palette.keyBevel, 0.3f to Color.Transparent),
                            topLeft = Offset(d, d), size = Size(size.width - 2 * d, size.height - 2 * d), cornerRadius = r, style = Stroke(d),
                        )
                    }
                }
                .padding(12.u),
        ) {
            GlassPanel(
                Modifier.fillMaxWidth(),
                caption = title,
                trailing = {
                    trailing?.invoke(this)
                    HardKey(onClick = onDismiss, modifier = Modifier.size(118.u, 46.u)) { ink ->
                        Txt("CLOSE", 18, color = ink, bold = true, tracking = 0.06f)
                    }
                },
                content = content,
            )
        }
    }
}
