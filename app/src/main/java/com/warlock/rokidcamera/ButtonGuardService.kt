package com.warlock.rokidcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

class ButtonGuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var claimed = false
    private var beatAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!claimed) return
            if (SystemClock.elapsedRealtime() - beatAt > 6500L) {
                releaseAndStop()
            } else {
                handler.postDelayed(this, 2000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = "warlock_btn"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(ch, "Warlock Button", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        startForeground(
            22,
            n.setContentTitle("Warlock Camera")
                .setContentText("Кнопка очков в этом приложении")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLAIM, ACTION_BEAT -> {
                beatAt = SystemClock.elapsedRealtime()
                if (!claimed || intent.action == ACTION_CLAIM) {
                    claimed = true
                    RokidKeys.claim(this)
                }
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, 2000L)
            }
            else -> releaseAndStop()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        releaseAndStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (claimed) RokidKeys.restore(this)
        claimed = false
        super.onDestroy()
    }

    private fun releaseAndStop() {
        handler.removeCallbacks(watchdog)
        if (claimed) RokidKeys.restore(this)
        claimed = false
        stopForeground(true)
        stopSelf()
    }

    companion object {
        const val ACTION_CLAIM = "com.warlock.rokidcamera.CLAIM"
        const val ACTION_BEAT = "com.warlock.rokidcamera.BEAT"
        const val ACTION_RELEASE = "com.warlock.rokidcamera.RELEASE"
    }
}
