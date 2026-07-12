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
 * HRV source on Galaxy watches. Invalid entries fire [onIbiDropped] instead, so
 * the caller can break its successive-difference chain: diffing the two valid
 * IBIs on either side of a hole produces a spurious jump that inflates RMSSD.
 *
 * Valid heart-rate readings (status 1) are forwarded via [onBpm] so the caller
 * can release its platform TYPE_HEART_RATE client while this stream is live —
 * both drive the same optical PPG, and running two clients stalls the SDK
 * stream a few seconds in.
 *
 * Optional and self-protecting: if the SDK can't connect, the Health Platform
 * is missing, the tracker type isn't supported, or the app isn't policy-allowed,
 * [onUnavailable] fires and the caller keeps its TYPE_HEART_BEAT / bpm-derived
 * fallback. Callbacks arrive on the SDK's binder thread.
 */
class SamsungHrTracker(
    private val context: Context,
    private val onBpm: (Int) -> Unit,
    private val onIbi: (Int) -> Unit,
    private val onIbiDropped: () -> Unit,
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
                val hr = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
                val hrStatus = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
                val ibis = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                val statuses = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                Log.d(TAG, "dp: hr=$hr hrStatus=$hrStatus ibis=$ibis statuses=$statuses")
                // HEART_RATE_STATUS 1 = successful reading (accept a missing
                // status only alongside a plausible value).
                if (hr != null && (hrStatus == null || hrStatus == 1) && hr in MIN_BPM..MAX_BPM) {
                    onBpm(hr)
                }
                ibis?.forEachIndexed { i, ibi ->
                    // IBI status 0 = valid; reject implausible intervals (30–200 bpm).
                    val status = statuses?.getOrNull(i) ?: 0
                    if (status == 0 && ibi in MIN_IBI_MS..MAX_IBI_MS) onIbi(ibi)
                    else onIbiDropped()
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
        private const val MIN_BPM = 20
        private const val MAX_BPM = 250
    }
}
