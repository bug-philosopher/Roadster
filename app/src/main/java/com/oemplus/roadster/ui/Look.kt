package com.oemplus.roadster.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.oemplus.roadster.R
import com.oemplus.roadster.core.Illumination
import com.oemplus.roadster.core.Settings

@Immutable
data class Palette(
    val dark: Boolean,
    val bezel: Color,
    val glassTop: Color,
    val glass: Color,
    val glassBottom: Color,
    val panelRim: Color,
    val panelLip: Color,
    val panelShade: Color,
    val cream: Color,
    val cream2: Color,
    val cream3: Color,
    val cream4: Color,
    val meter: Color,
    val ok: Color,
    val warn: Color,
    val ink: Color,
    val keyTop: Color,
    val keyMid: Color,
    val keyBottom: Color,
    val keyPrimaryTop: Color,
    val keyPrimaryMid: Color,
    val keyPrimaryBottom: Color,
    val keyPressTop: Color,
    val keyPressBottom: Color,
    val keyRim: Color,
    val keyBevel: Color,
    val keyShadow: Color,
    val ledOff: Color,
    val groove: Color,
    val grooveLight: Color,
    val sheetTop: Color,
    val sheetBottom: Color,
    val deckTop: Color,
    val deckBottom: Color,
    val window: Color,
    val dim: Float,
)

val NightPalette = Palette(
    dark = true,
    bezel = Color(0xFF1A1917),
    glassTop = Color(0xFF161513), glass = Color(0xFF0E0D0C), glassBottom = Color(0xFF0A0908),
    panelRim = Color.Black, panelLip = Color.White.copy(alpha = 0.07f), panelShade = Color.Black.copy(alpha = 0.65f),
    cream = Color(0xFFECE4D2), cream2 = Color(0xFFADA593), cream3 = Color(0xFF746D61), cream4 = Color(0xFF4A453E),
    meter = Color(0xFFB2D68C), ok = Color(0xFFB2D68C), warn = Color(0xFFE2603F), ink = Color(0xFF060505),
    keyTop = Color(0xFF38352F), keyMid = Color(0xFF2A2825), keyBottom = Color(0xFF201E1B),
    keyPrimaryTop = Color(0xFF47433D), keyPrimaryMid = Color(0xFF35322D), keyPrimaryBottom = Color(0xFF2A2724),
    keyPressTop = Color(0xFF171614), keyPressBottom = Color(0xFF211F1C),
    keyRim = Color(0xFF060505), keyBevel = Color.White.copy(alpha = 0.16f), keyShadow = Color.Black.copy(alpha = 0.55f),
    ledOff = Color(0xFF3B3732), groove = Color.Black.copy(alpha = 0.9f), grooveLight = Color.White.copy(alpha = 0.075f),
    sheetTop = Color(0xFF2E2B27), sheetBottom = Color(0xFF1E1C19),
    deckTop = Color(0xFF1D1B18), deckBottom = Color(0xFF0E0D0C),
    window = Color(0xFFF3D39A),
    dim = 0.30f,
)

val DayPalette = Palette(
    dark = false,
    bezel = Color(0xFFC6C1B7),
    glassTop = Color(0xFFFAF6EC), glass = Color(0xFFF1EBDB), glassBottom = Color(0xFFE6DFCC),
    panelRim = Color(0xFF7A746A), panelLip = Color.White.copy(alpha = 0.8f), panelShade = Color.Black.copy(alpha = 0.16f),
    cream = Color(0xFF151310), cream2 = Color(0xFF3D3933), cream3 = Color(0xFF5F584E), cream4 = Color(0xFFA39C90),
    meter = Color(0xFF24211D), ok = Color(0xFF357A26), warn = Color(0xFFB2361C), ink = Color(0xFF3A3631),
    keyTop = Color(0xFFFCFAF5), keyMid = Color(0xFFEAE5DB), keyBottom = Color(0xFFD1CBBF),
    keyPrimaryTop = Color(0xFFFFFFFF), keyPrimaryMid = Color(0xFFF3EFE7), keyPrimaryBottom = Color(0xFFDCD6CA),
    keyPressTop = Color(0xFFC6C0B4), keyPressBottom = Color(0xFFD9D4C9),
    keyRim = Color(0xFF6B655B), keyBevel = Color.White.copy(alpha = 0.95f), keyShadow = Color.Black.copy(alpha = 0.32f),
    ledOff = Color(0xFFB2ACA1), groove = Color(0xFF8A847A), grooveLight = Color.White.copy(alpha = 0.85f),
    sheetTop = Color(0xFFE4DFD5), sheetBottom = Color(0xFFC8C2B6),
    deckTop = Color(0xFFDDD8CE), deckBottom = Color(0xFFB3ADA2),
    window = Color(0xFFDA8A22),
    dim = 0f,
)

object Pal {
    var palette by mutableStateOf(NightPalette)

    val Bezel: Color get() = palette.bezel
    val Glass: Color get() = palette.glass
    val GlassTop: Color get() = palette.glassTop
    val GlassBottom: Color get() = palette.glassBottom
    val PanelRim: Color get() = palette.panelRim
    val PanelLip: Color get() = palette.panelLip
    val PanelShade: Color get() = palette.panelShade
    val Cream: Color get() = palette.cream
    val Cream2: Color get() = palette.cream2
    val Cream3: Color get() = palette.cream3
    val Cream4: Color get() = palette.cream4
    val Meter: Color get() = palette.meter
    val Ok: Color get() = palette.ok
    val Warn: Color get() = palette.warn
    val Ink: Color get() = palette.ink
    val Window: Color get() = palette.window
    val Beacon = Color(0xFFE5483B)
}

@Immutable
class Illum(val base: Color, day: Boolean = false) {
    val keyTop = if (day) lerp(base, Color.White, 0.12f) else lerp(base, Color.Black, 0.30f)
    val keyBottom = if (day) lerp(base, Color.Black, 0.20f) else lerp(base, Color.Black, 0.62f)
    val glow = if (day) lerp(base, Color.White, 0.45f).copy(alpha = 0.6f) else base.copy(alpha = 0.55f)
    val text = if (day) lerp(base, Color.Black, 0.84f) else lerp(base, Color.White, 0.72f)
    val edge = if (day) lerp(base, Color.White, 0.6f) else lerp(base, Color.White, 0.35f)
    val led = lerp(base, Color.White, 0.12f)
    val ink = if (day) lerp(base, Color.Black, 0.38f) else base
}

fun Illumination.illum(day: Boolean = false): Illum = Illum(
    when (this) {
        Illumination.AMBER -> Color(0xFFF0A43A)
        Illumination.GREEN -> Color(0xFF95D06E)
        Illumination.RED -> Color(0xFFE8503E)
        Illumination.ICE -> Color(0xFFA9D0EB)
    },
    day,
)

val LocalIllum = staticCompositionLocalOf { Illumination.AMBER.illum() }

val LocalU = staticCompositionLocalOf { 1f }
val LocalSettings = staticCompositionLocalOf { Settings() }
val LocalMarquee = staticCompositionLocalOf { true }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Fonts {
    val UD = FontFamily(Font(R.font.ud_regular, FontWeight.Normal), Font(R.font.ud_bold, FontWeight.Bold))
    val Seg = FontFamily(Font(R.font.seg_bold, FontWeight.Bold))
    val SegItalic = FontFamily(Font(R.font.seg_bold_italic, FontWeight.Bold))
}

val Int.u: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalU.current).dp
val Float.u: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalU.current).dp
val Int.us: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { (this@us * LocalU.current).dp.toSp() }
val Float.us: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { (this@us * LocalU.current).dp.toSp() }

@Composable
@ReadOnlyComposable
fun pxPerU(): Float = LocalU.current * LocalDensity.current.density

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
private val OpenLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
fun Txt(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Pal.Cream,
    bold: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    tracking: Float = 0f,
    lineHeight: Float = 1.2f,
    ellipsis: Boolean = true,
    shadow: Shadow? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontFamily = Fonts.UD,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = size.us,
            letterSpacing = tracking.em,
            textAlign = align,
            lineHeight = (size * lineHeight).us,
            lineHeightStyle = if (maxLines > 1) OpenLines else TightLines,
            shadow = shadow,
        ),
        maxLines = maxLines,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        softWrap = maxLines > 1,
    )
}

@Composable
fun Txt(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = Pal.Cream,
    bold: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    tracking: Float = 0f,
    lineHeight: Float = 1.2f,
    ellipsis: Boolean = true,
) = Txt(text, size.toFloat(), modifier, color, bold, maxLines, align, tracking, lineHeight, ellipsis)

@Composable
fun SegText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = Pal.Cream,
    ghost: Boolean = true,
    italic: Boolean = false,
) {
    val shown = text.replace(' ', '!')
    val style = TextStyle(
        color = color,
        fontFamily = if (italic) Fonts.SegItalic else Fonts.Seg,
        fontWeight = FontWeight.Bold,
        fontSize = size.us,
        lineHeight = (size * 1.08f).us,
        lineHeightStyle = TightLines,
    )
    Box(modifier) {
        if (ghost) {
            BasicText(ghostOf(shown), style = style.copy(color = color.copy(alpha = 0.09f)), maxLines = 1, softWrap = false)
        }
        BasicText(shown, style = style, maxLines = 1, softWrap = false)
    }
}

private fun ghostOf(s: String) = buildString {
    s.forEach { c -> append(if (c.isDigit() || c == '-' || c == '!') '8' else c) }
}
