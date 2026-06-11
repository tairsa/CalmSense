package com.example.app.data

data class Vitals(
    val heartRateBpm: Int?,
    val hrv: Double?,
    val isMoving: Boolean,
    val motionIntensity: Float? = null,
    val hrSampleAgeMinutes: Long? = null,
    val hrSampleAgeSeconds: Long? = null,
    // True/false from the watch's off-body detector; null = unknown (no fresh
    // watch data, or the source doesn't report wear state).
    val watchOnBody: Boolean? = null,
)
