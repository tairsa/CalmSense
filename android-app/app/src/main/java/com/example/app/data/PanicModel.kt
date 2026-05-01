package com.example.app.data

import kotlin.math.exp

/**
 * Logistic-regression panic-attack detector.
 *
 * The 5-element [weights] array matches the contract returned by the
 * CalmSense backend's GET /api/v1/sensor-data:
 *
 *     weights[0] = w_hr
 *     weights[1] = w_hrv
 *     weights[2] = w_motion
 *     weights[3] = reserved (currently 0.0)
 *     weights[4] = bias
 *
 * Decision rule:
 *     z       = w_hr*hr + w_hrv*hrv + w_motion*motion + bias
 *     p_panic = 1 / (1 + exp(-z))
 *     is_panic = p_panic > threshold
 *
 * Inference is intentionally on-device: it must work offline (panic attacks
 * happen in places without signal) and respond in milliseconds. The server's
 * job is to *train* this model and ship the weights — the actual decision
 * stays on the phone.
 */
data class PanicModel(
    val weights: DoubleArray,
    val source: String,         // "trained_global" | "default"
    val modelType: String?,     // e.g. "logistic_regression"
    val trainedAt: String?,     // ISO 8601 from the server
    val testAccuracy: Double?,  // training-time test accuracy
) {
    init {
        require(weights.size == 5) { "weights must have 5 elements, got ${weights.size}" }
    }

    /** Return (probability of panic, decision at threshold 0.5). */
    fun predict(hr: Double, hrv: Double, motion: Double): Prediction {
        val z = weights[0] * hr +
                weights[1] * hrv +
                weights[2] * motion +
                weights[4]   // weights[3] is reserved/unused
        val p = 1.0 / (1.0 + exp(-z))
        return Prediction(probability = p, isPanic = p > 0.5)
    }

    /** True if every coefficient is zero — i.e. the server returned defaults. */
    fun isUntrained(): Boolean =
        weights.all { it == 0.0 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PanicModel) return false
        return weights.contentEquals(other.weights) &&
                source == other.source &&
                modelType == other.modelType &&
                trainedAt == other.trainedAt &&
                testAccuracy == other.testAccuracy
    }

    override fun hashCode(): Int {
        var result = weights.contentHashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (modelType?.hashCode() ?: 0)
        result = 31 * result + (trainedAt?.hashCode() ?: 0)
        result = 31 * result + (testAccuracy?.hashCode() ?: 0)
        return result
    }
}

data class Prediction(val probability: Double, val isPanic: Boolean)
