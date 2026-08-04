package com.example.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.app.data.SettingsStore

/**
 * Brings monitoring back after the two events that silently stop it.
 *
 *  - **Reboot.** MonitorService returns START_STICKY, but that only covers the
 *    system killing the process; nothing restarts a service across a reboot.
 *  - **App update / sideload.** Replacing the package stops running services and
 *    does *not* restart them, START_STICKY included. This is not hypothetical:
 *    the phone stopped uploading sensor data on 2026-06-19, three days after the
 *    app was reinstalled, and nobody noticed for six weeks because the UI still
 *    worked and only the background upload was dead.
 *
 * Before this receiver existed, either event meant monitoring stayed off until
 * somebody happened to open the app.
 *
 * Deliberately distinct from [MonitoringResumeReceiver], which force-enables
 * monitoring because it only fires when a timed snooze expires. Boot must not
 * do that: if the user turned monitoring off, it stays off. We start only when
 * consent is granted AND monitoring is enabled — SettingsStore.init() has
 * already resumed any snooze that lapsed while the device was powered down.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        SettingsStore.init(context)
        if (!SettingsStore.consentGranted.value) {
            Log.i(TAG, "$action: consent not granted, staying off")
            return
        }
        if (!SettingsStore.monitoringEnabled.value) {
            Log.i(TAG, "$action: monitoring disabled by the user, staying off")
            return
        }

        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            Log.i(TAG, "$action: monitoring restarted")
        } catch (e: Exception) {
            // Background foreground-service starts can still be refused. Both
            // BOOT_COMPLETED and MY_PACKAGE_REPLACED are on the platform's
            // exemption list, so this should not normally fire — but if it does,
            // the setting is untouched and the service starts on next app launch.
            Log.w(TAG, "$action: could not start MonitorService from background", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
