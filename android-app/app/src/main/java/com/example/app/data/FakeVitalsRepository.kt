package com.example.app.data

import kotlin.random.Random

class FakeVitalsRepository {

    enum class Mode { BASELINE, STRESS, EXERCISE }

    @Volatile
    private var mode: Mode = Mode.BASELINE

    fun setMode(newMode: Mode) {
        mode = newMode
    }

    // Simulated HRV is clean by construction, so it never triggers the
    // bpm-derived fallback notice.
    suspend fun readVitals(): Vitals = when (mode) {
        Mode.BASELINE -> Vitals(
            heartRateBpm = 70 + Random.nextInt(-5, 6),
            hrv = 45.0 + Random.nextDouble(-5.0, 5.0),
            isMoving = false,
            hrvSource = HrvSource.REAL_IBI,
        )
        Mode.STRESS -> Vitals(
            heartRateBpm = 135 + Random.nextInt(-5, 11),
            hrv = 15.0 + Random.nextDouble(-3.0, 3.0),
            isMoving = false,
            hrvSource = HrvSource.REAL_IBI,
        )
        Mode.EXERCISE -> Vitals(
            heartRateBpm = 140 + Random.nextInt(-10, 11),
            hrv = 25.0 + Random.nextDouble(-3.0, 3.0),
            isMoving = true,
            hrvSource = HrvSource.REAL_IBI,
        )
    }
}
