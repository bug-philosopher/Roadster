package com.oemplus.roadster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.location.SunState
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val DW = 1200f
private const val TOWER = 333f
private const val PX = 9f
private const val PY = 11f
private const val WW = 5f
private const val WH = 6f
private const val ROW0 = 21f
private const val GROUND = 26f
private const val BAY = 872f

private enum class Glass { GRID, BANDS, RIBS }

private class Slot(val x0: Float, val x1: Float, val top: Float, val whole: Boolean = false)

private class Landmark(val slots: List<Slot>, val paint: Pen.() -> Unit)

private class City(val marks: List<Landmark>, val beacons: List<Offset>, val far: List<FloatArray>) {
    val slots: List<Slot> = marks.flatMap { it.slots }
}

private val NightBody = listOf(Color(0xFF0D1330), Color(0xFF121A3C), Color(0xFF0A102A))
private val NightEdge = Color(0xFF34427C)
private val NightRoof = Color(0xFF3B4A86)
private val NightGlass = Color(0xFF242F66)
private val NightDim = Color(0xFF94713C)
private val NightWarm = Color(0xFFFFD27A)
private val NightWhite = Color(0xFFFFF0C8)
private val NightCool = Color(0xFFCFE2FF)
private val NightFar = Color(0xFF27326C)

private val DaySun = listOf(Color(0xFF5B79A6), Color(0xFF6C88AF), Color(0xFF4F6D9C))
private val DayShade = listOf(Color(0xFF17233F), Color(0xFF1B2948), Color(0xFF131E38))
private val DayEdge = Color(0xFF8FA8C9)
private val DayGlass = Color(0xFF3A5482)
private val DayGlassShade = Color(0xFF2D406C)
private val DayFar = Color(0xFFA9B9CD)
private val Vermilion = Color(0xFFE3522B)
private val Paper = Color(0xFFF6F2E7)
private val Indigo = Color(0xFF22305A)
private val VfdGreen = Color(0xFFB2D68C)
private val BeaconRed = Color(0xFFFF4A3A)

private class Pen(val d: DrawScope, val s: Float, val ox: Float, val gy: Float, val night: Boolean, val on: Boolean) {
    fun x(v: Float) = ox + v * s
    fun y(v: Float) = gy - v * s

    fun body(tone: Int): Color = when {
        night -> NightBody[tone % 3]
        on -> DayShade[tone % 3]
        else -> DaySun[tone % 3]
    }

    fun rect(x0: Float, y0: Float, w: Float, h: Float, c: Color) {
        d.drawRect(c, Offset(x(x0), y(y0 + h)), Size(w * s, h * s))
    }

    fun poly(c: Color, vararg p: Float) {
        val path = Path()
        path.moveTo(x(p[0]), y(p[1]))
        var i = 2
        while (i < p.size) {
            path.lineTo(x(p[i]), y(p[i + 1]))
            i += 2
        }
        path.close()
        d.drawPath(path, c)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Color, round: Boolean = false) {
        d.drawLine(c, Offset(x(x0), y(y0)), Offset(x(x1), y(y1)), w * s, if (round) StrokeCap.Round else StrokeCap.Butt)
    }

    fun dot(cx: Float, cy: Float, r: Float, c: Color) = d.drawCircle(c, r * s, Offset(x(cx), y(cy)))

    fun block(x0: Float, w: Float, h: Float, glass: Glass = Glass.GRID, tone: Int = 0, y0: Float = 0f) {
        rect(x0, y0, w, h, body(tone))
        val edge = if (night) NightEdge else if (on) lerp(DayShade[tone % 3], DayEdge, 0.22f) else DayEdge
        val far = if (night) Color(0xFF060917) else if (on) Color(0xFF0E172D) else Color(0xFF3F5985)
        rect(x0, y0, 1.5f, h, edge)
        rect(x0 + w - 1.5f, y0, 1.5f, h, far)
        rect(x0, y0 + h - 1.5f, w, 1.5f, if (night) NightRoof else edge)
        val seed = (x0 * 13f + h * 7f).toInt()
        val r0 = ceil((max(ROW0, y0 + 4f) - ROW0) / PY).toInt()
        val r1 = floor((y0 + h - 4f - WH - ROW0) / PY).toInt()
        when (glass) {
            Glass.GRID -> {
                val cols = max(1, ((w - 4f) / PX).toInt())
                val pad = (w - (cols * PX - (PX - WW))) / 2f
                for (r in r0..r1) for (c in 0 until cols) pane(x0 + pad + c * PX, ROW0 + r * PY, WW, WH, hash(seed, r, c))
            }
            Glass.BANDS -> {
                val parts = max(1, ((w - 6f) / 16f).toInt())
                val pw = (w - 6f - (parts - 1) * 2f) / parts
                for (r in r0..r1) for (c in 0 until parts) pane(x0 + 3f + c * (pw + 2f), ROW0 + r * PY + 0.5f, pw, WH - 1f, hash(seed, r, c))
            }
            Glass.RIBS -> {
                val cols = max(1, ((w - 4f) / 6f).toInt())
                val pad = (w - (cols * 6f - 3f)) / 2f
                for (r in r0..r1) for (c in 0 until cols) pane(x0 + pad + c * 6f, ROW0 + r * PY - 2f, 3f, PY - 1.5f, hash(seed, r / 3, c))
            }
        }
    }

    private fun pane(wx: Float, wy: Float, ww: Float, wh: Float, h: Float) {
        if (!night) {
            rect(wx, wy, ww, wh, if (on) DayGlassShade else DayGlass)
            return
        }
        if (!on) {
            rect(wx, wy, ww, wh, if (h < 0.14f) NightDim else NightGlass)
            return
        }
        if (h > 0.9f) {
            rect(wx, wy, ww, wh, NightGlass)
            return
        }
        val c = if (h < 0.5f) NightWarm else if (h < 0.72f) NightWhite else NightCool
        rect(wx - 1.2f, wy - 1.2f, ww + 2.4f, wh + 2.4f, c.copy(alpha = 0.16f))
        rect(wx, wy, ww, wh, c.copy(alpha = 0.78f + 0.22f * hash(h.toBits(), 3, 9)))
    }

    fun hole(x0: Float, y0: Float, w: Float, h: Float) {
        d.drawRect(Color.Black, Offset(x(x0), y(y0 + h)), Size(w * s, h * s), blendMode = BlendMode.Clear)
    }
}

private fun hash(a: Int, b: Int, c: Int): Float {
    var v = a * 73856093 xor b * 19349663 xor c * 83492791
    v = v xor (v ushr 13)
    v *= 0x5bd1e995
    v = v xor (v ushr 15)
    return (v and 0xFFFF) / 65535f
}

// Tokyo, 2002. One building for each band.
private fun buildTokyo(): City {
    val marks = ArrayList<Landmark>()
    val beacons = ArrayList<Offset>()
    fun mark(x0: Float, x1: Float, top: Float, paint: Pen.() -> Unit) { marks += Landmark(listOf(Slot(x0, x1, top)), paint) }
    fun twin(x0: Float, x1: Float, top: Float, paint: Pen.() -> Unit) {
        val mid = (x0 + x1) / 2f
        marks += Landmark(listOf(Slot(x0, mid, top), Slot(mid, x1, top)), paint)
    }
    fun mast(pen: Pen, x: Float, y0: Float, len: Float) {
        pen.line(x, y0, x, y0 + len, 1.5f, if (pen.night) NightRoof else if (pen.on) DayShade[0] else Indigo)
    }

    mark(0f, 40f, 92f) {
        block(0f, 40f, 92f, Glass.GRID, 1)
        rect(9f, 92f, 16f, 7f, body(1))
    }
    mark(42f, 78f, 158f) {
        block(42f, 36f, 158f, Glass.BANDS, 0)
        mast(this, 60f, 158f, 36f)
        rect(54f, 166f, 12f, 2.5f, body(0))
        rect(56f, 176f, 8f, 2.5f, body(0))
    }
    beacons += Offset(60f, 196f)
    mark(80f, 124f, 118f) {
        block(80f, 44f, 118f, Glass.GRID, 2)
        rect(92f, 118f, 12f, 7f, body(2))
    }
    mark(126f, 190f, 200f) {
        poly(body(1), 126f, 0f, 130f, 18f, 134f, 38f, 137f, 58f, 139f, 78f, 139f, 0f)
        poly(body(1), 190f, 0f, 186f, 18f, 182f, 38f, 179f, 58f, 177f, 78f, 177f, 0f)
        block(139f, 38f, 200f, Glass.RIBS, 1)
    }
    mark(192f, 232f, 223f) {
        block(192f, 40f, 223f, Glass.GRID, 0)
        rect(200f, 223f, 24f, 6f, body(0))
    }
    beacons += Offset(194f, 226f)
    beacons += Offset(230f, 226f)
    twin(236f, 326f, 243f) {
        block(238f, 34f, 228f, Glass.GRID, 2)
        block(290f, 34f, 228f, Glass.GRID, 2)
        block(272f, 18f, 152f, Glass.GRID, 2)
        block(236f, 90f, 118f, Glass.GRID, 2)
        block(243f, 24f, 15f, Glass.GRID, 2, y0 = 228f)
        block(295f, 24f, 15f, Glass.GRID, 2, y0 = 228f)
        for (mx in floatArrayOf(246f, 264f, 298f, 316f)) mast(this, mx, 243f, 10f)
    }
    beacons += Offset(255f, 254f)
    beacons += Offset(307f, 254f)
    mark(330f, 364f, 178f) { block(330f, 34f, 178f, Glass.RIBS, 1) }
    mark(366f, 432f, 235f) {
        val cap = if (night) (if (on) Color(0xFFBFE6C8) else Color(0xFF3E5A6E)) else if (on) Color(0xFF5E7396) else Color(0xFFDDE7EE)
        for ((i, h) in floatArrayOf(180f, 208f, 235f).withIndex()) {
            val bx = 366f + i * 22f
            block(bx, 22f, h, Glass.GRID, 0)
            poly(cap, bx + 1f, h, bx + 11f, h + 13f, bx + 21f, h)
        }
    }
    beacons += Offset(421f, 250f)
    mark(436f, 466f, 132f) { block(436f, 30f, 132f, Glass.BANDS, 2) }
    mark(470f, 516f, 222f) {
        block(470f, 46f, 150f, Glass.GRID, 1)
        block(475f, 36f, 182f, Glass.GRID, 1)
        block(480f, 26f, 204f, Glass.GRID, 1)
        block(486f, 14f, 222f, Glass.GRID, 1)
        poly(body(1), 486f, 222f, 493f, 238f, 500f, 222f)
        mast(this, 493f, 238f, 30f)
        dot(493f, 211f, 5.5f, if (night) Color(0xFF0A102A) else Indigo)
        dot(493f, 211f, 4.3f, if (night) (if (on) NightWhite else Color(0xFFB8A77C)) else if (on) Color(0xFF9AA6BC) else Paper)
    }
    beacons += Offset(493f, 269f)
    mark(520f, 560f, 104f) {
        block(520f, 40f, 104f, Glass.GRID, 0)
        rect(528f, 104f, 14f, 7f, body(0))
    }
    twin(564f, 640f, 222f) {
        block(564f, 76f, 200f, Glass.BANDS, 1)
        block(569f, 66f, 222f, Glass.BANDS, 1)
        poly(body(1), 574f, 222f, 581f, 234f, 623f, 234f, 630f, 222f)
        rect(591f, 234f, 22f, 5f, body(1))
    }
    beacons += Offset(576f, 236f)
    beacons += Offset(628f, 236f)
    mark(644f, 676f, 148f) { block(644f, 32f, 148f, Glass.GRID, 2) }

    marks += Landmark(listOf(Slot(686f, 790f, TOWER, whole = true))) { tokyoTower(this) }
    beacons += Offset(738f, 335f)

    mark(800f, 840f, 180f) {
        block(800f, 40f, 70f, Glass.GRID, 0)
        block(804f, 32f, 132f, Glass.GRID, 0)
        block(810f, 20f, 180f, Glass.GRID, 0)
        hole(812f, 76f, 16f, 10f)
        rect(814f, 180f, 12f, 6f, body(0))
        mast(this, 820f, 186f, 14f)
    }
    beacons += Offset(820f, 201f)
    mark(844f, 878f, 96f) { block(844f, 34f, 96f, Glass.BANDS, 1) }

    mark(884f, 918f, 150f) {
        block(884f, 34f, 150f, Glass.GRID, 2)
        mast(this, 901f, 150f, 12f)
    }
    beacons += Offset(901f, 163f)
    twin(962f, 1036f, 118f) {
        block(962f, 20f, 118f, Glass.GRID, 0)
        block(1016f, 20f, 118f, Glass.GRID, 0)
        for (cy in floatArrayOf(30f, 62f, 96f)) rect(982f, cy, 34f, 8f, body(0))
        rect(962f, 112f, 74f, 6f, body(0))
        val ball = if (night) (if (on) Color(0xFFDCE6FF) else Color(0xFF4A5688)) else if (on) Color(0xFF7C8AA6) else Color(0xFFDDE2E8)
        dot(1003f, 84f, 15.5f, if (night) Color(0xFF0A102A) else Indigo)
        dot(1003f, 84f, 14.2f, ball)
        dot(998f, 89f, 4.5f, Color.White.copy(alpha = if (night && !on) 0.12f else 0.5f))
    }
    mark(1044f, 1078f, 84f) { block(1044f, 34f, 84f, Glass.BANDS, 2) }
    mark(1182f, 1204f, 58f) { block(1182f, 22f, 58f, Glass.GRID, 1) }

    val r = Random(2002)
    val far = ArrayList<FloatArray>()
    var fx = -12f
    while (fx < DW + 12f) {
        val w = 26f + r.nextInt(38)
        far += floatArrayOf(fx, w, 38f + r.nextInt(84))
        fx += w
    }
    return City(marks, beacons, far)
}

private val towerProfile = floatArrayOf(0f, 48f, 22f, 37f, 44f, 29f, 66f, 22.5f, 88f, 17f, 110f, 13f, 130f, 10f, 150f, 8f, 184f, 6.6f, 216f, 5.6f, 250f, 4.6f)

private fun towerHalf(y: Float): Float {
    val p = towerProfile
    var i = 0
    while (i < p.size - 4 && y > p[i + 2]) i += 2
    val f = ((y - p[i]) / (p[i + 2] - p[i])).coerceIn(0f, 1f)
    return p[i + 1] + (p[i + 3] - p[i + 1]) * f
}

private fun tokyoTower(pen: Pen) = with(pen) {
    val cx = 738f
    fun colour(y: Float): Color {
        val white = (y in 185f..215f) || (y in 262f..284f) || (y in 306f..320f)
        return when {
            night && on -> lerp(Color(0xFFFF8B34), Color(0xFFFFE0A8), (y / TOWER).coerceIn(0f, 1f))
            night -> Color(0xFF9A4A22)
            on -> if (white) Color(0xFF8A93A8) else Color(0xFF8C2E18)
            else -> if (white) Paper else Vermilion
        }
    }
    fun strut(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        val c = colour((y0 + y1) / 2f)
        if (night && on) line(x0, y0, x1, y1, w + 3.2f, Color(0xFFFF7A2A).copy(alpha = 0.16f), round = true)
        line(x0, y0, x1, y1, w, c, round = true)
    }
    val rings = floatArrayOf(0f, 22f, 44f, 66f, 88f, 110f, 130f, 150f, 162f, 184f, 206f, 228f, 250f)
    for (i in 0 until rings.size - 1) {
        val a = rings[i]
        val b = rings[i + 1]
        val ha = towerHalf(a)
        val hb = towerHalf(b)
        val leg = if (a < 150f) 2.4f else 1.8f
        strut(cx - ha, a, cx - hb, b, leg)
        strut(cx + ha, a, cx + hb, b, leg)
        if (a > 0f) strut(cx - ha, a, cx + ha, a, 1.1f)
        if (a >= 22f || b <= 22f) {
            strut(cx - ha, a, cx + hb, b, 0.9f)
            strut(cx + ha, a, cx - hb, b, 0.9f)
        }
    }
    var px = cx - 34f
    var py = 0f
    for (k in 1..10) {
        val t = k / 10f
        val nx = cx - 34f + 68f * t
        val ny = 44f * sin(PI.toFloat() * t)
        strut(px, py, nx, ny, 1.4f)
        px = nx
        py = ny
    }
    val deck = if (night) (if (on) Color(0xFFFFF0C8) else Color(0xFFA08658)) else if (on) Color(0xFF8A93A8) else Paper
    val trim = if (night) Color(0xFF3A1E12) else if (on) Color(0xFF17233F) else Indigo
    rect(cx - 15f, 148f, 30f, 14f, deck)
    rect(cx - 15f, 153.5f, 30f, 2.6f, trim)
    rect(cx - 7.5f, 247f, 15f, 8f, deck)
    rect(cx - 7.5f, 250f, 15f, 1.8f, trim)
    for (i in 0 until 6) {
        val a = 255f + i * 13f
        val b = min(TOWER, a + 13f)
        val wa = 3f - i * 0.4f
        strut(cx, a, cx, b, wa)
    }
}

private fun bridge(pen: Pen) = with(pen) {
    val steel = if (night) Color(0xFFE9EEFF) else Paper
    val under = if (night) Color(0xFF8CA0E0).copy(alpha = 0.22f) else Color(0xFF4A5E88)
    fun beam(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        line(x0, y0, x1, y1, w + if (night) 3f else 1.4f, under)
        line(x0, y0, x1, y1, w, steel)
    }
    for (p in bridgeCable()) {
        if (p.x.toInt() % 2 == 0 && p.y > DECK + 3f) line(p.x, DECK, p.x, p.y, 0.5f, steel.copy(alpha = if (night) 0.35f else 0.9f))
    }
    val cable = bridgeCable()
    for (i in 0 until cable.size - 1) beam(cable[i].x, cable[i].y, cable[i + 1].x, cable[i + 1].y, 1.2f)
    beam(BAY + 6f, DECK, DW + 12f, DECK, 2.6f)
    line(BAY + 6f, DECK - 5f, DW + 12f, DECK - 5f, 1f, steel.copy(alpha = if (night) 0.5f else 1f))
    var tx = BAY + 12f
    while (tx < DW + 10f) {
        line(tx, DECK - 5f, tx, DECK, 0.6f, steel.copy(alpha = if (night) 0.4f else 0.9f))
        tx += 12f
    }
    for (t in floatArrayOf(TOWER_A, TOWER_B)) {
        beam(t - 7f, -GROUND, t - 5f, 126f, 3f)
        beam(t + 7f, -GROUND, t + 5f, 126f, 3f)
        for (by in floatArrayOf(DECK + 1f, 72f, 104f, 123f)) beam(t - 6.5f, by, t + 6.5f, by, 2.2f)
    }
    if (night) {
        var lx = BAY + 10f
        while (lx < DW + 10f) {
            dot(lx, DECK + 2.6f, 1.1f, NightWarm.copy(alpha = 0.85f))
            lx += 14f
        }
    }
}

private const val DECK = 36f
private const val TOWER_A = 946f
private const val TOWER_B = 1104f

private fun bridgeCable(): List<Offset> {
    val out = ArrayList<Offset>()
    fun span(x0: Float, y0: Float, x1: Float, y1: Float, sag: Float) {
        val n = max(2, ((x1 - x0) / 6f).toInt())
        for (i in 0..n) {
            val t = i / n.toFloat()
            out += Offset(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t - sag * 4f * t * (1f - t))
        }
    }
    span(BAY + 8f, DECK + 3f, TOWER_A, 126f, 9f)
    span(TOWER_A, 126f, TOWER_B, 126f, 78f)
    span(TOWER_B, 126f, DW + 12f, 62f, 6f)
    return out
}

private const val WHEEL_X = 1140f
private const val WHEEL_Y = 62f
private const val WHEEL_R = 48f

private fun paintCity(pen: Pen, city: City) = with(pen) {
    val farC = if (night) NightFar else DayFar
    for (b in city.far) {
        rect(b[0], 0f, b[1], b[2], farC)
        if (night) {
            val n = (b[1] / 14f).toInt()
            for (i in 0 until n) {
                val h = hash(b[0].toInt(), i, 5)
                if (h > 0.45f) dot(b[0] + 6f + i * 13f, 14f + h * (b[2] - 22f), 0.9f, NightWarm.copy(alpha = 0.35f + 0.3f * h))
            }
        }
    }
    d.drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to if (night) Color(0xFFD08A6A).copy(alpha = 0.30f) else Color(0xFFFFF6E4).copy(alpha = 0.42f),
            startY = y(130f), endY = y(0f),
        ),
        Offset(0f, y(130f)), Size(d.size.width, 130f * s),
    )
    for (m in city.marks) m.paint(pen)

    val frame = if (night) Color(0xFF3A4884) else if (on) DayShade[0] else Indigo
    line(WHEEL_X, WHEEL_Y, WHEEL_X - 24f, 0f, 2.4f, frame)
    line(WHEEL_X, WHEEL_Y, WHEEL_X + 24f, 0f, 2.4f, frame)

    bridge(pen)

    rect(BAY, -GROUND, DW + 12f - BAY, GROUND, if (night) Color(0xFF0B1436) else Color(0xFF7EA4C2))
    rect(BAY, -2f, DW + 12f - BAY, 2f, if (night) Color(0xFF26315F) else Color(0xFFB9D0DF))
    for (t in floatArrayOf(TOWER_A, TOWER_B)) {
        val leg = if (night) Color(0xFFE9EEFF) else Paper
        rect(t - 8.5f, -GROUND, 3.2f, GROUND, leg)
        rect(t + 5.3f, -GROUND, 3.2f, GROUND, leg)
    }
    val land = if (night) Color(0xFF070A18) else Color(0xFF3B4E79)
    rect(-12f, -GROUND, BAY + 12f, GROUND, land)
    rect(-12f, -1.6f, BAY + 12f, 1.6f, if (night) Color(0xFF1A2248) else Color(0xFF5B79A6))
    val road = if (night) Color(0xFF161D40) else Color(0xFF17233F)
    val rail = if (night) Color(0xFF3B4A86) else Color(0xFF8FA8C9)
    rect(-12f, 6f, BAY + 4f, 8f, road)
    rect(-12f, 13.2f, BAY + 4f, 1.2f, rail)
    var pier = 20f
    while (pier < BAY - 10f) {
        rect(pier, -GROUND, 6f, GROUND + 6f, road)
        pier += 74f
    }
    var lamp = 34f
    while (lamp < BAY - 6f) {
        line(lamp, 14f, lamp, 24f, 0.9f, rail)
        if (night) {
            dot(lamp, 24.5f, 4.2f, Color(0xFFFFB45A).copy(alpha = 0.18f))
            dot(lamp, 24.5f, 1.5f, Color(0xFFFFD08A))
        } else {
            rect(lamp - 2.2f, 23.4f, 4.4f, 1.3f, rail)
        }
        lamp += 62f
    }
}

private class Heavens(val across: Float, val warmth: Float, val moonLit: Float, val waxing: Boolean) {
    val bucket: Int get() = (warmth * 6f).roundToInt()
}

private fun heavens(now: Long, sun: SunState, night: Boolean): Heavens {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    val rise = sun.sunrise
    val set = sun.sunset
    val across = if (!night) {
        if (rise != null && set != null && set > rise) ((now - rise).toFloat() / (set - rise)).coerceIn(0.04f, 0.96f)
        else ((hour - 6f) / 12f).coerceIn(0.04f, 0.96f)
    } else {
        val h = if (hour >= 12f) hour - 18f else hour + 6f
        (h / 12f).coerceIn(0.2f, 0.8f)
    }
    val warmth = if (night) 0f else (1f - min(across, 1f - across) / 0.2f).coerceIn(0f, 1f)
    val days = (now - 947182440000L) / 86_400_000.0
    val phase = ((days / 29.530588853) % 1.0 + 1.0) % 1.0
    val lit = ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
    return Heavens(across, warmth, lit, phase < 0.5)
}

private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val speed: Float, val phase: Float)

private class Scene(
    val w: Int, val h: Int, val night: Boolean, val bucket: Int,
    val s: Float, val ox: Float, val gy: Float,
    val city: City,
    val sky: ImageBitmap, val off: ImageBitmap, val on: ImageBitmap,
    val clouds: List<ImageBitmap>, val twinklers: List<Star>, val cable: List<Offset>,
) {
    fun x(v: Float) = ox + v * s
    fun y(v: Float) = gy - v * s

    var glow: Brush? = null
    var glowAt: Offset = Offset.Unspecified
    val towerGlow: Brush = Brush.radialGradient(
        listOf(Color(0xFFFF9A45).copy(alpha = 0.55f), Color(0xFFFF7A2A).copy(alpha = 0.16f), Color.Transparent),
        Offset(x(738f), y(150f)), 190f * s,
    )
}

private fun render(w: Int, h: Int, density: Density, block: DrawScope.() -> Unit): ImageBitmap {
    val bmp = ImageBitmap(max(1, w), max(1, h))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat()), block)
    return bmp
}

// Paint the city one time: lights off and lights on.
private fun buildScene(size: Size, density: Density, night: Boolean, sky: Heavens, city: City): Scene {
    val w = size.width.toInt()
    val h = size.height.toInt()
    val s = min(size.width / DW, size.height * 0.80f / (TOWER + GROUND))
    val ox = (size.width - DW * s) / 2f
    val gy = size.height - GROUND * s
    val r = Random(77)

    val stars = List(150) {
        val up = r.nextFloat()
        Star(r.nextFloat() * size.width, up * up * gy * 0.86f, (0.7f + r.nextFloat() * 1.0f) * s, 0.3f + r.nextFloat() * 0.7f, 0.8f + r.nextFloat() * 2.4f, r.nextFloat() * 6.28f)
    }
    val skyBmp = render(w, h, density) {
        val wm = sky.warmth
        val stops = if (night) arrayOf(
            0f to Color(0xFF060A1E), 0.30f to Color(0xFF101A42), 0.60f to Color(0xFF232E68),
            0.84f to Color(0xFF51427E), 1f to Color(0xFF93607A),
        ) else arrayOf(
            0f to lerp(Color(0xFF2A6AA8), Color(0xFF34588F), wm),
            0.17f to lerp(Color(0xFF6CA5D0), Color(0xFF7E9FC4), wm),
            0.38f to lerp(Color(0xFFC6DDE4), Color(0xFFE6D6C4), wm),
            0.62f to lerp(Color(0xFFEFE9D4), Color(0xFFF6D9AE), wm),
            0.86f to lerp(Color(0xFFF4DFBB), Color(0xFFF3B27A), wm),
            1f to lerp(Color(0xFFF1D2A4), Color(0xFFEB8F5C), wm),
        )
        drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = gy))
        if (night) for (st in stars) drawCircle(Color(0xFFF4F0E2).copy(alpha = st.a * 0.85f), st.r, Offset(st.x, st.y))
    }
    fun layer(on: Boolean) = render(w, h, density) { paintCity(Pen(this, s, ox, gy, night, on), city) }

    val cloudShapes = listOf(
        floatArrayOf(150f, 0f, 30f, 26f, 22f, 62f, 34f, 30f, 98f, 28f, 26f, 126f, 22f, 18f),
        floatArrayOf(110f, 0f, 24f, 22f, 18f, 52f, 28f, 26f, 84f, 22f, 18f),
        floatArrayOf(190f, 0f, 34f, 22f, 20f, 70f, 30f, 26f, 108f, 36f, 30f, 148f, 26f, 22f, 170f, 20f, 14f),
    )
    val clouds = cloudShapes.map { c ->
        val cw = (c[0] * s).toInt() + 4
        val ch = (58f * s).toInt() + 4
        render(cw, ch, density) {
            val base = ch - 6f * s
            val path = Path()
            var i = 2
            while (i < c.size) {
                path.addOval(Rect(Offset(c[i] * s, base - c[i + 1] * s * 0.62f), c[i + 2] * s))
                i += 3
            }
            path.addRoundRect(androidx.compose.ui.geometry.RoundRect(10f * s, base - 16f * s, (c[0] - 10f) * s, base + 4f * s, 9f * s, 9f * s))
            clipRect(0f, 0f, size.width, base + 0.5f) {
                drawPath(path, if (night) Color(0xFF2B3670) else Color(0xFFFCFBF6))
                clipRect(0f, base - 9f * s, size.width, base + 1f) {
                    drawPath(path, if (night) Color(0xFF1C2452) else Color(0xFFC9D8E4))
                }
            }
        }
    }
    return Scene(
        w, h, night, sky.bucket, s, ox, gy, city, skyBmp, layer(false), layer(true), clouds,
        stars.filterIndexed { i, _ -> i % 5 == 0 }, bridgeCable().filterIndexed { i, _ -> i % 2 == 0 },
    )
}

private val fujiPath = Path()
private val moonPath = Path()
private val shadePath = Path()

private fun DrawScope.fuji(sc: Scene, sky: Heavens) {
    val p = fujiPath
    p.reset()
    p.moveTo(sc.x(-150f), sc.y(0f))
    p.quadraticTo(sc.x(196f), sc.y(44f), sc.x(318f), sc.y(304f))
    p.lineTo(sc.x(346f), sc.y(304f))
    p.quadraticTo(sc.x(468f), sc.y(44f), sc.x(814f), sc.y(0f))
    p.close()
    val body = if (sc.night) Color(0xFF151D45) else lerp(Color(0xFF8FA8C6), Color(0xFFA596B4), sky.warmth)
    drawPath(p, body)
    clipPath(p) {
        val snow = Path()
        val zig = floatArrayOf(236f, 222f, 254f, 236f, 270f, 216f, 286f, 240f, 302f, 220f, 318f, 244f, 332f, 222f, 346f, 244f, 362f, 218f, 378f, 240f, 394f, 216f, 410f, 236f, 428f, 222f)
        snow.moveTo(sc.x(236f), sc.y(320f))
        var i = 0
        while (i < zig.size) {
            snow.lineTo(sc.x(zig[i]), sc.y(zig[i + 1]))
            i += 2
        }
        snow.lineTo(sc.x(428f), sc.y(320f))
        snow.close()
        drawPath(snow, if (sc.night) Color(0xFF6876A6) else lerp(Color(0xFFFBF8F0), Color(0xFFFBE3CF), sky.warmth))
    }
}

private fun DrawScope.orb(sc: Scene, sky: Heavens, bass: Float) {
    val cx = (size.width * (0.90f - 0.62f * sky.across)).roundToInt().toFloat()
    val lift = Math.pow(sin(PI * sky.across), 0.6).toFloat()
    val cy = (sc.gy * (0.70f - 0.56f * lift)).roundToInt().toFloat()
    val c = Offset(cx, cy)
    val r = (if (sc.night) 22f else 25f) * sc.s
    val glow = r * if (sc.night) 5.4f else 3.4f
    if (sc.glow == null || sc.glowAt != c) {
        sc.glowAt = c
        sc.glow = if (sc.night) {
            Brush.radialGradient(listOf(Color(0xFFF6EFD9).copy(alpha = 0.46f), Color(0xFFBFC9F2).copy(alpha = 0.15f), Color.Transparent), c, glow)
        } else {
            Brush.radialGradient(listOf(Color(0xFFFFF3C6).copy(alpha = 0.90f), Color(0xFFFFE9B0).copy(alpha = 0.34f), Color.Transparent), c, glow)
        }
    }
    drawCircle(sc.glow!!, glow, c, alpha = 0.80f + 0.20f * bass)
    if (!sc.night) {
        drawCircle(lerp(Color(0xFFE9552B), Color(0xFFD63D1C), sky.warmth), r, c)
        return
    }
    drawCircle(Color(0xFF6A77AA), r, c)
    val lit = max(0.24f, sky.moonLit)
    val rx = r * abs(1f - 2f * lit)
    val limb = if (sky.waxing) -90f else 90f
    val p = moonPath
    p.reset()
    p.arcTo(Rect(c, r), limb, 180f, true)
    val away = lit > 0.5f
    val sweep = if (away == sky.waxing) 180f else -180f
    p.arcTo(Rect(cx - rx, cy - r, cx + rx, cy + r), limb + 180f, sweep, false)
    p.close()
    drawPath(p, Color(0xFFFAF4E0))
    clipPath(p) {
        drawCircle(Color(0xFFDAD1B5), r * 0.26f, c + Offset(-r * 0.28f, -r * 0.30f))
        drawCircle(Color(0xFFDAD1B5), r * 0.18f, c + Offset(r * 0.30f, -r * 0.05f))
        drawCircle(Color(0xFFDAD1B5), r * 0.22f, c + Offset(-r * 0.05f, r * 0.42f))
    }
}

private fun DrawScope.clouds(sc: Scene, t: Double) {
    val lanes = if (sc.night) floatArrayOf(0.20f, 0.30f, 2.2f, 2f, 1.1f, 0.74f, 0.16f, 1.5f, 0f, 0.9f)
    else floatArrayOf(0.05f, 0.14f, 4.5f, 0f, 1.15f, 0.38f, 0.33f, 3.0f, 2f, 1.0f, 0.62f, 0.10f, 5.5f, 1f, 0.9f, 0.86f, 0.42f, 2.4f, 0f, 0.75f, 0.24f, 0.52f, 1.8f, 1f, 0.65f)
    val span = size.width + 320f * sc.s
    var i = 0
    while (i < lanes.size) {
        val img = sc.clouds[lanes[i + 3].toInt()]
        val k = lanes[i + 4]
        val x = ((lanes[i] * span + t * lanes[i + 2] * sc.s) % span).toFloat() - 250f * sc.s
        val y = sc.gy * lanes[i + 1]
        translate(x, y) {
            drawImage(
                img, dstSize = IntSize((img.width * k).toInt(), (img.height * k).toInt()),
                alpha = if (sc.night) 0.78f else 0.96f,
            )
        }
        i += 5
    }
}

// Show the lit layer up to the band level.
private fun DrawScope.reveal(sc: Scene, slot: Slot, level: Float, lean: Float) {
    val x0 = floor(sc.x(slot.x0)).toInt().coerceAtLeast(0)
    val x1 = ceil(sc.x(slot.x1)).toInt().coerceAtMost(sc.w)
    if (x1 <= x0) return
    val bottom = sc.h
    if (sc.night && !slot.whole) {
        val rows = ((slot.top - ROW0) / PY).toInt()
        val lit = (level * rows * 1.06f).roundToInt().coerceIn(0, rows + 2)
        if (lit == 0) return
        val top = sc.y(ROW0 + lit * PY - (PY - WH) / 2f).toInt().coerceIn(0, bottom - 1)
        drawImage(sc.on, IntOffset(x0, top), IntSize(x1 - x0, bottom - top), IntOffset(x0, top), IntSize(x1 - x0, bottom - top))
        return
    }
    val hgt = min(slot.top + 14f, level * slot.top * 1.04f)
    if (hgt < 2f) return
    if (sc.night) {
        val top = sc.y(hgt).toInt().coerceIn(0, bottom - 1)
        drawImage(sc.on, IntOffset(x0, top), IntSize(x1 - x0, bottom - top), IntOffset(x0, top), IntSize(x1 - x0, bottom - top))
        return
    }
    val tilt = (slot.x1 - slot.x0) * 0.12f * lean
    val top = sc.y(hgt + abs(tilt)).toInt().coerceIn(0, bottom - 1)
    val p = shadePath
    p.reset()
    p.moveTo(x0.toFloat(), bottom.toFloat())
    p.lineTo(x0.toFloat(), sc.y(hgt - tilt))
    p.lineTo(x1.toFloat(), sc.y(hgt + tilt))
    p.lineTo(x1.toFloat(), bottom.toFloat())
    p.close()
    clipPath(p) {
        drawImage(sc.on, IntOffset(x0, top), IntSize(x1 - x0, bottom - top), IntOffset(x0, top), IntSize(x1 - x0, bottom - top))
    }
}

private fun DrawScope.wheel(sc: Scene, t: Double, anim: SpectrumAnim) {
    val c = Offset(sc.x(WHEEL_X), sc.y(WHEEL_Y))
    val r = WHEEL_R * sc.s
    val turn = t * 0.07
    val rim = if (sc.night) Color(0xFF4C5B9C) else Indigo
    drawCircle(rim, r, c, style = Stroke(1.8f * sc.s))
    drawCircle(rim.copy(alpha = 0.7f), r * 0.72f, c, style = Stroke(0.8f * sc.s))
    val n = 16
    for (i in 0 until n) {
        val a = turn + i * 2.0 * PI / n
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        val tip = c + dir * r
        drawLine(rim.copy(alpha = if (sc.night) 0.55f else 0.85f), c, tip, 0.8f * sc.s)
        if (sc.night) {
            val lvl = anim.level[anim.bands - 1 - (i % 4)]
            val len = 0.22f + 0.78f * lvl
            drawLine(VfdGreen.copy(alpha = 0.35f + 0.65f * lvl), c + dir * (r * 0.1f), c + dir * (r * len), 1.6f * sc.s, StrokeCap.Round)
            drawCircle((if (i % 2 == 0) NightWarm else NightCool), 1.7f * sc.s, tip)
        } else {
            drawCircle(Indigo, 3.3f * sc.s, tip)
            drawCircle(if (i % 2 == 0) Vermilion else Paper, 2.4f * sc.s, tip)
        }
    }
    drawCircle(rim, 3.6f * sc.s, c)
}

private fun DrawScope.traffic(sc: Scene, t: Double) {
    val span = BAY + 60f
    for (i in 0 until 9) {
        val east = i % 2 == 0
        val speed = 34f + 9f * hash(i, 2, 7)
        val raw = (hash(i, 5, 1) * span + t * speed) % span
        val x = (if (east) raw else span - raw).toFloat() - 30f
        if (x > BAY - 6f) continue
        val y = if (east) 15.4f else 17.2f
        val dir = if (east) 1f else -1f
        if (sc.night) {
            drawCircle(Color(0xFFFFF6DC), 1.3f * sc.s, Offset(sc.x(x + 3f * dir), sc.y(y)))
            drawCircle(Color(0xFFFFF6DC).copy(alpha = 0.22f), 3.4f * sc.s, Offset(sc.x(x + 4f * dir), sc.y(y)))
            drawCircle(BeaconRed, 1.1f * sc.s, Offset(sc.x(x - 3f * dir), sc.y(y)))
        } else {
            val c = when (i % 3) { 0 -> Vermilion; 1 -> Paper; else -> Color(0xFF121B33) }
            drawRect(Color(0xFF121B33), Offset(sc.x(x - 4.6f), sc.y(y + 2f)), Size(9.2f * sc.s, 3.8f * sc.s))
            drawRect(c, Offset(sc.x(x - 3.8f), sc.y(y + 1.4f)), Size(7.6f * sc.s, 2.6f * sc.s))
        }
    }
}

private fun DrawScope.water(sc: Scene, t: Double, loud: Float) {
    for (j in 0 until 22) {
        val h = hash(j, 11, 4)
        val h2 = hash(j, 3, 8)
        val depth = 4f + h2 * (GROUND - 7f)
        if (sc.night) {
            val x = BAY + 14f + h * (DW - BAY - 20f)
            val a = (0.10f + 0.55f * loud) * (0.55f + 0.45f * sin(t * (1.5 + h2 * 2.0) + j).toFloat())
            val c = if (j % 3 == 0) NightCool else NightWarm
            drawLine(c.copy(alpha = a.coerceIn(0f, 1f)), Offset(sc.x(x), sc.y(-depth)), Offset(sc.x(x + 5f + 12f * h2), sc.y(-depth)), 1.1f * sc.s)
        } else {
            val span = DW - BAY + 30f
            val x = BAY - 10f + ((h * span + t * (3.0 + 4.0 * h2)) % span).toFloat()
            drawLine(Color(0xFFE4EEF3).copy(alpha = 0.55f + 0.35f * h2), Offset(sc.x(x), sc.y(-depth)), Offset(sc.x(x + 8f + 14f * h2), sc.y(-depth)), 1.1f * sc.s)
        }
    }
}

@Composable
fun SkylineView(anim: SpectrumAnim, frame: LongState, modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val night = Pal.palette.dark
    val sun by Graph.sun.state.collectAsStateWithLifecycle()
    val city = remember { buildTokyo() }
    val cache = remember { arrayOfNulls<Scene>(1) }
    val skyCache = remember(sun, night) { arrayOfNulls<Heavens>(1) }
    val skyAt = remember(sun, night) { longArrayOf(0L) }
    val rim = Pal.PanelRim
    Canvas(modifier.clipToBounds()) {
        frame.longValue
        if (size.width < 8f || size.height < 8f) return@Canvas
        val now = System.currentTimeMillis()
        val sky = skyCache[0]?.takeIf { now - skyAt[0] < 20_000L }
            ?: heavens(now, sun, night).also { skyCache[0] = it; skyAt[0] = now }
        val sc = cache[0]?.takeIf { it.w == size.width.toInt() && it.h == size.height.toInt() && it.night == night && it.bucket == sky.bucket }
            ?: buildScene(size, this, night, sky, city).also { cache[0] = it }
        val t = if (reduceMotion) 0.0 else anim.timeSec
        val bands = anim.bands
        var loud = 0f
        for (b in 0 until bands) loud += anim.level[b]
        loud /= bands
        val bass = (anim.level[0] + anim.level[1] + anim.level[2]) / 3f

        drawImage(sc.sky)
        if (night) {
            for (st in sc.twinklers) {
                val a = 0.5f + 0.5f * sin(t * st.speed + st.phase).toFloat()
                drawCircle(Color.White.copy(alpha = 0.25f + 0.75f * a), st.r * (0.9f + 0.6f * a), Offset(st.x, st.y))
            }
        }
        orb(sc, sky, bass)
        fuji(sc, sky)
        clouds(sc, t)
        if (night) drawCircle(sc.towerGlow, 190f * sc.s, Offset(sc.x(738f), sc.y(150f)), alpha = 0.30f + 0.70f * min(1f, loud * 1.4f))
        drawImage(sc.off)

        val lean = if (sky.across < 0.5f) -1f else 1f
        val slots = city.slots
        val each = slots.count { !it.whole }
        var i = 0
        for (slot in slots) {
            val level = if (slot.whole) min(1f, loud * 1.35f) else anim.level[min(bands - 1, i++ * bands / each)]
            reveal(sc, slot, level, lean)
        }

        wheel(sc, t, anim)
        traffic(sc, t)
        water(sc, t, loud)
        if (night) {
            sc.cable.forEachIndexed { j, p ->
                val lvl = anim.level[bands * 2 / 3 + j % (bands - bands * 2 / 3)]
                val c = Offset(sc.x(p.x), sc.y(p.y))
                drawCircle(NightCool.copy(alpha = 0.10f + 0.25f * lvl), 3.4f * sc.s, c)
                drawCircle(Color(0xFFF2F6FF).copy(alpha = 0.45f + 0.55f * lvl), 1.25f * sc.s, c)
            }
            val blink = reduceMotion || sin(t * PI * 1.1) > 0.15
            for (b in city.beacons) {
                val c = Offset(sc.x(b.x), sc.y(b.y))
                if (blink) drawCircle(BeaconRed.copy(alpha = 0.22f), 6.5f * sc.s, c)
                drawCircle(BeaconRed.copy(alpha = if (blink) 1f else 0.25f), 2f * sc.s, c)
            }
        }
        drawRect(rim.copy(alpha = if (night) 0.9f else 0.55f), style = Stroke(1.5f * sc.s))
    }
}
