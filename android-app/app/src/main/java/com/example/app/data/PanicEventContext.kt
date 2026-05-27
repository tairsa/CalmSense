package com.example.app.data

import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-component holder for the most-recent panic-event context.
 *
 * MonitorService writes here the instant a panic is detected (with the
 * vitals snapshot and a fresh GPS fix); MainActivity reads from here when
 * the user submits severity and we need to materialize a [PanicReportEntity].
 *
 * Lives in-process — both Service and Activity run in the same app process,
 * so an AtomicReference is sufficient. Cleared after a report is written.
 */
object PanicEventContext {

    data class Snapshot(
        val timestampMs: Long,
        val detectedByModel: Boolean,
        val hr: Int?,
        val hrv: Double?,
        val motionIntensity: Float?,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationAccuracyM: Float? = null,
    )

    private val ref = AtomicReference<Snapshot?>(null)

    fun set(snapshot: Snapshot) {
        ref.set(snapshot)
    }

    fun mergeLocation(latitude: Double, longitude: Double, accuracyM: Float) {
        val prev = ref.get() ?: return
        ref.set(prev.copy(latitude = latitude, longitude = longitude, locationAccuracyM = accuracyM))
    }

    fun take(): Snapshot? {
        return ref.getAndSet(null)
    }

    /** Read-only peek without clearing — for late-arriving location merges. */
    fun peek(): Snapshot? = ref.get()
}
