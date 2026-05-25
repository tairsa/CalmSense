package com.example.app.data

data class Vitals(
    val heartRateBpm: Int?,
    val hrv: Double?,
    val isMoving: Boolean,
    val motionIntensity: Float? = null,
    val hrSampleAgeMinutes: Long? = null,
)
