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

/** Upper clamp on motion, m/s²; MOTION_MAX in ml/generate_data.py. */
const val MOTION_MAX = 10.0

/**
 * Motion feature fed to [PanicModel.predict], and the value uploaded with
 * sensor rows and feedback so the server retrains on exactly what the phone
 * predicts with.
 *
 * Units are the watch's own: wrist linear-acceleration RMS over ~3 s in m/s²,
 * which the model is trained in (ml/generate_data.py). It used to be clamped to
 * [0,1] for a model trained on an abstract 0-1 scale, which turned ordinary
 * arm movement (~1 m/s² at a normal heart rate) into "exercise" and vetoed
 * any panic. Without a sample (simulation, Health Connect), [isMoving] picks a
 * walking-pace or a still value.
 */
fun motionFeatureFor(motionIntensity: Float?, isMoving: Boolean): Double =
    motionIntensity?.toDouble()?.coerceIn(0.0, MOTION_MAX) ?: if (isMoving) 3.0 else 0.1

fun Vitals.motionFeature(): Double = motionFeatureFor(motionIntensity, isMoving)
