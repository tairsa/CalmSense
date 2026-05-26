package com.example.app.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import kotlin.math.sqrt

class HrMonitoringService : Service(), SensorEventListener {

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    @Volatile private var latestBpm: Int? = null
    @Volatile private var latestMotionRms: Float = 0f

    // Rolling buffer of |a|^2 samples for ~5 s @ ~50 Hz = 256 entries
    private val accelSquares = FloatArray(256)
    private var accelIndex = 0
    private var accelFilled = 0
    @Volatile private var lastSendElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification("Starting…"))

        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hrSensor != null) {
            sensorManager.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "HR sensor listener registered (${hrSensor!!.name})")
        } else {
            Log.w(TAG, "No HR sensor available on this device")
        }

        // Linear acceleration removes gravity, so RMS reflects actual motion.
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Accelerometer listener registered (${accelSensor!!.name})")
        } else {
            Log.w(TAG, "No accelerometer available on this device")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        sendExecutor.shutdown()
        super.onDestroy()
    }

    private var accelEventCount = 0L

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values[0]
                val acc = event.accuracy
                Log.d(TAG, "HR event: raw=$bpm accuracy=$acc")
                if (bpm.isNaN() || bpm < 20f || bpm > 250f) return
                latestBpm = bpm.toInt()
                maybeSendSample()
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                var sq = x * x + y * y + z * z
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val mag = sqrt(sq)
                    val deviation = mag - SensorManager.GRAVITY_EARTH
                    sq = deviation * deviation
                }
                accelSquares[accelIndex] = sq
                accelIndex = (accelIndex + 1) % accelSquares.size
                if (accelFilled < accelSquares.size) accelFilled++

                var sum = 0f
                for (i in 0 until accelFilled) sum += accelSquares[i]
                latestMotionRms = sqrt(sum / accelFilled)
                accelEventCount++
                if (accelEventCount % 100L == 0L) {
                    Log.d(TAG, "Accel events=$accelEventCount latestRms=$latestMotionRms")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun maybeSendSample() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendElapsed < SEND_MIN_INTERVAL_MS) return
        val bpm = latestBpm ?: return
        lastSendElapsed = now
        val motion = latestMotionRms
        updateNotification("HR $bpm · motion ${"%.2f".format(motion)}")
        sendSampleToPhone(bpm, motion)
    }

    private fun sendSampleToPhone(bpm: Int, motion: Float) {
        sendExecutor.execute {
            try {
                val nodeClient = Wearable.getNodeClient(this)
                val messageClient = Wearable.getMessageClient(this)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                val payload = "$bpm,${"%.3f".format(motion)}".toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Tasks.await(messageClient.sendMessage(node.id, MSG_PATH_SAMPLE, payload))
                }
                Log.d(TAG, "Sent sample bpm=$bpm motion=$motion to ${nodes.size} node(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send sample to phone", t)
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("CalmSense")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "HrMonitoringService"
        private const val CHANNEL_ID = "calmsense_hr_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val SEND_MIN_INTERVAL_MS = 2_000L
        const val ACTION_STOP = "com.example.app.wear.action.STOP"
        const val MSG_PATH_SAMPLE = "/calmsense/sample"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CalmSense Monitor", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Ongoing HR and motion monitoring" }
            )
        }

        fun start(ctx: Context) {
            ensureChannel(ctx)
            val i = Intent(ctx, HrMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
