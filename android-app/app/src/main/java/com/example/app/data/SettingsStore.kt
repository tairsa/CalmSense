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

    // Minimum minutes between panic alerts, so a sustained episode doesn't
    // re-notify on every poll. 0 disables the cooldown.
    const val DEFAULT_COOLDOWN_MINUTES = 3

    private const val PREFS_NAME = "calmsense_settings"
    private const val KEY_THRESHOLD = "detection_threshold"
    private const val KEY_ADVANCED_MODE = "advanced_mode"
    private const val KEY_COOLDOWN_MINUTES = "panic_cooldown_minutes"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_MONITORING_OFF_UNTIL = "monitoring_off_until"

    private var prefs: SharedPreferences? = null

    private val _detectionThreshold = MutableStateFlow(DEFAULT_THRESHOLD_ADVANCED)
    val detectionThreshold: StateFlow<Float> = _detectionThreshold

    // Advanced mode shows the developer view of the dashboard: simulation
    // tools, server/model status, p(panic), motion, threshold, sample delay.
    // Off ("user mode") hides all of that for a clean end-user dashboard.
    // Defaults on so existing installs keep the dashboard they had.
    private val _advancedMode = MutableStateFlow(true)
    val advancedMode: StateFlow<Boolean> = _advancedMode

    private val _panicCooldownMinutes = MutableStateFlow(DEFAULT_COOLDOWN_MINUTES)
    val panicCooldownMinutes: StateFlow<Int> = _panicCooldownMinutes

    // Master switch: false stops the foreground MonitorService and disables
    // in-app detection until the user turns it back on from Settings (or a
    // timed snooze expires).
    private val _monitoringEnabled = MutableStateFlow(true)
    val monitoringEnabled: StateFlow<Boolean> = _monitoringEnabled

    // Epoch millis when a timed snooze ends and monitoring resumes on its
    // own; 0 means off-until-manually-turned-on (or not off at all).
    private val _monitoringOffUntil = MutableStateFlow(0L)
    val monitoringOffUntil: StateFlow<Long> = _monitoringOffUntil

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
        _panicCooldownMinutes.value =
            p.getInt(KEY_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES).coerceAtLeast(0)
        _monitoringEnabled.value = p.getBoolean(KEY_MONITORING_ENABLED, true)
        _monitoringOffUntil.value = p.getLong(KEY_MONITORING_OFF_UNTIL, 0L)
        // A timed snooze that lapsed while nothing was running (alarm lost to
        // a reboot, say) resumes here as a backstop.
        if (!_monitoringEnabled.value &&
            _monitoringOffUntil.value in 1..System.currentTimeMillis()
        ) {
            setMonitoringEnabled(true)
        }
    }

    fun setPanicCooldownMinutes(minutes: Int) {
        val v = minutes.coerceAtLeast(0)
        _panicCooldownMinutes.value = v
        prefs?.edit()?.putInt(KEY_COOLDOWN_MINUTES, v)?.apply()
    }

    /** [offUntilMs] only applies when disabling: epoch millis a timed snooze
     *  ends, or 0 to stay off until manually re-enabled. */
    fun setMonitoringEnabled(enabled: Boolean, offUntilMs: Long = 0L) {
        val until = if (enabled) 0L else offUntilMs
        _monitoringEnabled.value = enabled
        _monitoringOffUntil.value = until
        prefs?.edit()
            ?.putBoolean(KEY_MONITORING_ENABLED, enabled)
            ?.putLong(KEY_MONITORING_OFF_UNTIL, until)
            ?.apply()
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
