package com.oemplus.roadster.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.oemplus.roadster.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Maneuver {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN,
    KEEP_LEFT, KEEP_RIGHT, MERGE, EXIT_LEFT, EXIT_RIGHT, ROUNDABOUT, ARRIVE, UNKNOWN,
}

@Immutable
data class NavInfo(
    val key: String,
    val pkg: String,
    val app: String,
    val distance: String?,
    val instruction: String?,
    val road: String?,
    val eta: String?,
    val remaining: String?,
    val maneuver: Maneuver,
    val icon: ImageBitmap?,
)

class NavHub {
    private val _info = MutableStateFlow<NavInfo?>(null)
    val info: StateFlow<NavInfo?> = _info.asStateFlow()

    fun post(info: NavInfo) { _info.value = info }
    fun removed(key: String) { if (_info.value?.key == key) _info.value = null }
    fun clearAll() { _info.value = null }
}

object NavParser {
    val KNOWN_NAV = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "net.osmand", "net.osmand.plus", "net.osmand.dev",
        "app.organicmaps", "com.mapswithme.maps.pro",
        "com.sygic.aura",
        "com.here.app.maps",
        "com.generalmagic.magicearth",
        "com.mapfactor.navigator",
        "com.tomtom.gplay.navapp", "com.tomtom.go",
        "cz.seznam.mapy",
        "ru.yandex.yandexnavi",
        "com.coyotesystems.android",
    )

    private const val TEST_PLAYER = "com.oemplus.roadster.testplayer"

    fun isNavSource(pkg: String, chosenNavi: String?): Boolean =
        pkg in KNOWN_NAV || pkg == chosenNavi || (BuildConfig.DEBUG && pkg == TEST_PLAYER)

    private val DIST = Regex(
        """(?i)(\d+(?:[.,]\d+)?)\s?(ft|feet|yd|yds|yards|mi|miles?|km|kilometres?|kilometers?|m|metres?|meters?)\b""",
    )
    private val CLOCK = Regex("""(?i)\b(\d{1,2}:\d{2}\s?(?:[ap]\.?m\.?)?)""")
    private val DURATION = Regex("""(?i)\b(\d+\s?(?:h|hr|hrs|hours?)(?:\s?\d+\s?(?:m|min|mins))?|\d+\s?(?:min|mins|minutes))\b""")
    private val LEADING_IN = Regex("""(?i)^in\s+\d+(?:[.,]\d+)?\s?\w+,?\s*""")
    private val ROAD = Regex("""(?i)\b(?:onto|on|toward|towards|to|via|at)\s+(.+)$""")
    private val MANEUVER_WORDS = Regex(
        """(?i)\b(turn|keep|bear|slight|sharp|exit|merge|continue|head|roundabout|rotary|u-turn|make a|take|destination|arrive|arrived|stay|ramp|straight)\b""",
    )

    fun parse(ctx: Context, sbn: StatusBarNotification, appLabel: String): NavInfo? {
        val n = sbn.notification ?: return null
        val navCategory = n.category == Notification.CATEGORY_NAVIGATION
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!navCategory && !ongoing) return null

        val ex = n.extras
        fun cs(k: String) = ex.getCharSequence(k)?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        val title = cs(Notification.EXTRA_TITLE)
        val text = cs(Notification.EXTRA_TEXT)
        val big = cs(Notification.EXTRA_BIG_TEXT)
        val sub = cs(Notification.EXTRA_SUB_TEXT)
        val info = cs(Notification.EXTRA_INFO_TEXT)

        val primary = listOfNotNull(title, text, big).distinct()
        if (primary.isEmpty()) return null

        val distance = primary.firstNotNullOfOrNull { DIST.find(it)?.value }
        if (!navCategory && distance == null) return null
        val isOnlyDistance: (String) -> Boolean = { line -> DIST.matches(line.trim()) }

        var instruction = primary.firstOrNull { MANEUVER_WORDS.containsMatchIn(it) && !isOnlyDistance(it) }
            ?: primary.firstOrNull { !isOnlyDistance(it) && CLOCK.find(it) == null }
        instruction = instruction?.let { cleanInstruction(it) }

        var road = instruction?.let { ROAD.find(it)?.groupValues?.get(1)?.trim() }
        if (road == null) {
            road = primary.firstOrNull { !MANEUVER_WORDS.containsMatchIn(it) && DIST.find(it) == null && CLOCK.find(it) == null }
        }

        val tail = listOfNotNull(sub, info, text, big).joinToString(" · ")
        val eta = CLOCK.find(tail)?.groupValues?.get(1)?.trim()
        val remainingParts = listOfNotNull(
            sub?.let { DIST.find(it)?.value },
            sub?.let { DURATION.find(it)?.value },
        )
        val remaining = remainingParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")

        val maneuver = detect(listOfNotNull(instruction, title, text).joinToString(" "))
        val icon = if (maneuver == Maneuver.UNKNOWN) loadIcon(ctx, n) else null

        return NavInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            app = appLabel,
            distance = distance,
            instruction = instruction?.takeIf { it != distance },
            road = road?.takeIf { it != instruction },
            eta = eta,
            remaining = remaining,
            maneuver = maneuver,
            icon = icon,
        )
    }

    private fun cleanInstruction(s: String): String {
        val t = LEADING_IN.replace(s, "").trim().trimEnd('.')
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    fun detect(raw: String): Maneuver {
        val s = raw.lowercase()
        fun has(vararg w: String) = w.any { it in s }
        val left = has("left")
        val right = has("right")
        return when {
            has("arrive", "destination", "you have arrived") -> Maneuver.ARRIVE
            has("u-turn", "u turn", "make a u") -> Maneuver.UTURN
            has("roundabout", "rotary", "traffic circle") -> Maneuver.ROUNDABOUT
            has("merge") -> Maneuver.MERGE
            has("exit", "ramp") && left -> Maneuver.EXIT_LEFT
            has("exit", "ramp") -> Maneuver.EXIT_RIGHT
            has("keep left", "stay left", "bear left", "keep to the left") -> Maneuver.KEEP_LEFT
            has("keep right", "stay right", "bear right", "keep to the right") -> Maneuver.KEEP_RIGHT
            has("sharp left") -> Maneuver.SHARP_LEFT
            has("sharp right") -> Maneuver.SHARP_RIGHT
            has("slight left") -> Maneuver.SLIGHT_LEFT
            has("slight right") -> Maneuver.SLIGHT_RIGHT
            left && has("turn", " onto", " at ") -> Maneuver.LEFT
            right && has("turn", " onto", " at ") -> Maneuver.RIGHT
            left -> Maneuver.LEFT
            right -> Maneuver.RIGHT
            has("continue", "head", "straight", "stay on") -> Maneuver.STRAIGHT
            else -> Maneuver.UNKNOWN
        }
    }

    private fun loadIcon(ctx: Context, n: Notification): ImageBitmap? = try {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 23) {
            n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap(160, 160)
        } else null
        (bmp ?: n.extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON))?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
