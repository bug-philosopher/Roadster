package com.oemplus.roadster.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Access(
    val notifications: Boolean = false,
    val location: Boolean = false,
    val microphone: Boolean = false,
    val overlay: Boolean = false,
    val postNotifications: Boolean = true,
)

class AccessHub(private val ctx: Context) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Access> = _state.asStateFlow()
    val current: Access get() = _state.value

    fun refresh(): Access {
        val a = read()
        _state.value = a
        return a
    }

    private fun read() = Access(
        notifications = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
        location = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        overlay = android.provider.Settings.canDrawOverlays(ctx),
        postNotifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
    )

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}
