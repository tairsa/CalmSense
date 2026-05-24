package com.example.app.data

import java.time.Duration
import java.time.Instant

object WatchVitalsRepository {

    @Volatile
    private var lastBpm: Int? = null

    @Volatile
    private var lastReceivedAt: Instant? = null

    fun update(bpm: Int) {
        lastBpm = bpm
        lastReceivedAt = Instant.now()
    }

    fun readVitals(): Vitals {
        val now = Instant.now()
        val received = lastReceivedAt
        val bpm = lastBpm
        val ageMin = received?.let { Duration.between(it, now).toMinutes() }
        // Watch sends a sample every ~5–30 s under passive monitoring; treat anything fresher than
        // 2 minutes as "current."
        val freshBpm = if (bpm != null && ageMin != null && ageMin < 2L) bpm else null
        return Vitals(
            heartRateBpm = freshBpm,
            hrv = null,
            isMoving = false,
            hrSampleAgeMinutes = ageMin,
        )
    }
}
