package com.example.app.data

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != MSG_PATH_HR) return
        val bpm = String(event.data, Charsets.UTF_8).toIntOrNull() ?: return
        WatchVitalsRepository.update(bpm)
        Log.d(TAG, "Received HR=$bpm from watch ${event.sourceNodeId}")
    }

    companion object {
        private const val TAG = "WatchListener"
        const val MSG_PATH_HR = "/calmsense/hr"
    }
}
