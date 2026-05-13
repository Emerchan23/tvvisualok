package br.com.paineltv.novotv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("painel_tv_novo", Context.MODE_PRIVATE)
        val maintenanceMode = prefs.getBoolean("maintenance_mode", false)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            prefs.edit()
                .putBoolean("maintenance_mode", false)
                .putBoolean("shutdown_requested", false)
                .apply()
        } else if (maintenanceMode) {
            return
        }
        try {
            val serviceIntent = Intent(context, AppKeepAliveService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Throwable) {
        }
        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
