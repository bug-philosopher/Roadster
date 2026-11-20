package com.oemplus.roadster.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.oemplus.roadster.Graph
import com.oemplus.roadster.MainActivity
import com.oemplus.roadster.R

class TripService : Service() {
    private var holding = false

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        Graph.gps.acquire(TAG)
        holding = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        if (holding) Graph.gps.release(TAG)
        Graph.trips.save()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Trip computer", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "Shown while Roadster counts trip distance with GPS"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_roadster)
            .setContentTitle("Trip computer on")
            .setContentText("Counting distance with GPS")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        return try {
            ServiceCompat.startForeground(
                this, NOTE_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "trip"
        private const val CHANNEL = "trip"
        private const val NOTE_ID = 7

        fun sync(ctx: Context) {
            val want = Graph.prefs.current.tripTracking && Graph.gps.hasPermission()
            val i = Intent(ctx, TripService::class.java)
            try {
                if (want) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Throwable) {
            }
        }
    }
}
