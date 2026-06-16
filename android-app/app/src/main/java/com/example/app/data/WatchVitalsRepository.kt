package com.example.app.data

import java.time.Duration
import java.time.Instant

object WatchVitalsRepository {

    private const val MOVING_THRESHOLD = 0.5f  // m/s^2 RMS — above this counts as moving
    // Motion must stay above the threshold this long before we report "Active".
    // A brief spike (a single arm movement) shouldn't flip the label off
    // "Resting"; turning the label back off is immediate.
    private const val SUSTAINED_MOTION_MS = 4_000L

    @Volatile
    private var motionAboveSince: Instant? = null

    @Volatile
    private var confirmedMoving = false

    @Volatile
    private var lastBpm: Int? = null

    @Volatile
    private var lastMotion: Float? = null

    @Volatile
    private var lastHrvMs: Float? = null

    @Volatile
    private var lastHrvSource: HrvSource = HrvSource.NONE

    @Volatile
    private var lastReceivedAt: Instant? = null

    @Volatile
    private var onBody: Boolean = true

    fun update(
        bpm: Int?,
        motion: Float? = null,
        hrvMs: Float? = null,
        onBody: Boolean = true,
        hrvSource: HrvSource = HrvSource.NONE,
    ) {
        this.onBody = onBody
        if (!onBody) {
            // Off wrist: whatever vitals we held are no longer the wearer's.
            lastBpm = null
            lastHrvMs = null
            lastHrvSource = HrvSource.NONE
        } else if (bpm != null) {
            lastBpm = bpm
            if (hrvMs != null) {
                lastHrvMs = hrvMs
                lastHrvSource = hrvSource
            }
        }
        // bpm == null && onBody: just re-worn, HR sensor still locking on —
        // keep vitals null but refresh the timestamp so the status is "live".
        if (motion != null) {
            lastMotion = motion
            updateMotionState(motion)
        }
        lastReceivedAt = Instant.now()
    }

    /** Debounce the "Active" label: only confirm movement once motion has stayed
     *  above the threshold for [SUSTAINED_MOTION_MS]; drop it immediately when
     *  motion falls back below. */
    private fun updateMotionState(motion: Float) {
        if (motion > MOVING_THRESHOLD) {
            val now = Instant.now()
            val since = motionAboveSince ?: now.also { motionAboveSince = it }
            if (Duration.between(since, now).toMillis() >= SUSTAINED_MOTION_MS) {
                confirmedMoving = true
            }
        } else {
            motionAboveSince = null
            confirmedMoving = false
        }
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
            hrvSource = if (freshHrv != null) lastHrvSource else HrvSource.NONE,
            isMoving = freshMotion != null && confirmedMoving,
            motionIntensity = freshMotion,
            hrSampleAgeMinutes = ageMin,
            hrSampleAgeSeconds = age?.seconds,
            // Only assert wrist state while the stream is fresh; a silent watch
            // means "unknown", not "off wrist".
            watchOnBody = if (ageMin != null && ageMin < 2L) onBody else null,
        )
    }
}
