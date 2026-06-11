package com.example.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User-adjustable app settings, persisted to SharedPreferences. Backs the
 * Settings tab.
 *
 * The detection threshold is the sigmoid cutoff the on-device panic
 * classifier alerts at (default 0.5). Lowering it makes detection more
 * sensitive — more alerts, including more false alarms; raising it the
 * opposite. Read by both the in-app classifier (HeartRateViewModel) and the
 * background MonitorService, so the dial applies everywhere.
 */
object SettingsStore {
    // Defaults differ by mode: advanced (developer) keeps the model's natural
    // 0.5 cutoff; user mode starts conservative at 0.7 so everyday users see
    // fewer false alarms out of the box. Moving the dial overrides either.
    const val DEFAULT_THRESHOLD_ADVANCED = 0.5f
    const val DEFAULT_THRESHOLD_USER = 0.7f

    // The dial is meant to nudge sensitivity, not disable detection: below
    // 0.2 everything alerts, above 0.8 nothing does.
    const val MIN_THRESHOLD = 0.2f
    const val MAX_THRESHOLD = 0.8f

    fun defaultThresholdFor(advanced: Boolean) =
        if (advanced) DEFAULT_THRESHOLD_ADVANCED else DEFAULT_THRESHOLD_USER

    private const val PREFS_NAME = "calmsense_settings"
    private const val KEY_THRESHOLD = "detection_threshold"
    private const val KEY_ADVANCED_MODE = "advanced_mode"

    private var prefs: SharedPreferences? = null

    private val _detectionThreshold = MutableStateFlow(DEFAULT_THRESHOLD_ADVANCED)
    val detectionThreshold: StateFlow<Float> = _detectionThreshold

    // Advanced mode shows the developer view of the dashboard: simulation
    // tools, server/model status, p(panic), motion, threshold, sample delay.
    // Off ("user mode") hides all of that for a clean end-user dashboard.
    // Defaults on so existing installs keep the dashboard they had.
    private val _advancedMode = MutableStateFlow(true)
    val advancedMode: StateFlow<Boolean> = _advancedMode

    /** Idempotent. Call from every process entry point (activity + services). */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _advancedMode.value = p.getBoolean(KEY_ADVANCED_MODE, true)
        // KEY_THRESHOLD only exists once the user has moved the dial; until
        // then the effective threshold follows the current mode's default.
        _detectionThreshold.value = if (p.contains(KEY_THRESHOLD)) {
            p.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD_ADVANCED).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        } else {
            defaultThresholdFor(_advancedMode.value)
        }
    }

    fun setDetectionThreshold(value: Float) {
        val v = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        _detectionThreshold.value = v
        prefs?.edit()?.putFloat(KEY_THRESHOLD, v)?.apply()
    }

    /** Back to the current mode's default, and stop overriding mode switches. */
    fun resetDetectionThreshold() {
        prefs?.edit()?.remove(KEY_THRESHOLD)?.apply()
        _detectionThreshold.value = defaultThresholdFor(_advancedMode.value)
    }

    fun setAdvancedMode(enabled: Boolean) {
        _advancedMode.value = enabled
        prefs?.edit()?.putBoolean(KEY_ADVANCED_MODE, enabled)?.apply()
        if (prefs?.contains(KEY_THRESHOLD) != true) {
            _detectionThreshold.value = defaultThresholdFor(enabled)
        }
    }
}
