package com.example.app.data

/**
 * Provenance of an HRV reading — the seam each platform's vitals source fills in
 * so the model can normalize and the UI can flag estimates.
 *
 *  - [REAL_IBI]    real inter-beat intervals: TYPE_HEART_BEAT, Samsung Health
 *                  Sensor SDK ibiList, Apple HealthKit, Health Connect RMSSD.
 *  - [BPM_DERIVED] approximation from smoothed bpm — degraded; the advanced
 *                  dashboard surfaces a notice when this is the active source.
 *  - [NONE]        no HRV available.
 *
 * [wireCode] is the integer sent in the watch→phone sample payload; keep it in
 * sync with the HRV_SRC_* constants in the wear HrMonitoringService.
 */
enum class HrvSource(val wireCode: Int, val apiValue: String) {
    NONE(0, "none"),
    REAL_IBI(1, "real_ibi"),
    BPM_DERIVED(2, "bpm_derived");

    companion object {
        fun fromWire(code: Int?): HrvSource =
            values().firstOrNull { it.wireCode == code } ?: NONE

        /** Inverse of [apiValue]; null for an absent or unrecognised name. */
        fun fromApi(name: String?): HrvSource? =
            values().firstOrNull { it.apiValue == name }
    }
}
