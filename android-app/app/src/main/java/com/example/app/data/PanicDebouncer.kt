package com.example.app.data

import java.time.Duration
import java.time.Instant

/**
 * Suppresses single-sample panic detections: a raw positive must persist for
 * [sustainMillis] before [confirm] returns true. A negative reading clears the
 * streak immediately. Panic attacks last minutes, so a short confirmation delay
 * costs little, while a lone noisy HR/HRV sample no longer fires an alert.
 *
 * Each detection path (foreground HeartRateViewModel, background MonitorService)
 * holds its own instance, so the streak measures sustained-ness within that
 * path's own polling cadence rather than mixing the two.
 */
class PanicDebouncer(private val sustainMillis: Long = DEFAULT_SUSTAIN_MS) {
    @Volatile
    private var positiveSince: Instant? = null

    /** Feed one raw model/rule decision; returns true once positives have been
     *  continuous for [sustainMillis]. */
    fun confirm(rawPositive: Boolean, now: Instant = Instant.now()): Boolean {
        if (!rawPositive) {
            positiveSince = null
            return false
        }
        val since = positiveSince ?: now.also { positiveSince = it }
        return Duration.between(since, now).toMillis() >= sustainMillis
    }

    fun reset() {
        positiveSince = null
    }

    companion object {
        // Foreground polls every 1 s; background every 5 s while elevated. 20 s
        // filters transient spikes at both cadences without meaningfully
        // delaying a real (minutes-long) episode.
        const val DEFAULT_SUSTAIN_MS = 20_000L
    }
}
