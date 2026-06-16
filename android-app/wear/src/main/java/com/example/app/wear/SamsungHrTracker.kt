package com.example.app.wear

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * Streams real inter-beat intervals from the Samsung Health Sensor SDK
 * (HEART_RATE_CONTINUOUS). Each valid IBI (status 0, plausible range) is handed
 * to [onIbi] so the caller can compute a true RMSSD — this is the top-priority
 * HRV source on Galaxy watches.
 *
 * Optional and self-protecting: if the SDK can't connect, the Health Platform
 * is missing, the tracker type isn't supported, or the app isn't policy-allowed,
 * [onUnavailable] fires and the caller keeps its TYPE_HEART_BEAT / bpm-derived
 * fallback. Callbacks arrive on the SDK's binder thread.
 */
class SamsungHrTracker(
    private val context: Context,
    private val onIbi: (Int) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private var service: HealthTrackingService? = null
    private var tracker: HealthTracker? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val svc = service ?: return
            val supported = runCatching { svc.trackingCapability.supportHealthTrackerTypes }
                .getOrDefault(emptyList())
            if (HealthTrackerType.HEART_RATE_CONTINUOUS !in supported) {
                Log.w(TAG, "HEART_RATE_CONTINUOUS unsupported — disconnecting, keeping fallback")
                onUnavailable()
                stop()
                return
            }
            tracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                .also { it.setEventListener(trackerListener) }
            Log.i(TAG, "Samsung HR tracker connected — streaming real IBI")
        }

        override fun onConnectionEnded() {
            Log.i(TAG, "Samsung HR tracker connection ended")
            onUnavailable()
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.w(TAG, "Samsung HR connect failed: code=${e.errorCode} ${e.message}")
            onUnavailable()
        }
    }

    private val trackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val ibis = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: continue
                val statuses = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                ibis.forEachIndexed { i, ibi ->
                    // IBI status 0 = valid; reject implausible intervals (30–200 bpm).
                    val status = statuses?.getOrNull(i) ?: 0
                    if (status == 0 && ibi in MIN_IBI_MS..MAX_IBI_MS) onIbi(ibi)
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            // PERMISSION_ERROR / SDK_POLICY_ERROR are terminal (e.g. app not
            // allow-listed). Stop tracking so the continuous PPG sensor powers
            // down instead of draining the battery for data we'll never receive.
            Log.w(TAG, "Samsung HR tracker error: $error — stopping to save battery")
            onUnavailable()
            stop()
        }
    }

    fun start() {
        if (service != null) return
        runCatching {
            service = HealthTrackingService(connectionListener, context)
                .also { it.connectService() }
        }.onFailure {
            Log.w(TAG, "Failed to start HealthTrackingService", it)
            onUnavailable()
        }
    }

    fun stop() {
        runCatching { tracker?.unsetEventListener() }
        runCatching { service?.disconnectService() }
        tracker = null
        service = null
    }

    companion object {
        private const val TAG = "SamsungHrTracker"
        private const val MIN_IBI_MS = 300
        private const val MAX_IBI_MS = 2_000
    }
}
