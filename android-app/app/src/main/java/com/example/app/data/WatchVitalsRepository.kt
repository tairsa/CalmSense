package com.example.app.data

import java.time.Duration
import java.time.Instant

object WatchVitalsRepository {

    private const val MOVING_THRESHOLD = 0.5f  // m/s^2 RMS — above this counts as moving

    @Volatile
    private var lastBpm: Int? = null

    @Volatile
    private var lastMotion: Float? = null

    @Volatile
    private var lastHrvMs: Float? = null

    @Volatile
    private var lastReceivedAt: Instant? = null

    @Volatile
    private var onBody: Boolean = true

    fun update(bpm: Int?, motion: Float? = null, hrvMs: Float? = null, onBody: Boolean = true) {
        this.onBody = onBody
        if (!onBody) {
            // Off wrist: whatever vitals we held are no longer the wearer's.
            lastBpm = null
            lastHrvMs = null
        } else if (bpm != null) {
            lastBpm = bpm
            if (hrvMs != null) lastHrvMs = hrvMs
        }
        // bpm == null && onBody: just re-worn, HR sensor still locking on —
        // keep vitals null but refresh the timestamp so the status is "live".
        if (motion != null) lastMotion = motion
        lastReceivedAt = Instant.now()
    }

    fun readVitals(): Vitals {
        val now = Instant.now()
        val received = lastReceivedAt
        val bpm = lastBpm
        val motion = lastMotion
        val hrv = lastHrvMs
        val age = received?.let { Duration.between(it, now) }
        val ageMin = age?.toMinutes()
        // Watch streams a sample every ~2 s while the foreground service is alive; treat
        // anything fresher than 2 minutes as "current."
        val freshBpm = if (bpm != null && ageMin != null && ageMin < 2L) bpm else null
        val freshMotion = if (motion != null && ageMin != null && ageMin < 2L) motion else null
        val freshHrv = if (hrv != null && ageMin != null && ageMin < 2L) hrv.toDouble() else null
        return Vitals(
            heartRateBpm = freshBpm,
            hrv = freshHrv,
            isMoving = freshMotion != null && freshMotion > MOVING_THRESHOLD,
            motionIntensity = freshMotion,
            hrSampleAgeMinutes = ageMin,
            hrSampleAgeSeconds = age?.seconds,
            // Only assert wrist state while the stream is fresh; a silent watch
            // means "unknown", not "off wrist".
            watchOnBody = if (ageMin != null && ageMin < 2L) onBody else null,
        )
    }
}
