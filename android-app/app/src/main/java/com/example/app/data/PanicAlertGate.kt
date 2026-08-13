package com.example.app.data

/**
 * Shared cooldown for panic alerts. Both detection paths (the background
 * MonitorService poll and the in-app HeartRateViewModel check) call [tryFire]
 * before alerting, so a sustained episode produces one alert per cooldown
 * window instead of one per poll. In-process singleton, like the other stores.
 */
object PanicAlertGate {
    private var lastAlertAtMs = 0L

    /** True (and starts a new cooldown window) when enough time has passed
     *  since the last alert; false while the cooldown is still running. */
    @Synchronized
    fun tryFire(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cooldownMs = SettingsStore.panicCooldownMinutes.value * 60_000L
        if (nowMs - lastAlertAtMs < cooldownMs) return false
        lastAlertAtMs = nowMs
        return true
    }
}
