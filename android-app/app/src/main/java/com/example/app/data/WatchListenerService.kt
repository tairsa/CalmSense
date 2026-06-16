package com.example.app.data

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val text = String(event.data, Charsets.UTF_8)
        when (event.path) {
            MSG_PATH_SAMPLE -> {
                // Format: "<bpm>,<motion_rms>,<hrv_ms>,<on_body>,<hrv_source>" e.g.
                // "78,0.412,42.3,1,2". HRV is -1 while the watch's rolling window
                // is still filling; bpm is -1 when there's no reading (off
                // wrist, or just re-worn). on_body is 1/0. hrv_source maps to
                // HrvSource.wireCode; older watch builds omit it and were always
                // bpm-derived, so we infer that when hrv is present.
                val parts = text.split(',')
                val bpm = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                val motion = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                val hrv = parts.getOrNull(2)?.trim()?.toFloatOrNull()?.takeIf { it > 0f }
                val onBody = parts.getOrNull(3)?.trim()?.toIntOrNull() != 0
                val hrvSourceCode = parts.getOrNull(4)?.trim()?.toIntOrNull()
                val hrvSource = when {
                    hrvSourceCode != null -> HrvSource.fromWire(hrvSourceCode)
                    hrv != null -> HrvSource.BPM_DERIVED  // legacy build: always bpm-derived
                    else -> HrvSource.NONE
                }
                WatchVitalsRepository.update(bpm, motion, hrv, onBody, hrvSource)
                SleepDetector.onSample(bpm, motion, onBody)
                Log.d(TAG, "Received sample bpm=$bpm motion=$motion hrv=$hrv onBody=$onBody hrvSrc=$hrvSource from ${event.sourceNodeId}")
            }
            MSG_PATH_HR -> {
                val bpm = text.toIntOrNull() ?: return
                WatchVitalsRepository.update(bpm)
                Log.d(TAG, "Received HR=$bpm from watch ${event.sourceNodeId}")
            }
        }
    }

    companion object {
        private const val TAG = "WatchListener"
        const val MSG_PATH_HR = "/calmsense/hr"
        const val MSG_PATH_SAMPLE = "/calmsense/sample"
    }
}
