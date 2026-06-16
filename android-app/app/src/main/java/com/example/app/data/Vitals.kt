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
    // How [hrv] was derived. Defaults to NONE; each source sets it. (Kept last
    // so positional Vitals(hr, hrv, isMoving) constructions stay valid.)
    val hrvSource: HrvSource = HrvSource.NONE,
)

/**
 * Motion feature fed to [PanicModel.predict].
 *
 * The model is trained on motion RMS in roughly [0,1] (see ml/generate_data.py),
 * and the watch reports RMS in the same m/s² units, so we pass it straight
 * through, clamped to the trained range. Falls back to a coarse binary only when
 * no motion sample is available. This replaces the old always-binary 0.7/0.05
 * encoding, which discarded the real intensity — making any still moment with a
 * high heart rate look like panic and inflating false positives.
 */
fun motionFeatureFor(motionIntensity: Float?, isMoving: Boolean): Double =
    motionIntensity?.toDouble()?.coerceIn(0.0, 1.0) ?: if (isMoving) 0.7 else 0.05

fun Vitals.motionFeature(): Double = motionFeatureFor(motionIntensity, isMoving)
