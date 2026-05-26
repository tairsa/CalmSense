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
    private var lastReceivedAt: Instant? = null

    fun update(bpm: Int, motion: Float? = null) {
        lastBpm = bpm
        if (motion != null) lastMotion = motion
        lastReceivedAt = Instant.now()
    }

    fun readVitals(): Vitals {
        val now = Instant.now()
        val received = lastReceivedAt
        val bpm = lastBpm
        val motion = lastMotion
        val ageMin = received?.let { Duration.between(it, now).toMinutes() }
        // Watch streams a sample every ~2 s while the foreground service is alive; treat
        // anything fresher than 2 minutes as "current."
        val freshBpm = if (bpm != null && ageMin != null && ageMin < 2L) bpm else null
        val freshMotion = if (motion != null && ageMin != null && ageMin < 2L) motion else null
        return Vitals(
            heartRateBpm = freshBpm,
            hrv = null,
            isMoving = freshMotion != null && freshMotion > MOVING_THRESHOLD,
            motionIntensity = freshMotion,
            hrSampleAgeMinutes = ageMin,
        )
    }
}
