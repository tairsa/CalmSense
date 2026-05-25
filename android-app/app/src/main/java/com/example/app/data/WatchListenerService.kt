package com.example.app.data

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val text = String(event.data, Charsets.UTF_8)
        when (event.path) {
            MSG_PATH_SAMPLE -> {
                // Format: "<bpm>,<motion_rms>" e.g. "78,0.412"
                val parts = text.split(',')
                val bpm = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return
                val motion = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                WatchVitalsRepository.update(bpm, motion)
                Log.d(TAG, "Received sample bpm=$bpm motion=$motion from ${event.sourceNodeId}")
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
