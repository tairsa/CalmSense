package com.example.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * One HRV source's resting baseline: a time-weighted mean of ln(HRV) over calm
 * samples. Weighted by elapsed time, not sample count, so the watch's 1 s
 * (screen on) and 5 s (screen off) cadences count the same per minute.
 *
 * Starts from a population prior worth [PRIOR_WEIGHT_SEC] of data, behaves as
 * a plain running mean until [TAU_SEC] of calm data has accumulated, then as an
 * exponential average with that time constant - slow enough that a panic
 * episode barely moves it, fast enough to follow a real change over days.
 */
class LogBaseline(var logMean: Double, var weightSec: Double, var lastMs: Long = 0L) {

    fun update(hrvMs: Double, nowMs: Long) {
        if (hrvMs <= 0.0) return
        // Capped so a sample after a long gap (watch off, phone asleep) cannot
        // carry hours of weight on its own.
        val dt = if (lastMs == 0L) 1.0 else ((nowMs - lastMs) / 1000.0).coerceIn(0.0, MAX_STEP_SEC)
        lastMs = nowMs
        logMean += dt / (weightSec + dt) * (ln(hrvMs) - logMean)
        weightSec = min(weightSec + dt, TAU_SEC)
    }

    /** ln(hrv / baseline): 0 = this user's normal, -0.7 = half of it. */
    fun relative(hrvMs: Double): Double =
        if (hrvMs <= 0.0) 0.0 else (ln(hrvMs) - logMean).coerceIn(-3.0, 1.0)

    val baselineMs: Double get() = exp(logMean)

    companion object {
        const val PRIOR_WEIGHT_SEC = 600.0          // the prior counts as 10 calm minutes
        const val TAU_SEC = 3 * 24 * 3600.0         // 3 days
        const val MAX_STEP_SEC = 10.0

        /** Typical resting HRV per source. REAL_IBI: the resting midpoint of the
         *  training priors. BPM_DERIVED: median of 2,243 calm readings recorded
         *  in June 2026 (11.4 ms) - the estimate runs ~5x below a true RMSSD. */
        fun prior(source: HrvSource): LogBaseline = LogBaseline(
            logMean = ln(if (source == HrvSource.BPM_DERIVED) 11.4 else 52.5),
            weightSec = PRIOR_WEIGHT_SEC,
        )
    }
}

/**
 * Per-source resting HRV baselines, fed by the watch stream and persisted.
 *
 * The model reads HRV only as [relative] - the drop from this user's own normal
 * for the same source - because a raw number is not comparable across sources
 * (or people). Only real watch samples update it; simulation must not.
 */
object HrvBaseline {
    private const val PREFS = "calmsense_hrv_baseline"
    private const val SAVE_EVERY_MS = 60_000L

    // Calm = what "normal" means: on wrist, still, awake, heart rate unremarkable.
    // Sleep is excluded because sleeping HRV runs well above waking HRV.
    private const val CALM_MAX_MOTION = 0.5f   // matches WatchVitalsRepository's moving threshold
    private const val CALM_MAX_HR = 100

    private var prefs: SharedPreferences? = null
    private val baselines = HashMap<HrvSource, LogBaseline>()
    private var lastSaveMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for (src in listOf(HrvSource.REAL_IBI, HrvSource.BPM_DERIVED)) {
            val key = src.apiValue
            if (p.contains("${key}_log")) {
                baselines[src] = LogBaseline(
                    p.getFloat("${key}_log", 0f).toDouble(),
                    p.getFloat("${key}_w", 0f).toDouble(),
                )
            }
        }
    }

    private fun of(source: HrvSource) = baselines.getOrPut(source) { LogBaseline.prior(source) }

    @Synchronized
    fun onWatchSample(bpm: Int?, motion: Float?, hrvMs: Float?, source: HrvSource, asleep: Boolean,
                      nowMs: Long = System.currentTimeMillis()) {
        if (bpm == null || hrvMs == null || source == HrvSource.NONE || asleep) return
        if (bpm >= CALM_MAX_HR || (motion ?: 0f) >= CALM_MAX_MOTION) return
        of(source).update(hrvMs.toDouble(), nowMs)
        if (nowMs - lastSaveMs >= SAVE_EVERY_MS) {
            lastSaveMs = nowMs
            prefs?.edit()?.apply {
                baselines.forEach { (src, b) ->
                    putFloat("${src.apiValue}_log", b.logMean.toFloat())
                    putFloat("${src.apiValue}_w", b.weightSec.toFloat())
                }
            }?.apply()
        }
    }

    /** The model feature for [hrvMs]; 0 (no drop) when the source is unknown. */
    @Synchronized
    fun relative(hrvMs: Double, source: HrvSource): Double =
        if (source == HrvSource.NONE) 0.0 else of(source).relative(hrvMs)

    /** Sent with feedback so the server can retrain on the same feature. */
    @Synchronized
    fun baselineMs(source: HrvSource): Double? =
        if (source == HrvSource.NONE) null else of(source).baselineMs
}
