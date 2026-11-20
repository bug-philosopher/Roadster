package com.oemplus.roadster.media

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.oemplus.roadster.Graph

class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        Graph.access.refresh()
        Graph.media.connect()
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Throwable) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Graph.nav.clearAll()
        try {
            requestRebind(ComponentName(this, MediaListener::class.java))
        } catch (_: Throwable) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Graph.nav.removed(sbn.key)
        Graph.media.notificationSession(sbn.key, sbn.packageName, null)
    }

    private fun handle(sbn: StatusBarNotification) {
        mediaToken(sbn)?.let { Graph.media.notificationSession(sbn.key, sbn.packageName, it) }
        if (!NavParser.isNavSource(sbn.packageName, Graph.prefs.current.naviPkg)) return
        val info = try {
            NavParser.parse(this, sbn, Graph.apps.labelFor(sbn.packageName))
        } catch (_: Throwable) {
            null
        } ?: return
        Graph.nav.post(info)
    }

    companion object {
        @Volatile
        var instance: MediaListener? = null
            private set

        @Suppress("DEPRECATION")
        fun mediaToken(sbn: StatusBarNotification): MediaSession.Token? = try {
            sbn.notification?.extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        } catch (_: Throwable) {
            null
        }
    }
}
