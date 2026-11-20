package com.oemplus.roadster.system

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.oemplus.roadster.BuildConfig
import com.oemplus.roadster.Graph
import com.oemplus.roadster.media.MediaListener
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

object Diagnostics {
    fun report(ctx: Context, deep: Boolean = false): String = buildString {
        val pm = ctx.packageManager
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        appendLine("Roadster ${BuildConfig.VERSION_NAME} audio report · $now")
        val soc = if (Build.VERSION.SDK_INT >= 31) " · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        appendLine("Unit: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.HARDWARE}$soc")
        val access = Graph.access.refresh()
        appendLine("Notification access: ${yes(access.notifications)} · microphone: ${yes(access.microphone)}")
        appendLine("Analyzer: ${Graph.analyzer.status.value} · hears audio right now: ${yes(Graph.analyzer.audible.value)}")
        appendLine()

        appendLine("HCT / MICRONTEK BLUETOOTH (Eonon, XTRONS, Dasaita…)")
        Graph.media.describeMtc().forEach { appendLine("  $it") }
        appendLine()

        val bt = Graph.media.bt.value
        appendLine("ANDROID BLUETOOTH PLAYER LINK")
        appendLine("  ${bt.service ?: "no service found"} · ${bt.status} · attempts ${bt.attempts}")
        appendLine()

        appendLine("PLAYERS ROADSTER CAN SEE")
        val seen = Graph.media.describe()
        if (seen.isEmpty()) appendLine("  (none)") else seen.forEach { appendLine("  $it") }
        val cur = Graph.media.current.value
        appendLine("  shown now: ${cur?.let { "${it.pkg} · ${it.title ?: "-"}" } ?: "nothing"}")
        appendLine()

        appendLine("MEDIA BROWSER SERVICES INSTALLED")
        val browsers = try {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
        } catch (_: Throwable) {
            emptyList()
        }
        if (browsers.isEmpty()) appendLine("  (none)")
        browsers.forEach { appendLine("  ${it.serviceInfo.packageName}/${it.serviceInfo.name} (${it.loadLabel(pm)})") }
        appendLine()

        appendLine("BLUETOOTH, MUSIC AND PHONE-LINK APPS")
        val rx = Regex("(?i)(\\bbt|bluetooth|btmusic|music|radio|player|audio|carplay|zlink|tlink|autokit|carlink|a2dp|avrcp)")
        val apps = try {
            pm.getInstalledApplications(0)
        } catch (_: Throwable) {
            emptyList()
        }
        apps.filter { rx.containsMatchIn(it.packageName) || rx.containsMatchIn(pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.packageName }
            .forEach { ai ->
                val kind = if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) "system" else "installed"
                appendLine("  ${ai.packageName} (${pm.getApplicationLabel(ai)}, $kind)")
                if (Regex("(?i)(bt|bluetooth)").containsMatchIn(ai.packageName)) {
                    val services = try {
                        pm.getPackageInfo(ai.packageName, PackageManager.GET_SERVICES).services
                    } catch (_: Throwable) {
                        null
                    }
                    services?.filter { it.exported }?.take(12)?.forEach { appendLine("      service ${it.name}") }
                }
            }
        appendLine()

        appendLine("ONGOING NOTIFICATIONS (app · title · carries a player handle)")
        val listener = MediaListener.instance
        if (listener == null) {
            appendLine("  (notification access not connected)")
        } else {
            val list = try {
                listener.activeNotifications.toList()
            } catch (_: Throwable) {
                emptyList()
            }
            val ongoing = list.filter { (it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 }
            if (ongoing.isEmpty()) appendLine("  (none)")
            ongoing.take(25).forEach { sbn ->
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(40) ?: "-"
                val handle = if (MediaListener.mediaToken(sbn) != null) "yes" else "no"
                appendLine("  ${sbn.packageName} · $title · $handle")
            }
        }

        if (!deep) {
            appendLine()
            appendLine("(SAVE writes a longer file: unit identity and how the maker's Bluetooth apps are wired.)")
            return@buildString
        }
        appendLine()
        appendLine("UNIT IDENTITY")
        properties().forEach { appendLine("  $it") }
        appendLine()

        val home = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.filter { it != ctx.packageName }
        } catch (_: Throwable) {
            emptyList()
        }
        val wired = Regex("(?i)(microntek|\\bhct|bluetooth|btmusic|\\.bt\\b|a2dp|avrcp|\\.syu\\.|\\.fyt|topway|\\.wits\\.)")
        val targets = (apps.map { it.packageName }.filter { wired.containsMatchIn(it) } + home).distinct().take(14)
        appendLine("HOW THE MAKER'S APPS ARE WIRED (components, then signal names found in their code)")
        targets.forEach { pkg ->
            appendLine("  $pkg")
            components(ctx, pkg).forEach { appendLine("      $it") }
            val names = signalNames(pm, pkg)
            if (names.isNotEmpty()) appendLine("      names: " + names.joinToString(" "))
        }
        appendLine()

        appendLine("ALL SYSTEM APPS")
        apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }.map { it.packageName }.sorted()
            .chunked(4).forEach { appendLine("  " + it.joinToString("  ")) }
    }

    private fun properties(): List<String> {
        val keep = Regex("(?i)(mcu|mtc|hct|microntek|fota|ro\\.product\\.(model|brand|device|name|manufacturer)|ro\\.build\\.(display|description|fingerprint)|ro\\.board|ro\\.hardware|bluetooth|\\.bt\\.|goc|blueware|customer|oem|carplay|androidauto)")
        val skip = Regex("(?i)(serial|imei|mac|addr|wifi|ssid|uuid|passw|key)")
        return try {
            val p = Runtime.getRuntime().exec("getprop")
            val lines = p.inputStream.bufferedReader().readLines()
            p.destroy()
            lines.filter { keep.containsMatchIn(it) && !skip.containsMatchIn(it) }.map { it.take(160) }.take(70)
        } catch (_: Throwable) {
            listOf("(properties not readable)")
        }.ifEmpty { listOf("(nothing matched)") }
    }

    private fun components(ctx: Context, pkg: String): List<String> {
        val ns = "http://schemas.android.com/apk/res/android"
        val out = ArrayList<String>()
        try {
            val assets = ctx.createPackageContext(pkg, 0).assets
            for (cookie in 1..16) {
                val x = try { assets.openXmlResourceParser(cookie, "AndroidManifest.xml") } catch (_: Throwable) { continue }
                try {
                    var mine = false
                    var head: String? = null
                    val actions = ArrayList<String>()
                    fun flush() {
                        head?.let { if (actions.isNotEmpty() || " exported" in it) out += it + if (actions.isEmpty()) "" else " ← " + actions.joinToString(", ") }
                        head = null
                        actions.clear()
                    }
                    var ev = x.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (x.name) {
                                "manifest" -> mine = x.getAttributeValue(null, "package") == pkg
                                "activity", "activity-alias", "service", "receiver", "provider" -> if (mine) {
                                    flush()
                                    val name = x.getAttributeValue(ns, "name")?.removePrefix(pkg) ?: "?"
                                    val exported = x.getAttributeValue(ns, "exported") == "true"
                                    val perm = x.getAttributeValue(ns, "permission")
                                    head = "${x.name} $name" + (if (exported) " exported" else "") + (perm?.let { " needs $it" } ?: "")
                                }
                                "action" -> if (mine && head != null) x.getAttributeValue(ns, "name")?.let { actions += it }
                            }
                        }
                        if (!mine && ev == XmlPullParser.START_TAG && x.name != "manifest") break
                        ev = x.next()
                    }
                    flush()
                    if (mine) break
                } catch (_: Throwable) {
                } finally {
                    x.close()
                }
            }
        } catch (_: Throwable) {
        }
        return out.take(60)
    }

    private fun signalNames(pm: PackageManager, pkg: String): List<String> {
        val dotted = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}")
        val topic = Regex("(?i)(bt|blue|music|a2dp|avrcp|report|play|id3|media|audio|source|channel|irkey|bootcheck)")
        val ours = Regex("(?i)^(com\\.microntek|android\\.microntek|hct|com\\.hct|com\\.syu|com\\.fyt|com\\.ts\\.|com\\.wits)")
        val params = Regex("(av|sta|cfg|rpt|bt|music|a2dp)_[a-z0-9_]{2,30}=?")
        val found = sortedSetOf<String>()
        try {
            val apk = pm.getApplicationInfo(pkg, 0).sourceDir ?: return emptyList()
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) && it.size in 1..40_000_000L }.take(4).forEach { e ->
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (bytes.size < 0x70) return@forEach
                    val count = bb.getInt(0x38)
                    val table = bb.getInt(0x3C)
                    for (i in 0 until count) {
                        var pos = bb.getInt(table + i * 4)
                        var len = 0
                        var shift = 0
                        while (true) {
                            val b = bytes[pos++].toInt() and 0xFF
                            len = len or ((b and 0x7F) shl shift)
                            if (b and 0x80 == 0) break
                            shift += 7
                        }
                        if (len !in 5..70 || pos + len > bytes.size) continue
                        val text = String(bytes, pos, len, Charsets.ISO_8859_1)
                        if ((dotted.matches(text) && (ours.containsMatchIn(text) || (topic.containsMatchIn(text) && !text.startsWith("android.") && !text.startsWith("java") && !text.startsWith("kotlin")))) ||
                            params.matches(text)
                        ) {
                            found += text
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return found.take(260)
    }

    fun save(ctx: Context, text: String): String? {
        val name = "roadster-audio-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val stick = try {
            ctx.getExternalFilesDirs(null).drop(1).filterNotNull().firstOrNull {
                Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            }?.let { dir ->
                File(dir, name).writeText(text)
                "USB stick › Android/data/${ctx.packageName}/files/$name"
            }
        } catch (_: Throwable) {
            null
        }
        val download = saveToDownload(ctx, name, text)
        return listOfNotNull(stick, download).joinToString(" and ").ifEmpty { null }
    }

    private fun saveToDownload(ctx: Context, name: String, text: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
                "Download/$name"
            } else {
                val dir = ctx.getExternalFilesDir(null) ?: return null
                val f = File(dir, name)
                f.writeText(text)
                f.absolutePath
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"
}
