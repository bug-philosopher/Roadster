package com.oemplus.roadster.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.oemplus.roadster.BuildConfig
import dalvik.system.PathClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Immutable
data class MtcState(
    val present: Boolean = false,
    val bound: Boolean = false,
    val ready: Boolean = false,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

class MtcBluetooth(private val ctx: Context, private val onChange: () -> Unit) {
    interface AudioProbe {
        fun hold()
        fun release()
        fun hears(): Boolean?
    }

    var audioProbe: AudioProbe? = null
    var onStartedPlaying: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mtc-bt").apply { isDaemon = true } }
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val memory = ctx.getSharedPreferences("mtc", Context.MODE_PRIVATE)
    var otherAudio: (() -> Boolean)? = null

    private val _state = MutableStateFlow(MtcState(present = installed()))
    val state: StateFlow<MtcState> = _state.asStateFlow()

    @Volatile private var binder: IBinder? = null
    @Volatile private var codes: Codes? = null
    private var binding = false
    private var registered = false
    private var enteredChannel = false
    private var polling = false

    @Volatile private var boundTo: String? = null
    @Volatile private var codesFrom = "not worked out yet"
    @Volatile private var rawBt: Int? = null
    @Volatile private var rawAv: Int? = null
    @Volatile private var rawInfo: String? = null
    private val notes = ArrayDeque<String>()
    private val reports = ArrayDeque<String>()
    @Volatile private var reportCount = 0

    class Codes(
        val name: String,
        val getBTState: Int,
        val getAVState: Int,
        val getMusicInfo: Int,
        val avPlay: Int,
        val avPlayPause: Int,
        val avPlayPrev: Int,
        val avPlayNext: Int,
        val musicUnmute: Int,
    )

    private fun installed(): Boolean = try {
        ctx.packageManager.getApplicationInfo(PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun connect() {
        val here = installed()
        if (here != _state.value.present) update { it.copy(present = here) }
        if (!here) return
        if (!registered) {
            val filter = IntentFilter().apply { WATCHED.forEach { addAction(it) } }
            try {
                ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            } catch (_: Throwable) {
            }
        }
        if (binder != null || binding) return
        for (cls in listOf("$PKG.BlueToothService", "$PKG.BTSerialService")) {
            val ok = try {
                ctx.bindService(Intent().setComponent(ComponentName(PKG, cls)), connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                note("bind ${cls.substringAfterLast('.')}: ${t.javaClass.simpleName}")
                false
            }
            if (ok) {
                binding = true
                return
            }
            try { ctx.unbindService(connection) } catch (_: Throwable) {}
        }
        note("service present but it won't accept a connection")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binding = false
            binder = service
            boundTo = name.className.substringAfterLast('.')
            note("connected to $boundTo")
            update { it.copy(bound = true) }
            io.execute {
                workOutCodes(service)
                poll()
            }
            if (!polling) {
                polling = true
                main.postDelayed(tick, POLL_MS)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            note("service went away")
            update { MtcState(present = true) }
        }

        override fun onBindingDied(name: ComponentName) {
            binder = null
            binding = false
            try { ctx.unbindService(this) } catch (_: Throwable) {}
            note("connection died; will reconnect")
            update { MtcState(present = true) }
            main.postDelayed({ connect() }, 5_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (binder != null) io.execute { poll() }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            remember(i)
            if (binder != null) io.execute { poll() }
        }
    }

    private fun workOutCodes(b: IBinder) {
        val skipClass = BuildConfig.DEBUG && File(ctx.filesDir, "mtc_noreflect").exists()
        val fromClass = if (skipClass) null else reflectCodes()
        val c = fromClass ?: probeCodes(b)
        codes = c
        codesFrom = when {
            fromClass != null -> "the unit's own interface class"
            c != null -> "reply shapes (${c.name})"
            else -> "undetermined, so nothing is sent"
        }
        note("numbering: $codesFrom")
        update { it.copy(ready = c != null) }
    }

    // Read the transaction codes from the unit.
    private fun reflectCodes(): Codes? {
        val loaders = ArrayList<ClassLoader>()
        try {
            val apk = ctx.packageManager.getApplicationInfo(PKG, 0).sourceDir
            loaders += PathClassLoader(apk, ClassLoader.getSystemClassLoader())
        } catch (_: Throwable) {
        }
        javaClass.classLoader?.let { loaders += it }
        for (loader in loaders) {
            val found = HashMap<String, Int>()
            try {
                val stub = Class.forName("$DESCRIPTOR\$Stub", false, loader)
                for (f in stub.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
                    if (!f.name.startsWith("TRANSACTION_")) continue
                    f.isAccessible = true
                    found[f.name.removePrefix("TRANSACTION_")] = f.getInt(null)
                }
            } catch (_: Throwable) {
            }
            val c = Codes(
                "unit",
                found["getBTState"] ?: 0,
                found["getAVState"] ?: 0,
                found["getMusicInfo"] ?: 0,
                found["avPlay"] ?: 0,
                found["avPlayPause"] ?: 0,
                found["avPlayPrev"] ?: 0,
                found["avPlayNext"] ?: 0,
                found["musicUnmute"] ?: 0,
            )
            if (c.getBTState != 0 && c.getAVState != 0 && c.avPlayPause != 0) return c
        }
        return null
    }

    // Find the firmware version with read-only calls. If not sure, send nothing.
    private fun probeCodes(b: IBinder): Codes? {
        val p8 = shape(b, 8)
        return when {
            p8.text -> {
                val p47 = shape(b, 47)
                note("replies: 8=$p8 47=$p47")
                when {
                    p47.text -> API2
                    !p47.ok -> API1
                    else -> null
                }
            }
            p8.flag -> {
                val p57 = shape(b, 57)
                val p59 = shape(b, 59)
                note("replies: 8=$p8 57=$p57 59=$p59")
                when {
                    p57.text && p59.ok -> API4
                    p57.number && !p59.ok -> API3
                    else -> null
                }
            }
            else -> {
                note("replies: 8=$p8")
                null
            }
        }
    }

    private class Shape(val ok: Boolean, val bytes: Int, val int: Int?) {
        val text get() = ok && (bytes >= 8 || (bytes == 4 && int == -1))
        val flag get() = ok && bytes == 4 && (int == 0 || int == 1)
        val number get() = ok && bytes == 4 && (int ?: -1) >= 0
        override fun toString() = if (!ok) "none" else "${bytes}b" + (int?.let { "/$it" } ?: "")
    }

    private fun shape(b: IBinder, code: Int): Shape {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESCRIPTOR)
            if (!b.transact(code, d, r, 0)) return Shape(false, 0, null)
            r.readException()
            val bytes = r.dataAvail()
            Shape(true, bytes, if (bytes >= 4) r.readInt() else null)
        } catch (_: Throwable) {
            Shape(false, -1, null)
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun <T> call(code: Int, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        if (code <= 0) return null
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESCRIPTOR)
            if (!b.transact(code, d, r, 0)) {
                note("call $code isn't known to the service")
                null
            } else {
                r.readException()
                read(r)
            }
        } catch (t: Throwable) {
            note("call $code failed: ${t.javaClass.simpleName}")
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun send(code: Int) {
        call(code) { }
    }

    private fun poll() {
        val c = codes ?: return
        if (binder == null) return
        val bt = call(c.getBTState) { it.readByte().toInt() }
        val av = call(c.getAVState) { it.readByte().toInt() }
        val info = if (c.getMusicInfo != 0) call(c.getMusicInfo) { it.readString() } else null
        rawBt = bt
        rawAv = av
        rawInfo = info
        val parsed = MtcMusicInfo.parse(info)
        val connected = (bt ?: 0) >= 1 || (av ?: 0) >= 1
        val playing = (av ?: 0) >= 2
        main.post {
            val was = _state.value
            val now = was.copy(
                bound = binder != null,
                connected = connected,
                playing = playing && connected,
                title = parsed.title.takeIf { connected },
                artist = parsed.artist.takeIf { connected },
                album = parsed.album.takeIf { connected },
            )
            if (now != was) {
                _state.value = now
                if (now.playing && !was.playing && !pressedRecently()) onStartedPlaying?.invoke()
                onChange()
            }
            if (!memory.getBoolean(KEY_THROUGH_ANDROID, false)) {
                heardRuns = if (now.playing && audioProbe?.hears() == true && otherAudio?.invoke() != true) heardRuns + 1 else 0
                if (heardRuns >= 5) {
                    memory.edit().putBoolean(KEY_THROUGH_ANDROID, true).apply()
                    note("heard the phone's music in Android's mixer")
                }
            }
        }
    }

    private var pressedAt = 0L
    private var heardRuns = 0
    private fun pressedRecently() = System.currentTimeMillis() - pressedAt < 4_000

    private fun press(pick: (Codes) -> Int): Boolean {
        val code = codes?.let(pick) ?: 0
        if (code == 0 || binder == null) return false
        pressedAt = System.currentTimeMillis()
        io.execute {
            send(code)
            poll()
        }
        main.postDelayed({ io.execute { poll() } }, 600)
        main.postDelayed({ io.execute { poll() } }, 1_500)
        return true
    }

    fun playPause(): Boolean = press { it.avPlayPause }
    fun next(): Boolean = press { it.avPlayNext }
    fun previous(): Boolean = press { it.avPlayPrev }

    // Set Bluetooth as the audio source.
    fun enterSource() {
        if (!_state.value.present) return
        pressedAt = System.currentTimeMillis()
        val c = codes
        val wasPlaying = _state.value.playing
        note("BT Audio pressed")
        if (c != null && binder != null) {
            io.execute {
                if (c.musicUnmute != 0) send(c.musicUnmute)
                when {
                    c.avPlay != 0 -> send(c.avPlay)
                    !wasPlaying -> send(c.avPlayPause)
                }
                poll()
            }
        }
        val probe = audioProbe
        probe?.hold()
        val knownNeeded = memory.getBoolean(KEY_CHANNEL_NEEDED, false)
        val patience = if (memory.getBoolean(KEY_THROUGH_ANDROID, false)) 6_000 else 4_000
        var waited = 1_600
        val listen = object : Runnable {
            override fun run() {
                val hears = probe?.hears()
                when {
                    hears == true -> {
                        note("music is coming through Android's mixer; audio channel left alone")
                        probe?.release()
                    }
                    hears == false && waited < patience && !knownNeeded -> {
                        waited += 400
                        main.postDelayed(this, 400)
                    }
                    else -> enterChannel(probe, hears)
                }
            }
        }
        main.postDelayed(listen, if (knownNeeded) 300L else 1_600L)
    }

    private fun enterChannel(probe: AudioProbe?, heard: Boolean?) {
        try {
            audio.setParameters("av_channel_enter=$CHANNEL")
            enteredChannel = true
            note("entered the unit's Bluetooth audio channel (mixer ${if (heard == null) "can't be heard" else "was silent"})")
        } catch (t: Throwable) {
            note("channel switch failed: ${t.javaClass.simpleName}")
        }
        io.execute { poll() }
        if (probe == null) return
        main.postDelayed({
            if (probe.hears() == true && memory.getBoolean(KEY_THROUGH_ANDROID, false)) {
                memory.edit().putBoolean(KEY_CHANNEL_NEEDED, true).apply()
                note("music arrived with the channel; this unit needs it")
            }
            probe.release()
        }, 5_000)
    }

    fun leaveSource() {
        if (!enteredChannel) return
        enteredChannel = false
        try {
            audio.setParameters("av_channel_exit=$CHANNEL")
            note("left the Bluetooth audio channel")
        } catch (_: Throwable) {
        }
    }

    private fun update(f: (MtcState) -> MtcState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val now = f(_state.value)
            if (now != _state.value) {
                _state.value = now
                onChange()
            }
        } else {
            main.post { update(f) }
        }
    }

    private fun note(text: String) {
        synchronized(notes) {
            if (notes.peekLast()?.substringAfter("  ") == text) return
            notes.addLast(clock() + "  " + text)
            while (notes.size > 24) notes.removeFirst()
        }
    }

    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun remember(i: Intent) {
        reportCount++
        val extras = i.extras
        val body = extras?.keySet()?.sorted()?.joinToString(" ") { k ->
            val v = try { extras.get(k) } catch (_: Throwable) { null }
            "$k=${mask(k, v)}"
        } ?: ""
        synchronized(reports) {
            reports.addLast("${clock()}  ${i.action?.removePrefix("com.microntek.")}  $body")
            while (reports.size > 14) reports.removeFirst()
        }
    }

    // Hide addresses and numbers.
    private fun mask(key: String, v: Any?): String {
        val s = when (v) {
            null -> return "null"
            is ByteArray -> return "bytes[${v.size}]"
            is Array<*> -> return "array[${v.size}]"
            else -> v.toString()
        }
        val k = key.lowercase()
        return when {
            listOf("mac", "addr", "number", "num", "phone", "name", "uuid", "contact", "pin", "pass").any { it in k } ->
                "<hidden ${s.length}>"
            Regex("[0-9A-Fa-f]{12}|([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|\\+?[0-9]{7,}").containsMatchIn(s) -> "<hidden ${s.length}>"
            else -> visible(s).take(60)
        }
    }

    private fun visible(s: String): String = buildString {
        for (ch in s) {
            if (ch < ' ' || ch == '\u007F' || ch == '\u00FF' || ch == '\uFFFD') append('<').append(Integer.toHexString(ch.code)).append('>')
            else append(ch)
        }
    }

    fun describe(): List<String> {
        val s = _state.value
        if (!s.present) return listOf("not an HCT / Microntek unit ($PKG isn't installed)")
        val out = ArrayList<String>()
        val files = try {
            ctx.packageManager.getInstalledApplications(0)
                .filter { "microntek" in it.packageName || it.packageName.startsWith("com.hct") }
                .joinToString(", ") { "${it.packageName} [${File(it.sourceDir ?: "").name}]" }
        } catch (_: Throwable) {
            ""
        }
        out += "service: ${if (s.bound) "connected to $boundTo" else "not connected"} · numbering from $codesFrom"
        codes?.let {
            out += "calls: state ${it.getBTState}/${it.getAVState} info ${it.getMusicInfo} play ${it.avPlay} " +
                "pause ${it.avPlayPause} prev ${it.avPlayPrev} next ${it.avPlayNext} unmute ${it.musicUnmute}"
        }
        out += "phone link state=${rawBt ?: "-"} · music state=${rawAv ?: "-"} · read as: " +
            "${if (s.connected) "connected" else "no phone"}, ${if (s.playing) "playing" else "not playing"}"
        out += "track info as sent: ${rawInfo?.let { "\"" + visible(it).take(300) + "\"" } ?: "(nothing)"}"
        out += "track info as read: title=${s.title ?: "-"} · artist=${s.artist ?: "-"} · album=${s.album ?: "-"}"
        val channel = listOf("av_channel=", "av_channel").firstNotNullOfOrNull { k ->
            try { audio.getParameters(k)?.substringAfter("=", "")?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
        }
        val mcu = try { audio.getParameters("sta_mcu_version=")?.substringAfter("=", "")?.trim() } catch (_: Throwable) { null }
        out += "audio channel now: ${channel ?: "(unit doesn't say)"} · Roadster entered BT channel: ${if (enteredChannel) "yes" else "no"}" +
            " · MCU ${mcu?.takeIf { it.isNotEmpty() } ?: "?"}"
        out += "learned: music through Android's mixer=${memory.getBoolean(KEY_THROUGH_ANDROID, false)} · " +
            "needs the BT channel=${memory.getBoolean(KEY_CHANNEL_NEEDED, false)}"
        out += "maker's apps: $files"
        synchronized(notes) { if (notes.isNotEmpty()) { out += "log:"; notes.forEach { out += "  $it" } } }
        synchronized(reports) {
            out += "broadcasts from the unit: $reportCount" + if (reports.isEmpty()) "" else " (latest last)"
            reports.forEach { out += "  $it" }
        }
        return out
    }

    companion object {
        const val PKG = "android.microntek.mtcser"
        private const val DESCRIPTOR = "android.microntek.mtcser.BTServiceInf"
        private const val CHANNEL = "gsm_bt"
        private const val POLL_MS = 2_000L
        private const val KEY_THROUGH_ANDROID = "through_android"
        private const val KEY_CHANNEL_NEEDED = "channel_needed"

        private val WATCHED = listOf(
            "com.microntek.bt.report",
            "com.microntek.btmusic.report",
            "com.microntek.bootcheck",
        )

        private val API4 = Codes("generation 4", 2, 3, 57, 14, 15, 17, 18, 51)
        private val API3 = Codes("generation 3", 2, 3, 56, 0, 14, 16, 17, 50)
        private val API2 = Codes("generation 2", 2, 3, 47, 0, 9, 11, 12, 41)
        private val API1 = Codes("generation 1", 2, 3, 0, 0, 9, 11, 12, 39)
    }
}
