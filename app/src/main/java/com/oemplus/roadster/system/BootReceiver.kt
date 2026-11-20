package com.oemplus.roadster.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.oemplus.roadster.Graph
import com.oemplus.roadster.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Graph.prefs.current.autostart) return
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) return
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        } catch (_: Throwable) {
        }
    }
}
