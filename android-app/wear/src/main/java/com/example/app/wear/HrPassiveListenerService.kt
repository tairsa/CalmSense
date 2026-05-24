package com.example.app.wear

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable

class HrPassiveListenerService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val hrSamples = dataPoints.getData(DataType.HEART_RATE_BPM)
        val latest = hrSamples.maxByOrNull { it.timeDurationFromBoot.toMillis() } ?: return
        val bpm = latest.value
        if (bpm.isNaN() || bpm < 20 || bpm > 250) return  // sanity filter
        sendBpmToPhone(bpm.toInt())
    }

    private fun sendBpmToPhone(bpm: Int) {
        try {
            val nodeClient = Wearable.getNodeClient(this)
            val messageClient = Wearable.getMessageClient(this)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val payload = bpm.toString().toByteArray(Charsets.UTF_8)
            for (node in nodes) {
                Tasks.await(messageClient.sendMessage(node.id, MSG_PATH_HR, payload))
            }
            Log.d(TAG, "Sent HR=$bpm to ${nodes.size} node(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send HR to phone", t)
        }
    }

    companion object {
        private const val TAG = "HrPassiveListener"
        const val MSG_PATH_HR = "/calmsense/hr"
    }
}
