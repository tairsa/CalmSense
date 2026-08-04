package com.example.app.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts [HrMonitoringService] after a reboot or an app update.
 *
 * Until now the sensor stream only ever started from [WearMainActivity], so the
 * watch had to be picked up and the app opened by hand before the phone saw any
 * heart rate. A reboot, or an `adb install -r`, left the watch looking perfectly
 * normal while sending nothing at all — the failure is completely silent from
 * the wearer's side, and the phone simply shows no data.
 *
 * MY_PACKAGE_REPLACED matters as much as BOOT_COMPLETED here: replacing the
 * package stops the foreground service without restarting it, which is exactly
 * what happened after each sideload during development.
 *
 * Note the watch keeps no persisted on/off flag — the service runs whenever the
 * app has been started, so this restores that same state. If a persisted "user
 * stopped monitoring" switch is ever added, gate this on it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        try {
            HrMonitoringService.start(context)
            Log.i(TAG, "$action: HR monitoring restarted")
        } catch (e: Exception) {
            // Both actions are exempt from the background foreground-service
            // restrictions, so this is a backstop rather than an expected path.
            Log.w(TAG, "$action: could not start HrMonitoringService", e)
        }
    }

    private companion object {
        const val TAG = "WearBootReceiver"
    }
}
