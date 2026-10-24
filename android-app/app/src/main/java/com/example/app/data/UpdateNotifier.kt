package com.example.app.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.app.MainActivity
import com.example.app.R

/**
 * Checks GitHub for a newer release at most once a day and, when there is one,
 * posts a notification that opens Settings > App updates. Called by MonitorService
 * (hourly, so the day is caught even when the app stays closed) and on app launch.
 */
object UpdateNotifier {
    const val ACTION_OPEN_UPDATE = "com.example.app.action.OPEN_UPDATE"
    private const val CHANNEL_ID = "UPDATES_CHANNEL_ID"
    private const val NOTIFICATION_ID = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    suspend fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check_ms", 0L) < DAY_MS) return
        // Offline or GitHub down: the day is not marked, so the next call retries.
        val release = runCatching { AppUpdater.check() }.getOrElse { return }
        prefs.edit().putLong("last_check_ms", now).apply()
        if (release != null) notify(context, release)
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled(), which lint cannot see through
    private fun notify(context: Context, release: AppUpdater.Release) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_title),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_UPDATE
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_available, release.version))
            .setContentText(context.getString(R.string.notif_update_text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}
