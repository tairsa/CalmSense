package com.example.app.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Actigraphy-style sleep detection from the live watch stream.
 *
 * No Wear OS sensor reports sleep directly (verified on the Galaxy Watch 5),
 * and Samsung Health's sleep sessions only land in Health Connect after the
 * fact — useless for real-time state. So we infer it from what the watch
 * already streams, the same way clinical actigraphy does:
 *
 *   ASLEEP  = on wrist + no movement above [STILL_RMS] for [ONSET_STILL_MS]
 *             + heart rate at least [HR_DIP_BPM] below the wearer's awake
 *             baseline (a slow EMA, so "resting on the couch" doesn't count —
 *             the baseline settles to couch HR and sleep must dip below it).
 *   AWAKE   = movement above [WAKE_RMS] sustained for [WAKE_BURST_MS]
 *             (a single sleep-turn blip doesn't wake the state).
 *   UNKNOWN = watch off wrist, or no stream yet.
 *
 * A heart-rate spike alone never flips ASLEEP → AWAKE: a nocturnal panic
 * attack is exactly an HR spike out of sleep, and we want the event tagged
 * as starting during sleep. Detection itself keeps running while asleep.
 *
 * Fed by [WatchListenerService] on every sample; both the UI and
 * MonitorService read [state] (in-process singleton, like the other stores).
 *
 * Thresholds are first-pass values pending overnight calibration — state
 * transitions are logged so real nights can tune them.
 */
object SleepDetector {

    enum class State { UNKNOWN, AWAKE, ASLEEP }

    // Motion RMS below this counts as still. Desk-resting measures ~0.4–0.9
    // on the Watch 5, so this must sit below that noise floor's lower edge.
    private const val STILL_RMS = 0.5f

    // Sustained motion above this while asleep means the wearer got up.
    private const val WAKE_RMS = 1.0f

    private const val ONSET_STILL_MS = 20 * 60_000L
    private const val WAKE_BURST_MS = 60_000L

    // How far below the awake-HR baseline counts as a sleep dip.
    private const val HR_DIP_BPM = 5.0

    // EMA weight per ~2 s sample → baseline time constant of roughly 7 min.
    private const val HR_EMA_ALPHA = 0.005

    // A stream gap longer than this voids the stillness timer (we can't know
    // what happened in between).
    private const val MAX_GAP_MS = 5 * 60_000L

    private const val TAG = "SleepDetector"

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state

    private var awakeHrEma: Double? = null
    private var lastActiveAt = 0L
    private var wakeBurstSince: Long? = null
    private var lastSampleAt = 0L

    val isAsleep: Boolean get() = _state.value == State.ASLEEP

    fun onSample(bpm: Int?, motionRms: Float?, onBody: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val gap = lastSampleAt != 0L && now - lastSampleAt > MAX_GAP_MS
        lastSampleAt = now

        if (!onBody) {
            transition(State.UNKNOWN, now)
            wakeBurstSince = null
            return
        }

        if (_state.value == State.UNKNOWN || gap) {
            // (Re)starting: assume awake, the wearer just produced a sample
            // stream change. Keep the HR baseline — it's still the same wearer.
            transition(State.AWAKE, now)
            lastActiveAt = now
            wakeBurstSince = null
        }

        when (_state.value) {
            State.AWAKE -> {
                if (bpm != null) {
                    awakeHrEma = awakeHrEma?.let { it * (1 - HR_EMA_ALPHA) + bpm * HR_EMA_ALPHA }
                        ?: bpm.toDouble()
                }
                if (motionRms != null && motionRms > STILL_RMS) lastActiveAt = now

                val baseline = awakeHrEma
                val stillLongEnough = now - lastActiveAt >= ONSET_STILL_MS
                if (stillLongEnough && bpm != null && baseline != null && bpm < baseline - HR_DIP_BPM) {
                    transition(State.ASLEEP, now)
                }
            }
            State.ASLEEP -> {
                // Baseline intentionally frozen while asleep so sleep HR
                // doesn't drag the awake reference down.
                if (motionRms != null && motionRms > WAKE_RMS) {
                    if (wakeBurstSince == null) wakeBurstSince = now
                    if (now - wakeBurstSince!! >= WAKE_BURST_MS) {
                        transition(State.AWAKE, now)
                        lastActiveAt = now
                        wakeBurstSince = null
                    }
                } else {
                    wakeBurstSince = null
                }
            }
            State.UNKNOWN -> Unit // unreachable: handled above
        }
    }

    private fun transition(to: State, now: Long) {
        if (_state.value == to) return
        Log.i(TAG, "Sleep state ${_state.value} -> $to (hrBaseline=${awakeHrEma?.toInt()}, stillForMs=${now - lastActiveAt})")
        _state.value = to
    }
}
