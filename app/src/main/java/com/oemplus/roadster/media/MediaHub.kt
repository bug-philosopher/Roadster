package com.oemplus.roadster.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.oemplus.roadster.apps.AppCatalog
import com.oemplus.roadster.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@Immutable
data class SessionView(
    val key: String,
    val pkg: String,
    val source: String,
    val appIcon: ImageBitmap?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: ImageBitmap?,
    val durationMs: Long,
    val state: Int,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val actions: Long,
    val shuffle: Int,
    val repeat: Int,
    val shuffleAction: String?,
    val repeatAction: String?,
    val upNext: String?,
) {
    val playing: Boolean
        get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING ||
            state == PlaybackState.STATE_SKIPPING_TO_NEXT || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS

    val usable: Boolean
        get() = state != PlaybackState.STATE_ERROR && !(state == PlaybackState.STATE_NONE && title == null)

    val canShuffle get() = shuffle >= 0 || shuffleAction != null
    val canRepeat get() = repeat >= 0 || repeatAction != null
    val canSeek get() = durationMs > 0 && supports(PlaybackState.ACTION_SEEK_TO)

    fun supports(action: Long) = actions == 0L || (actions and action) != 0L

    fun positionNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        var p = positionMs
        if (state == PlaybackState.STATE_PLAYING && positionAt > 0) {
            p += ((nowElapsed - positionAt) * speed).toLong()
        }
        p = max(0L, p)
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }
}

class MediaHub(
    private val ctx: Context,
    private val prefs: Prefs,
    private val apps: AppCatalog,
    private val scope: CoroutineScope,
) {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var pinned: MediaSession.Token? = null
    private var currentT: Tracked? = null
    private var registered = false

    // HCT units: Bluetooth music from the unit maker service.
    private val mtc = MtcBluetooth(ctx) { publish() }
    val mtcState: StateFlow<MtcState> get() = mtc.state
    private var pinnedMtc = false
    private var currentIsMtc = false

    var audioProbe: MtcBluetooth.AudioProbe?
        get() = mtc.audioProbe
        set(value) { mtc.audioProbe = value }

    fun describeMtc(): List<String> = mtc.describe()

    private val _access = MutableStateFlow(false)
    val access: StateFlow<Boolean> = _access.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionView>>(emptyList())
    val sessions: StateFlow<List<SessionView>> = _sessions.asStateFlow()
    private val _current = MutableStateFlow<SessionView?>(null)
    val current: StateFlow<SessionView?> = _current.asStateFlow()

    private var activeList: List<MediaController> = emptyList()
    private val extra = LinkedHashMap<String, MediaController>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        activeList = list ?: emptyList()
        rebuild()
    }

    fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun connect() {
        connectBluetooth()
        mtc.connect()
        val ok = hasAccess()
        _access.value = ok
        if (!ok) {
            if (registered) {
                try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
                registered = false
            }
            activeList = emptyList()
            rebuild()
            return
        }
        try {
            if (!registered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                registered = true
            }
            activeList = msm.getActiveSessions(component)
            rebuild()
        } catch (e: SecurityException) {
            _access.value = false
        }
    }

    private fun rebuild() {
        val merged = ArrayList<MediaController>(activeList)
        for (c in extra.values) {
            if (merged.none { it.sessionToken == c.sessionToken }) merged += c
        }
        onSessionsChanged(merged)
    }

    private fun onSessionsChanged(list: List<MediaController>) {
        val ordered = LinkedHashMap<MediaSession.Token, Tracked>()
        for (c in list) {
            val token = c.sessionToken
            if (c.packageName == ctx.packageName) continue
            ordered[token] = tracked.remove(token) ?: Tracked(c).also { it.attach() }
        }
        tracked.values.forEach { it.detach() }
        tracked.clear()
        tracked.putAll(ordered)
        if (pinned != null && pinned !in tracked) pinned = null
        publish()
    }

    private fun drop(t: Tracked) {
        if (tracked.remove(t.token) != null) t.detach()
        if (pinned == t.token) pinned = null
        publish()
    }

    private fun startedPlaying(t: Tracked) {
        pinned = t.token
        pinnedMtc = false
        if (!isBluetooth(t.pkg)) mtc.leaveSource()
        publish()
    }

    private fun publish() {
        val usable = tracked.values.filter { it.view.usable }
        val bt = mtcView(usable)
        _sessions.value = usable.map { it.view } + listOfNotNull(bt)
        val real = pinned?.let { tracked[it] }?.takeIf { it.view.usable }
            ?: usable.firstOrNull { it.view.state == PlaybackState.STATE_PLAYING }
        val lastWasBt = bt != null && prefs.current.lastSourcePkg == bt.pkg
        val onMtc = bt != null && (pinnedMtc || (real == null && (bt.playing || usable.isEmpty() || lastWasBt)))
        val cur = if (onMtc) null else real ?: usable.firstOrNull()
        currentT = cur
        currentIsMtc = onMtc
        _btReady.value = btTracked() != null || mtc.state.value.present
        _btPhone.value = when {
            btTracked() != null || mtc.state.value.connected -> true
            mtc.state.value.present && !mtc.state.value.ready -> null
            else -> false
        }
        _current.value = if (onMtc) bt else cur?.view ?: broadcastView
        val pkg = when {
            onMtc -> bt?.pkg?.takeIf { pinnedMtc || bt.playing }
            cur != null -> cur.pkg.takeIf { cur.view.playing || pinned == cur.token }
            else -> null
        }
        if (pkg != null && prefs.current.lastSourcePkg != pkg) prefs.update { it.copy(lastSourcePkg = pkg) }
    }

    private fun isBluetooth(pkg: String) = "bluetooth" in pkg.lowercase()

    private fun mtcView(usable: List<Tracked>): SessionView? {
        val s = mtc.state.value
        if (!s.ready || !s.connected) return null
        if (usable.any { isBluetooth(it.pkg) }) return null
        val app = if (apps.isInstalled(STOCK_BT_APP)) STOCK_BT_APP else MtcBluetooth.PKG
        return SessionView(
            key = MTC_KEY,
            pkg = app,
            source = "BT Audio",
            appIcon = apps.iconFor(app),
            title = s.title,
            artist = s.artist,
            album = s.album,
            art = null,
            durationMs = 0L,
            state = if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs = 0L,
            positionAt = 0L,
            speed = 1f,
            actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            shuffle = -1,
            repeat = -1,
            shuffleAction = null,
            repeatAction = null,
            upNext = null,
        )
    }

    fun select(key: String) {
        if (key == MTC_KEY) {
            pinnedMtc = true
            pinned = null
            publish()
            return
        }
        tracked.values.firstOrNull { it.key == key }?.let {
            pinned = it.token
            pinnedMtc = false
            publish()
        }
    }

    fun activate(key: String) {
        if (key == MTC_KEY) {
            startBluetooth()
            return
        }
        val t = tracked.values.firstOrNull { it.key == key } ?: return
        pinned = t.token
        pinnedMtc = false
        if (!isBluetooth(t.pkg)) mtc.leaveSource()
        publish()
        if (!t.view.playing) t.controller.transportControls.play()
    }

    fun playPause() {
        if (currentIsMtc && mtc.playPause()) return
        val t = currentT
        if (t == null) {
            if (!startBluetooth(fromPlayKey = true)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        val tc = t.controller.transportControls
        if (t.view.playing) tc.pause() else tc.play()
    }

    private val _btReady = MutableStateFlow(false)
    val btReady: StateFlow<Boolean> = _btReady.asStateFlow()

    private val _btPhone = MutableStateFlow<Boolean?>(false)
    val btPhone: StateFlow<Boolean?> = _btPhone.asStateFlow()

    private fun btTracked(): Tracked? {
        val token = extra[BT_KEY]?.sessionToken ?: return null
        return tracked[token]?.takeIf { it.view.state != PlaybackState.STATE_ERROR }
    }

    fun startBluetooth(fromPlayKey: Boolean = false): Boolean {
        val m = mtc.state.value
        if (m.present && (m.connected || !fromPlayKey) && btTracked() == null) {
            pinnedMtc = true
            pinned = null
            publish()
            mtc.enterSource()
            return true
        }
        val t = btTracked() ?: return false
        pinnedMtc = false
        pinned = t.token
        publish()
        t.controller.transportControls.play()
        return true
    }

    fun next() {
        if (currentIsMtc && mtc.next()) return
        val t = currentT ?: return mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        t.controller.transportControls.skipToNext()
    }

    fun previous() {
        if (currentIsMtc && mtc.previous()) return
        val t = currentT ?: return mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        t.controller.transportControls.skipToPrevious()
    }

    fun seekTo(ms: Long) { currentT?.controller?.transportControls?.seekTo(ms) }

    fun toggleShuffle() {
        val t = currentT ?: return
        val v = t.view
        val compat = t.compat
        if (v.shuffle >= 0 && compat != null) {
            compat.transportControls.setShuffleMode(
                if (v.shuffle == PlaybackStateCompat.SHUFFLE_MODE_NONE) PlaybackStateCompat.SHUFFLE_MODE_ALL
                else PlaybackStateCompat.SHUFFLE_MODE_NONE,
            )
        } else {
            v.shuffleAction?.let { t.controller.transportControls.sendCustomAction(it, null) }
        }
    }

    fun cycleRepeat() {
        val t = currentT ?: return
        val v = t.view
        val compat = t.compat
        if (v.repeat >= 0 && compat != null) {
            compat.transportControls.setRepeatMode(
                when (v.repeat) {
                    PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                    PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP ->
                        PlaybackStateCompat.REPEAT_MODE_ONE
                    else -> PlaybackStateCompat.REPEAT_MODE_NONE
                },
            )
        } else {
            v.repeatAction?.let { t.controller.transportControls.sendCustomAction(it, null) }
        }
    }

    private fun mediaKey(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        } catch (_: Throwable) {
        }
    }

    fun sessionActivity(key: String? = null): PendingIntent? {
        if (key == MTC_KEY) return null
        val t = key?.let { k -> tracked.values.firstOrNull { it.key == k } } ?: currentT
        return try { t?.controller?.sessionActivity } catch (_: Throwable) { null }
    }

    private inner class Tracked(val controller: MediaController) {
        val token: MediaSession.Token = controller.sessionToken
        val pkg: String = controller.packageName
        val key = "$pkg:${token.hashCode()}"
        val compat: MediaControllerCompat? = try {
            MediaControllerCompat(ctx, MediaSessionCompat.Token.fromToken(token))
        } catch (_: Throwable) {
            null
        }

        private var artSource: Any? = null
        private var artImage: ImageBitmap? = null
        private var artJob: Job? = null

        private val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val was = view.state
                refresh()
                if (view.state == PlaybackState.STATE_PLAYING && was != PlaybackState.STATE_PLAYING) {
                    startedPlaying(this@Tracked)
                }
            }
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = refresh()
            override fun onSessionDestroyed() = drop(this@Tracked)
        }

        private val compatCb = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(shuffleMode: Int) = refresh()
            override fun onRepeatModeChanged(repeatMode: Int) = refresh()
            override fun onSessionReady() = refresh()
        }

        var view: SessionView = build()
            private set

        fun attach() {
            try { controller.registerCallback(cb, main) } catch (_: Throwable) {}
            try { compat?.registerCallback(compatCb, main) } catch (_: Throwable) {}
            view = build()
        }

        fun detach() {
            try { controller.unregisterCallback(cb) } catch (_: Throwable) {}
            try { compat?.unregisterCallback(compatCb) } catch (_: Throwable) {}
            artJob?.cancel()
        }

        fun refresh() {
            if (tracked[token] !== this) return
            view = build()
            publish()
        }

        private fun build(): SessionView {
            val md = try { controller.metadata } catch (_: Throwable) { null }
            val ps = try { controller.playbackState } catch (_: Throwable) { null }
            val desc = md?.description
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: desc?.title?.toString()?.takeIf { it.isNotBlank() }
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
                ?: desc?.subtitle?.toString()?.takeIf { it.isNotBlank() }
            val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
                ?: desc?.description?.toString()?.takeIf { it.isNotBlank() && it != artist }
            val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

            val ready = try { compat?.isSessionReady == true } catch (_: Throwable) { false }
            val shuffle = if (ready) try { compat!!.shuffleMode } catch (_: Throwable) { -1 } else -1
            val repeat = if (ready) try { compat!!.repeatMode } catch (_: Throwable) { -1 } else -1

            val queue = try { controller.queue } catch (_: Throwable) { null }
            val activeId = ps?.activeQueueItemId ?: -1L
            val upNext = if (queue != null && activeId != -1L) {
                val i = queue.indexOfFirst { it.queueId == activeId }
                if (i >= 0) queue.getOrNull(i + 1)?.description?.title?.toString() else null
            } else null

            val label = apps.labelFor(pkg)
            return SessionView(
                key = key,
                pkg = pkg,
                source = sourceName(pkg, label),
                appIcon = apps.iconFor(pkg),
                title = title,
                artist = artist,
                album = album,
                art = resolveArt(md),
                durationMs = if (duration > 0) duration else 0L,
                state = ps?.state ?: PlaybackState.STATE_NONE,
                positionMs = ps?.position ?: 0L,
                positionAt = ps?.lastPositionUpdateTime ?: 0L,
                speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                actions = ps?.actions ?: 0L,
                shuffle = shuffle,
                repeat = repeat,
                shuffleAction = customAction(ps, "shuffle"),
                repeatAction = customAction(ps, "repeat"),
                upNext = upNext,
            )
        }

        private fun resolveArt(md: MediaMetadata?): ImageBitmap? {
            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: md?.description?.iconBitmap
            if (bmp != null && !bmp.isRecycled) {
                if (bmp === artSource) return artImage
                artSource = bmp
                artImage = try { scaleArt(bmp).asImageBitmap() } catch (_: Throwable) { null }
                return artImage
            }
            val uri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?: md?.description?.iconUri?.toString()
            if (uri != null) {
                if (uri == artSource) return artImage
                artSource = uri
                artImage = null
                artJob?.cancel()
                artJob = scope.launch {
                    val img = withContext(Dispatchers.IO) { loadUri(uri) }
                    if (artSource == uri && img != null) {
                        artImage = img
                        refresh()
                    }
                }
                return null
            }
            artSource = null
            artImage = null
            return null
        }
    }

    private fun customAction(ps: PlaybackState?, word: String): String? =
        ps?.customActions?.firstOrNull {
            it.action.contains(word, ignoreCase = true) || it.name?.toString()?.contains(word, ignoreCase = true) == true
        }?.action

    private fun loadUri(uri: String): ImageBitmap? = try {
        val u = Uri.parse(uri)
        when (u.scheme) {
            "content", "android.resource", "file" ->
                ctx.contentResolver.openInputStream(u)?.use { decodeSampled(it.readBytes()) }?.asImageBitmap()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= ART_PX) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return b?.let { scaleArt(it) }
    }

    private fun scaleArt(b: Bitmap): Bitmap {
        val m = max(b.width, b.height)
        if (m <= ART_PX) return b
        val f = ART_PX.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    data class BtLink(val service: String? = null, val status: String = "Not looked for yet", val attempts: Int = 0)

    private val _bt = MutableStateFlow(BtLink())
    val bt: StateFlow<BtLink> = _bt.asStateFlow()
    private var btBrowser: MediaBrowserCompat? = null
    private var btRetry: Runnable? = null

    // Android Bluetooth: connect to the media browser service.
    private fun connectBluetooth() {
        if (btBrowser != null || btRetry != null) return
        val comp = findBluetoothService()
        if (comp == null) {
            _bt.value = BtLink(null, "No Bluetooth media service found")
            return
        }
        _bt.value = BtLink(comp.flattenToShortString(), "Connecting…", _bt.value.attempts + 1)
        val browser = MediaBrowserCompat(ctx, comp, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val b = btBrowser ?: return
                try {
                    val platform = b.sessionToken.token as? MediaSession.Token
                    if (platform != null) {
                        extra[BT_KEY] = MediaController(ctx, platform)
                        rebuild()
                        _bt.value = _bt.value.copy(status = "Connected")
                    } else {
                        _bt.value = _bt.value.copy(status = "Connected, but it gave no session")
                    }
                } catch (t: Throwable) {
                    _bt.value = _bt.value.copy(status = "Connected, session error: ${t.javaClass.simpleName}")
                }
            }

            override fun onConnectionSuspended() {
                extra.remove(BT_KEY)
                rebuild()
                _bt.value = _bt.value.copy(status = "Disconnected; retrying")
                retryBluetooth()
            }

            override fun onConnectionFailed() {
                _bt.value = _bt.value.copy(status = "Refused the connection; retrying")
                retryBluetooth()
            }
        }, null)
        btBrowser = browser
        try {
            browser.connect()
        } catch (t: Throwable) {
            _bt.value = _bt.value.copy(status = "Couldn't connect: ${t.javaClass.simpleName}")
            retryBluetooth()
        }
    }

    fun reconnectBluetooth() {
        btRetry?.let { main.removeCallbacks(it) }
        btRetry = null
        try { btBrowser?.disconnect() } catch (_: Throwable) {}
        btBrowser = null
        if (extra.remove(BT_KEY) != null) rebuild()
        connectBluetooth()
    }

    private fun retryBluetooth() {
        try { btBrowser?.disconnect() } catch (_: Throwable) {}
        btBrowser = null
        if (btRetry != null) return
        val r = Runnable {
            btRetry = null
            connectBluetooth()
        }
        btRetry = r
        main.postDelayed(r, 20_000)
    }

    private fun findBluetoothService(): ComponentName? {
        val services = try {
            ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
        } catch (_: Throwable) {
            emptyList()
        }
        fun score(pkg: String, cls: String): Int {
            val p = pkg.lowercase()
            val c = cls.lowercase()
            return when {
                "avrcp" in c -> 3
                p == "com.android.bluetooth" || p == "com.google.android.bluetooth" -> 2
                "bluetooth" in p || "btmusic" in p || "bt" in p.split('.') -> 1
                else -> 0
            }
        }
        return services.map { it.serviceInfo }
            .filter { score(it.packageName, it.name) > 0 }
            .maxByOrNull { score(it.packageName, it.name) }
            ?.let { ComponentName(it.packageName, it.name) }
    }

    fun notificationSession(sbnKey: String, pkg: String, token: MediaSession.Token?) {
        val k = "notif:$sbnKey"
        if (token == null || pkg == ctx.packageName) {
            if (extra.remove(k) != null) rebuild()
            return
        }
        if (extra[k]?.sessionToken == token) return
        try {
            extra[k] = MediaController(ctx, token)
            rebuild()
        } catch (_: Throwable) {
        }
    }

    fun describe(): List<String> = tracked.values.map { t ->
        val via = when {
            extra[BT_KEY]?.sessionToken == t.token -> "bluetooth service"
            extra.entries.any { it.key.startsWith("notif:") && it.value.sessionToken == t.token } &&
                activeList.none { it.sessionToken == t.token } -> "notification"
            else -> "active session"
        }
        val v = t.view
        "${t.pkg} [$via] state=${stateName(v.state)} title=${v.title ?: "-"} artist=${v.artist ?: "-"} " +
            "art=${if (v.art != null) "yes" else "no"} actions=0x${java.lang.Long.toHexString(v.actions)}"
    }

    private fun stateName(s: Int) = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE -> "none"
        else -> s.toString()
    }

    private var broadcastView: SessionView? = null
    private var broadcastAt = 0L

    private val legacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onLegacyBroadcast(i)
    }

    init {
        mtc.otherAudio = { tracked.values.any { it.view.playing } }
        mtc.onStartedPlaying = {
            pinnedMtc = true
            pinned = null
            publish()
        }
        val filter = IntentFilter().apply { LEGACY_ACTIONS.forEach { addAction(it) } }
        try {
            ContextCompat.registerReceiver(ctx, legacyReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Throwable) {
        }
        scope.launch {
            while (true) {
                delay(60_000)
                if (expireBroadcast()) publish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun onLegacyBroadcast(i: Intent) {
        val ex = i.extras ?: return
        fun any(vararg keys: String): Any? = keys.firstNotNullOfOrNull { k -> try { ex.get(k) } catch (_: Throwable) { null } }
        fun text(vararg keys: String) = any(*keys)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val prev = broadcastView
        val title = text("track", "title") ?: prev?.title ?: return
        val same = prev?.title == title
        val playing = (any("playing", "isPlaying", "playstate") as? Boolean) ?: prev?.playing ?: true
        val position = (any("position") as? Number)?.toLong()
        val pkg = text("package", "packageName")
        val now = SystemClock.elapsedRealtime()
        broadcastView = SessionView(
            key = "broadcast",
            pkg = pkg ?: "",
            source = pkg?.let { sourceName(it, apps.labelFor(it)) } ?: "Audio",
            appIcon = pkg?.let { apps.iconFor(it) },
            title = title,
            artist = text("artist") ?: if (same) prev?.artist else null,
            album = text("album") ?: if (same) prev?.album else null,
            art = null,
            durationMs = (any("duration") as? Number)?.toLong()?.takeIf { it > 0 } ?: (if (same) prev?.durationMs else null) ?: 0L,
            state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs = position ?: if (same && prev != null) prev.positionNow(now) else 0L,
            positionAt = now,
            speed = 1f,
            actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            shuffle = -1,
            repeat = -1,
            shuffleAction = null,
            repeatAction = null,
            upNext = null,
        )
        broadcastAt = System.currentTimeMillis()
        publish()
    }

    private fun expireBroadcast(): Boolean {
        val v = broadcastView ?: return false
        val age = System.currentTimeMillis() - broadcastAt
        val stale = age > if (v.playing) 60 * 60_000L else 15 * 60_000L
        if (stale) broadcastView = null
        return stale
    }

    companion object {
        private const val ART_PX = 520
        private const val BT_KEY = "bluetooth-service"
        const val MTC_KEY = "mtc-bluetooth"
        private const val STOCK_BT_APP = "com.microntek.bluetooth"

        private val LEGACY_ACTIONS = listOf(
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete",
            "com.htc.music.metachanged",
            "com.sec.android.app.music.metachanged",
            "com.sec.android.app.music.playstatechanged",
            "com.samsung.sec.android.MusicPlayer.metachanged",
            "com.miui.player.metachanged",
            "com.sonyericsson.music.metachanged",
            "com.andrew.apollo.metachanged",
        )

        fun sourceName(pkg: String, label: String): String {
            val p = pkg.lowercase()
            val l = label.lowercase()
            return when {
                "bluetooth" in l || "btmusic" in p || "bt music" in l || "a2dp" in p || p.endsWith(".bt") ||
                    ("bluetooth" in p && "music" in p) -> "BT Audio"
                else -> label
            }
        }
    }
}
