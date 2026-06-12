package com.example.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.app.data.SettingsStore

/**
 * Turns monitoring off and on, including timed snoozes ("off for 2 hours").
 * A timed snooze schedules an AlarmManager wake-up that restarts the
 * MonitorService; SettingsStore.init() resumes lapsed snoozes as a backstop
 * for alarms lost to a reboot.
 */
object MonitoringSnooze {

    /** [durationMs] null means off until manually turned back on. */
    fun turnOff(context: Context, durationMs: Long?) {
        val until = durationMs?.let { System.currentTimeMillis() + it } ?: 0L
        SettingsStore.setMonitoringEnabled(false, until)
        context.stopService(Intent(context, MonitorService::class.java))

        val alarms = context.getSystemService(AlarmManager::class.java)
        if (until > 0L) {
            // Inexact while-idle alarm: no exact-alarm permission needed, and
            // a few minutes of drift doesn't matter at these durations.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, resumeIntent(context))
        } else {
            alarms.cancel(resumeIntent(context))
        }
    }

    fun turnOn(context: Context) {
        SettingsStore.setMonitoringEnabled(true)
        context.getSystemService(AlarmManager::class.java).cancel(resumeIntent(context))
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }

    private fun resumeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MonitoringResumeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class MonitoringResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SettingsStore.init(context)
        SettingsStore.setMonitoringEnabled(true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
            // Background FGS start can be rejected; monitoring is re-enabled
            // either way, so the service comes back on next app launch.
            Log.w("MonitoringResume", "Could not restart monitor service from background", e)
        }
    }
}
