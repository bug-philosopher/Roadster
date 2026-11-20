package com.oemplus.roadster

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.oemplus.roadster.location.TripService
import com.oemplus.roadster.media.MediaListener
import com.oemplus.roadster.core.resolve
import com.oemplus.roadster.ui.DayPalette
import com.oemplus.roadster.ui.Host
import com.oemplus.roadster.ui.NightPalette
import com.oemplus.roadster.ui.Pal
import com.oemplus.roadster.ui.RoadsterRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), Host {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onAccessChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        Pal.palette = if (Graph.prefs.current.dimmer.resolve(Graph.sun.state.value.night).first) NightPalette else DayPalette
        setContent { RoadsterRoot(this) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.prefs.settings.map { it.fullscreen }.distinctUntilChanged().collect { applyBars(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Graph.gps.acquire("ui")
        TripService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        onAccessChanged()
    }

    override fun onStop() {
        Graph.gps.release("ui")
        Graph.trips.save()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars(Graph.prefs.current.fullscreen)
    }

    private fun onAccessChanged() {
        val before = Graph.access.current
        val now = Graph.access.refresh()
        Graph.media.connect()
        Graph.gps.refresh()
        if (now.microphone != before.microphone) Graph.analyzer.restart()
        if (now.location != before.location) TripService.sync(this)
    }

    private fun applyBars(full: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun requestLocation() {
        val ask = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(ask.toTypedArray())
    }

    override fun requestMicrophone() {
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun openNotificationAccess() {
        val component = ComponentName(this, MediaListener::class.java).flattenToString()
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 30) {
                add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component))
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        startFirst(tries)
    }

    override fun openOverlayPermission() {
        startFirst(listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), Intent(Settings.ACTION_SETTINGS)))
    }

    override fun openHomeSettings() {
        startFirst(listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    }

    override fun setHomeAlias(on: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.HomeAlias"),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    override fun launchApp(pkg: String): Boolean {
        val i = Graph.apps.launchIntent(pkg) ?: return false
        return try {
            startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun openAppInfo(pkg: String) {
        startFirst(listOf(Graph.apps.appInfoIntent(pkg)))
    }

    override fun sendPendingIntent(pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pi.send(this, 0, null, null, null, null, opts)
        } else {
            pi.send()
        }
        true
    } catch (_: Throwable) {
        false
    }

    override fun toBackground() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (ri?.activityInfo?.packageName != packageName) moveTaskToBack(true)
    }

    private fun startFirst(intents: List<Intent>) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
