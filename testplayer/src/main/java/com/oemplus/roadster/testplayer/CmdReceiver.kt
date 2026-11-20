package com.oemplus.roadster.testplayer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout

class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Player.init(ctx)
        when (intent.getStringExtra("cmd")) {
            "play" -> Player.play()
            "pause" -> Player.pause()
            "toggle" -> Player.toggle()
            "next" -> Player.skip(1)
            "prev" -> Player.skip(-1)
            "seek" -> Player.seek(intent.getStringExtra("ms")?.toLongOrNull() ?: 0L)
            "nav" -> Player.postNav(
                ctx,
                intent.getStringExtra("title") ?: "0.3 mi",
                intent.getStringExtra("text") ?: "Turn right onto Maple Ave",
                intent.getStringExtra("sub"),
            )
            "navoff" -> Player.clearNav(ctx)
            "hide" -> Player.hide(broadcast = false)
            "hidebcast" -> Player.hide(broadcast = true)
            "show" -> Player.show()
            "noart" -> Player.setArt(false)
            "art" -> Player.setArt(true)
        }
    }
}

class ButtonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Player.init(ctx)
        @Suppress("DEPRECATION")
        val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (ev.action != KeyEvent.ACTION_DOWN) return
        Log.i(Player.TAG, "media button ${KeyEvent.keyCodeToString(ev.keyCode)} via receiver")
        when (ev.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> Player.skip(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Player.skip(-1)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Player.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> Player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Player.pause()
        }
    }
}

class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun b(label: String, f: () -> Unit) = col.addView(Button(this).apply { text = label; setOnClickListener { f() } })
        b("Play / pause") { Player.toggle() }
        b("Next") { Player.skip(1) }
        b("Turn right in 0.3 mi") { Player.postNav(this, "0.3 mi", "Turn right onto Maple Ave", "14:03 ETA · 3.6 mi · 12 min") }
        b("Clear navigation") { Player.clearNav(this) }
        setContentView(col)
    }
}
