package br.com.paineltv.novotv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class AppKeepAliveService : Service() {

    companion object {
        private const val RELAUNCH_COOLDOWN_MS = 90_000L
        private const val ACTIVITY_STALE_MS = 180_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("painel_tv_novo", Context.MODE_PRIVATE) }
    private var lastLaunchAttemptAt = 0L

    private val monitorRunnable = object : Runnable {
        override fun run() {
            checkActivityHealth()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(9001, buildNotification())
        handler.post(monitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkActivityHealth() {
        val now = SystemClock.elapsedRealtime()
        val lastSeen = prefs.getLong("activity_last_seen", 0L)
        val foreground = prefs.getBoolean("activity_foreground", false)
        val shutdownRequested = prefs.getBoolean("shutdown_requested", false)
        val maintenanceMode = prefs.getBoolean("maintenance_mode", false)
        if (maintenanceMode) return
        if (shutdownRequested) return
        if (now - lastLaunchAttemptAt < RELAUNCH_COOLDOWN_MS) return
        if (foreground && lastSeen > 0L && now - lastSeen < ACTIVITY_STALE_MS) return

        val stale = lastSeen <= 0L || now - lastSeen > ACTIVITY_STALE_MS
        if (stale) {
            lastLaunchAttemptAt = now
            launchMainActivity()
        }
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "painel_tv_keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Painel TV",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Mantem o Painel TV ativo em segundo plano."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Painel TV ativo")
            .setContentText("Monitoramento de estabilidade em execucao.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
