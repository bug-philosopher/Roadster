package com.oemplus.roadster.testplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

object Player {
    private data class Track(val title: String, val artist: String, val album: String, val durMs: Long, val hue: Int, val roots: IntArray)

    private val tracks = listOf(
        Track("真夜中のドア〜Stay With Me", "松原みき", "Pocket Park", 214_000, 0xD9823B, intArrayOf(53, 52, 50, 48)),
        Track("Plastic Love", "Mariya Takeuchi", "Variety", 289_000, 0xC94F6D, intArrayOf(50, 55, 52, 57)),
        Track("Ride on Time", "Tatsuro Yamashita", "Ride on Time", 263_000, 0x3E8FB0, intArrayOf(55, 52, 57, 50)),
        Track("Test tone with a deliberately long title to check the scrolling title display", "Roadster Test Player", "Sample metadata", 95_000, 0x7A9A4A, intArrayOf(48, 53, 55, 50)),
    )

    private lateinit var app: Context
    private lateinit var session: MediaSessionCompat
    private val main = Handler(Looper.getMainLooper())
    private var index = 0
    @Volatile private var playing = false
    @Volatile private var frames = 0L
    private var shuffle = PlaybackStateCompat.SHUFFLE_MODE_NONE
    private var repeat = PlaybackStateCompat.REPEAT_MODE_NONE
    private var thread: Thread? = null
    private const val RATE = 44_100
    const val TAG = "TestPlayer"

    fun init(ctx: Context) {
        if (::session.isInitialized) return
        app = ctx.applicationContext
        session = MediaSessionCompat(app, "RoadsterTestPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() {
                    if (posMs() > 3000) seek(0) else skip(-1)
                }
                override fun onSeekTo(pos: Long) = seek(pos)
                override fun onSetShuffleMode(shuffleMode: Int) {
                    shuffle = shuffleMode
                    session.setShuffleMode(shuffleMode)
                }
                override fun onSetRepeatMode(repeatMode: Int) {
                    repeat = repeatMode
                    session.setRepeatMode(repeatMode)
                }
            })
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    app, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(app, ButtonReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setQueue(tracks.mapIndexed { i, t ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder().setMediaId("t$i").setTitle(t.title).setSubtitle(t.artist).build(), i.toLong(),
                )
            })
            isActive = true
        }
        publishMeta()
        publishState()
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (playing && posMs() >= tracks[index].durMs) {
                if (repeat == PlaybackStateCompat.REPEAT_MODE_ONE) seek(0) else skip(1)
            }
            main.postDelayed(this, 1000)
        }
    }

    fun posMs() = frames * 1000 / RATE

    @Volatile var legacy = false

    @Volatile var withArt = true

    fun setArt(on: Boolean) {
        withArt = on
        publishMeta()
        publishState()
    }

    fun hide(broadcast: Boolean) {
        session.isActive = false
        legacy = broadcast
        if (broadcast) announce()
        Log.i(TAG, "session hidden, broadcasts=$broadcast")
    }

    fun show() {
        legacy = false
        session.isActive = true
        publishMeta()
        publishState()
        Log.i(TAG, "session visible")
    }

    private fun announce() {
        val t = tracks[index]
        app.sendBroadcast(
            Intent("com.android.music.metachanged")
                .putExtra("track", t.title).putExtra("artist", t.artist).putExtra("album", t.album)
                .putExtra("playing", playing).putExtra("duration", t.durMs).putExtra("position", posMs()),
        )
    }

    fun play() {
        if (playing) return
        playing = true
        if (thread == null) {
            thread = Thread(::render, "synth").also { it.start() }
        }
        publishState()
    }

    fun pause() {
        playing = false
        publishState()
    }

    fun toggle() = if (playing) pause() else play()

    fun skip(d: Int) {
        Log.i(TAG, "skip $d")
        index = if (shuffle != PlaybackStateCompat.SHUFFLE_MODE_NONE && d > 0) {
            (index + 1 + Random.nextInt(tracks.size - 1)) % tracks.size
        } else {
            (index + d + tracks.size) % tracks.size
        }
        frames = 0
        publishMeta()
        publishState()
    }

    fun seek(ms: Long) {
        frames = ms.coerceIn(0, tracks[index].durMs) * RATE / 1000
        publishState()
    }

    private fun publishState() {
        val st = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                        PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(st, posMs(), 1f, SystemClock.elapsedRealtime())
                .setActiveQueueItemId(index.toLong())
                .build(),
        )
        if (legacy) announce()
    }

    private fun publishMeta() {
        val t = tracks[index]
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, t.durMs)
                .apply { if (withArt) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art(t)) }
                .build(),
        )
    }

    private fun art(t: Track): Bitmap {
        val s = 512
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val hue = t.hue
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), intArrayOf(0xFF1B1A2E.toInt(), (0xFF000000 or hue.toLong()).toInt(), 0xFFF2D0A0.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = null
        p.color = Color.argb(200, 255, 230, 190)
        c.drawCircle(s * 0.68f, s * 0.58f, s * 0.16f, p)
        p.color = Color.argb(255, 20, 18, 26)
        for (i in 0 until 7) c.drawRect(0f, s * (0.70f + i * 0.045f), s.toFloat(), s * (0.70f + i * 0.045f + 0.02f), p)
        p.color = Color.WHITE
        p.textSize = 34f
        p.isFakeBoldText = true
        c.drawText("TEST", 28f, 60f, p)
        return b
    }

    private fun render() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192))
            .build()
        track.play()
        val n = 1024
        val buf = ShortArray(n * 2)
        val rnd = Random(3)
        var hatState = 0.0
        while (true) {
            if (!playing) {
                buf.fill(0)
                track.write(buf, 0, buf.size)
                continue
            }
            val roots = tracks[index].roots
            val bpm = 104.0
            for (i in 0 until n) {
                val t = (frames + i).toDouble() / RATE
                val beatF = t * bpm / 60.0
                val beat = beatF.toInt()
                val inBeat = beatF - beat
                val bar = beat / 4
                val root = roots[bar % roots.size]
                fun hz(midi: Int) = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                var pad = 0.0
                for (iv in intArrayOf(0, 4, 7, 11)) {
                    val f = hz(root + 12 + iv)
                    pad += sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)
                }
                pad *= 0.035
                val bassPhase = when {
                    beat % 4 == 0 -> inBeat
                    beat % 4 == 1 && inBeat >= 0.5 -> inBeat - 0.5
                    beat % 4 == 2 -> inBeat
                    else -> 9.0
                }
                val bf = hz(root - 12)
                val bass = if (bassPhase < 1.0) (sin(2 * PI * bf * t) + 0.5 * sin(4 * PI * bf * t) + 0.25 * sin(6 * PI * bf * t)) * 0.22 * exp(-bassPhase * 2.5) else 0.0
                val kick = if (beat % 2 == 0) {
                    val kt = inBeat * 60.0 / bpm
                    sin(2 * PI * (45 + 90 * exp(-kt * 30)) * kt) * 0.55 * exp(-kt * 9)
                } else 0.0
                val snare = if (beat % 2 == 1) {
                    val st = inBeat * 60.0 / bpm
                    ((rnd.nextDouble() - 0.5) * 0.8 + 0.3 * sin(2 * PI * 185 * st)) * 0.32 * exp(-st * 18)
                } else 0.0
                val eighth = (beatF * 2) - (beatF * 2).toInt()
                val noise = rnd.nextDouble() - 0.5
                val hp = noise - hatState
                hatState = noise
                val hat = hp * 0.16 * exp(-eighth * 60.0 / bpm * 2 * 45)
                val step = (beatF * 4).toInt()
                val arpNote = root + 24 + intArrayOf(0, 7, 11, 14, 16, 14, 11, 7)[step % 8]
                val sixteenth = beatF * 4 - step
                val lead = sin(2 * PI * hz(arpNote) * t + 0.4 * sin(2 * PI * 5 * t)) * 0.07 * exp(-sixteenth * 3)
                val l = tanh((pad + bass + kick + snare + hat * 0.8 + lead * 1.1) * 1.2)
                val r = tanh((pad + bass + kick + snare + hat * 1.2 + lead * 0.9) * 1.2)
                buf[2 * i] = (l * 26000).toInt().toShort()
                buf[2 * i + 1] = (r * 26000).toInt().toShort()
            }
            frames += n
            track.write(buf, 0, buf.size)
        }
    }

    fun postNav(ctx: Context, title: String, text: String, sub: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, "nav")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setLargeIcon(arrowBitmap())
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (sub != null) setSubText(sub) }
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .build()
        nm.notify(42, n)
    }

    fun clearNav(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(42)
    }

    private fun arrowBitmap(): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 14f }
        val path = Path().apply { moveTo(40f, 120f); lineTo(40f, 60f); quadTo(40f, 40f, 60f, 40f); lineTo(90f, 40f) }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(86f, 16f); lineTo(122f, 40f); lineTo(86f, 64f); close() }, p)
        return b
    }
}
