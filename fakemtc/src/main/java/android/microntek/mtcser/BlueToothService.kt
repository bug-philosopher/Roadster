package android.microntek.mtcser

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "FakeMtc"
private const val DESCRIPTOR = "android.microntek.mtcser.BTServiceInf"

object Fake {
    var api = 4
    var connected = true
    var playing = false
    var muted = true
    var track = 0
    var format = "ff"
    var path = "module"

    val tracks = listOf(
        Triple("Plastic Love", "Mariya Takeuchi", "Variety"),
        Triple("Stay With Me", "Miki Matsubara", "Pocket Park"),
        Triple("Ride on Time", "Tatsuro Yamashita", "Ride on Time"),
    )

    fun info(): String {
        val (t, a, al) = tracks[track]
        return when (format) {
            "nl" -> "$t\n$a\n$al"
            "kv" -> "title=$t, artist=$a, album=$al, duration=291000"
            "json" -> """{"title":"$t","artist":"$a","album":"$al","duration":291000}"""
            else -> "$t\u00FF$a\u00FF291000\u00FF${track + 1}\u00FF${tracks.size}\u00FF$al"
        }
    }

    fun name(code: Int): String? = when (api) {
        4 -> when (code) {
            1 -> "init"; 2 -> "getBTState"; 3 -> "getAVState"; 8 -> "IsConferenceCalling"; 12 -> "getNowDevName"
            14 -> "avPlay"; 15 -> "avPlayPause"; 16 -> "avPlayStop"; 17 -> "avPlayPrev"; 18 -> "avPlayNext"
            41 -> "deleteBT"; 49 -> "deleteHistoryAll"; 50 -> "musicMute"; 51 -> "musicUnmute"; 56 -> "reDial"
            57 -> "getMusicInfo"; 58 -> "getOBDstate"; 59 -> "requestBtInfo"
            else -> if (code in 1..59) "other$code" else null
        }
        3 -> when (code) {
            1 -> "init"; 2 -> "getBTState"; 3 -> "getAVState"; 8 -> "IsConferenceCalling"; 12 -> "getNowDevName"
            14 -> "avPlayPause"; 15 -> "avPlayStop"; 16 -> "avPlayPrev"; 17 -> "avPlayNext"
            40 -> "deleteBT"; 48 -> "deleteHistoryAll"; 49 -> "musicMute"; 50 -> "musicUnmute"; 55 -> "reDial"
            56 -> "getMusicInfo"; 57 -> "getOBDstate"; 58 -> "requestBtInfo"
            else -> if (code in 1..58) "other$code" else null
        }
        else -> when (code) {
            1 -> "init"; 2 -> "getBTState"; 3 -> "getAVState"; 8 -> "getNowDevName"
            9 -> "avPlayPause"; 10 -> "avPlayStop"; 11 -> "avPlayPrev"; 12 -> "avPlayNext"
            31 -> "deleteBT"; 39 -> "deleteHistoryAll"; 40 -> "musicMute"; 41 -> "musicUnmute"; 46 -> "reDial"
            47 -> "getMusicInfo"; 48 -> "getOBDstate"; 49 -> "requestBtInfo"
            else -> if (code in 1..49) "other$code" else null
        }
    }
}

class BlueToothService : Service() {
    private val binder = object : BTServiceInf.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val name = Fake.name(code) ?: return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            reply ?: return true
            reply.writeNoException()
            when (name) {
                "getBTState" -> reply.writeByte(if (Fake.connected) 1 else 0)
                "getAVState" -> reply.writeByte(if (!Fake.connected) 0 else if (Fake.playing) 2 else 1)
                "getMusicInfo" -> reply.writeString(if (Fake.connected) Fake.info() else "")
                "getNowDevName" -> reply.writeString(if (Fake.connected) "Test Phone" else null)
                "IsConferenceCalling" -> reply.writeInt(0)
                "getOBDstate" -> reply.writeInt(0)
                "requestBtInfo" -> Unit
                "avPlay" -> act(name) { Fake.playing = true }
                "avPlayPause" -> act(name) { Fake.playing = !Fake.playing }
                "avPlayStop" -> act(name) { Fake.playing = false }
                "avPlayNext" -> act(name) { Fake.track = (Fake.track + 1) % Fake.tracks.size }
                "avPlayPrev" -> act(name) { Fake.track = (Fake.track + Fake.tracks.size - 1) % Fake.tracks.size }
                "musicMute" -> act(name) { Fake.muted = true }
                "musicUnmute" -> act(name) { Fake.muted = false }
                else -> Log.e(TAG, "UNEXPECTED call $name (code $code)")
            }
            return true
        }
    }

    private fun act(name: String, change: () -> Unit) {
        Log.i(TAG, "call $name")
        change()
        report(this)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service up, generation ${Fake.api}")
        thread(isDaemon = true, name = "fake-a2dp") { tone() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun tone() {
        val rate = 44_100
        val n = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(n * 2)
            .build()
        val buf = ShortArray(2048)
        var t = 0L
        val rnd = Random(3)
        track.play()
        while (true) {
            val on = Fake.path == "android" && Fake.connected && Fake.playing && !Fake.muted
            for (i in buf.indices) {
                val s = t / rate.toDouble()
                val beat = exp(-((s * 2) % 1.0) * 5.0)
                val v = if (!on) 0.0 else
                    0.30 * sin(2 * PI * 110 * s) * beat + 0.18 * sin(2 * PI * 440 * s) + 0.12 * sin(2 * PI * 1760 * s) +
                        0.08 * sin(2 * PI * 5200 * s) + (rnd.nextDouble() - 0.5) * 0.10
                buf[i] = (v * 20000).toInt().toShort()
                t++
            }
            track.write(buf, 0, buf.size)
        }
    }
}

fun report(ctx: Context) {
    ctx.sendBroadcast(
        Intent("com.microntek.bt.report")
            .putExtra("connect_state", if (Fake.connected) 1 else 0)
            .putExtra("connected_mac", "AA:BB:CC:DD:EE:FF")
            .putExtra("phone_mac", "AABBCCDDEEFF")
            .putExtra("spp_state", 0),
    )
}

class Cmd : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val cmd = i.getStringExtra("cmd") ?: "") {
            "connect" -> Fake.connected = true
            "disconnect" -> { Fake.connected = false; Fake.playing = false }
            "play" -> Fake.playing = true
            "pause" -> Fake.playing = false
            "next" -> Fake.track = (Fake.track + 1) % Fake.tracks.size
            "api4", "api3", "api2" -> Fake.api = cmd.last().digitToInt()
            "ff", "nl", "kv", "json" -> Fake.format = cmd
            "android", "module" -> Fake.path = cmd
            "mute" -> Fake.muted = true
        }
        Log.i(TAG, "cmd ${i.getStringExtra("cmd")}: api=${Fake.api} connected=${Fake.connected} playing=${Fake.playing} muted=${Fake.muted} fmt=${Fake.format} path=${Fake.path}")
        report(c)
    }
}
