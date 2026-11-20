package com.oemplus.roadster.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.oemplus.roadster.media.NavParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class AppEntry(val pkg: String, val activity: String, val label: String, val icon: ImageBitmap?)

class AppCatalog(private val ctx: Context, private val scope: CoroutineScope) {
    private val pm = ctx.packageManager
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        refresh()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            ctx,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = refresh()
            },
            f,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { load() }
            _apps.value = list
            _loaded.value = true
        }
    }

    private fun load(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                val icon = try {
                    ri.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
                labels[pkg] = label
                icon?.let { icons[pkg] = it }
                AppEntry(pkg, ri.activityInfo.name, label, icon)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun labelFor(pkg: String): String = labels[pkg] ?: try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().also { labels[pkg] = it }
    } catch (_: Throwable) {
        pkg.substringAfterLast('.')
    }

    fun iconFor(pkg: String): ImageBitmap? = icons[pkg] ?: try {
        pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { icons[pkg] = it }
    } catch (_: Throwable) {
        null
    }

    fun isInstalled(pkg: String?): Boolean = pkg != null && try {
        pm.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)?.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
    )

    fun appInfoIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val linkWords = Regex("""(?i)(zlink|tlink|carplay|car ?play|android ?auto|autokit|carlink|car ?auto|cplay|phone ?link|smart ?link|easyconnect|carbit)""")
    private val navWords = Regex("""(?i)(maps|navi|waze|osmand|sygic|gps|route)""")
    private val mediaWords = Regex("""(?i)(music|radio|bluetooth|bt ?audio|player|podcast|spotify|tidal|deezer|pandora|audible|fm|dab|usb|media)""")
    private val preferredNav = listOf("com.google.android.apps.maps", "com.waze")

    fun suggestLink(): List<AppEntry> =
        _apps.value.filter { linkWords.containsMatchIn(it.label) || linkWords.containsMatchIn(it.pkg) }

    fun suggestNavi(): List<AppEntry> {
        val all = _apps.value
        val known = all.filter { it.pkg in NavParser.KNOWN_NAV }.sortedBy { preferredNav.indexOf(it.pkg).let { i -> if (i < 0) 99 else i } }
        val guessed = all.filter { it.pkg !in NavParser.KNOWN_NAV && navWords.containsMatchIn(it.label) }
        return known + guessed
    }

    private val notSources = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.maps",
        "com.google.android.gms",
        "com.android.settings",
    )

    fun suggestSources(): List<AppEntry> {
        val browsers = try {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
                .map { it.serviceInfo.packageName }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        val candidates = _apps.value.filter { it.pkg !in notSources }
        val named = candidates.filter { mediaWords.containsMatchIn(it.label) }
        val services = candidates.filter { it.pkg in browsers }
        return (named + services).distinctBy { it.pkg }
    }

    fun defaultNavi(): String? = suggestNavi().firstOrNull { it.pkg in NavParser.KNOWN_NAV }?.pkg

    fun defaultLink(): String? = suggestLink().singleOrNull()?.pkg

    companion object {
        const val ICON_PX = 112
    }
}
