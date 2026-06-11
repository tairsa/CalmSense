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
    private var offBodySensor: Sensor? = null

    @Volatile private var latestBpm: Int? = null
    @Volatile private var latestMotionRms: Float = 0f
    @Volatile private var latestHrvMs: Float? = null

    // Wrist detection. Assume on-body until the off-body sensor says otherwise
    // (it reports the current state as soon as the listener registers), and on
    // devices without the sensor, so behavior is unchanged there.
    @Volatile private var isOnBody = true

    // Rolling buffer of |a|^2 samples for ~5 s @ ~50 Hz = 256 entries
    private val accelSquares = FloatArray(256)
    private var accelIndex = 0
    private var accelFilled = 0
    @Volatile private var lastSendElapsed = 0L

    // HRV (RMSSD approximation). The HR sensor reports smoothed bpm (~1 Hz),
    // not raw beat timestamps, so we derive an inter-beat interval as
    // 60000/bpm and take the RMS of successive IBI differences over a rolling
    // window. This tracks relative HRV trends, which is what the model uses;
    // it is not a clinical RMSSD.
    private val ibiDiffSquares = FloatArray(HRV_WINDOW)
    private var ibiDiffIndex = 0
    private var ibiDiffFilled = 0
    private var lastIbiMs: Float? = null
    private var lastHrEventElapsed = 0L

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

        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (offBodySensor != null) {
            sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Off-body detector registered (${offBodySensor!!.name})")
        } else {
            Log.w(TAG, "No off-body detector on this device — assuming always on-body")
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
                // Stray events can still arrive briefly after the off-body
                // transition unregisters the HR sensor — drop them.
                if (!isOnBody) return
                if (bpm.isNaN() || bpm < 20f || bpm > 250f) return
                latestBpm = bpm.toInt()
                updateHrv(bpm)
                maybeSendSample()
            }
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                onBodyStateChanged(onBody = event.values[0] >= 0.5f)
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
                // Off-wrist there are no HR events to drive sends, so the
                // accelerometer keeps the off-body heartbeat flowing.
                if (!isOnBody) maybeSendSample()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateHrv(bpm: Float) {
        val now = SystemClock.elapsedRealtime()
        val ibiMs = 60_000f / bpm
        val prevIbi = lastIbiMs
        // Only pair consecutive readings; across a gap (sensor dropout, watch
        // off wrist) the difference is meaningless. Stale entries age out of
        // the rolling window as new readings arrive.
        if (prevIbi != null && now - lastHrEventElapsed <= HRV_MAX_GAP_MS) {
            val d = ibiMs - prevIbi
            ibiDiffSquares[ibiDiffIndex] = d * d
            ibiDiffIndex = (ibiDiffIndex + 1) % ibiDiffSquares.size
            if (ibiDiffFilled < ibiDiffSquares.size) ibiDiffFilled++
        }
        lastIbiMs = ibiMs
        lastHrEventElapsed = now

        if (ibiDiffFilled >= HRV_MIN_DIFFS) {
            var sum = 0f
            for (i in 0 until ibiDiffFilled) sum += ibiDiffSquares[i]
            latestHrvMs = sqrt(sum / ibiDiffFilled)
        }
    }

    /** Wrist on/off transition. Off-wrist the optical HR sensor would burn
     *  battery measuring a tabletop, so we stop it and clear the HRV window
     *  (post-gap readings must not pair with pre-gap ones). The phone is told
     *  immediately so its status flips without waiting for the next sample. */
    private fun onBodyStateChanged(onBody: Boolean) {
        if (onBody == isOnBody) return
        isOnBody = onBody
        Log.i(TAG, if (onBody) "Watch back on wrist" else "Watch off wrist")
        if (onBody) {
            hrSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            updateNotification("On wrist — waiting for heart rate…")
        } else {
            hrSensor?.let { sensorManager.unregisterListener(this, it) }
            latestBpm = null
            latestHrvMs = null
            lastIbiMs = null
            ibiDiffIndex = 0
            ibiDiffFilled = 0
            updateNotification("Off wrist — heart-rate monitoring paused")
        }
        lastSendElapsed = SystemClock.elapsedRealtime()
        sendSampleToPhone(bpm = -1, motion = latestMotionRms, hrvMs = null, onBody = onBody)
    }

    private fun maybeSendSample() {
        val now = SystemClock.elapsedRealtime()
        // Off-wrist there's nothing to monitor — just a slow "still off" beacon
        // so the phone can tell off-wrist apart from a dead connection.
        val interval = if (isOnBody) SEND_MIN_INTERVAL_MS else OFFBODY_SEND_INTERVAL_MS
        if (now - lastSendElapsed < interval) return
        if (!isOnBody) {
            lastSendElapsed = now
            sendSampleToPhone(bpm = -1, motion = latestMotionRms, hrvMs = null, onBody = false)
            return
        }
        val bpm = latestBpm ?: return
        lastSendElapsed = now
        val motion = latestMotionRms
        val hrv = latestHrvMs
        val hrvText = if (hrv != null) "HRV ${hrv.toInt()} · " else ""
        updateNotification("HR $bpm · ${hrvText}motion ${"%.2f".format(motion)}")
        sendSampleToPhone(bpm, motion, hrv, onBody = true)
    }

    private fun sendSampleToPhone(bpm: Int, motion: Float, hrvMs: Float?, onBody: Boolean) {
        sendExecutor.execute {
            try {
                val nodeClient = Wearable.getNodeClient(this)
                val messageClient = Wearable.getMessageClient(this)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                // Locale.US keeps the decimal point a '.' so the comma-separated
                // payload can't be corrupted by comma-decimal locales.
                // HRV is -1 until the rolling window has enough readings; bpm is
                // -1 when there's no reading (off wrist, or just re-worn).
                // 4th field: 1 = on wrist, 0 = off wrist.
                val text = String.format(
                    java.util.Locale.US, "%d,%.3f,%.1f,%d",
                    bpm, motion, hrvMs ?: -1f, if (onBody) 1 else 0
                )
                val payload = text.toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Tasks.await(messageClient.sendMessage(node.id, MSG_PATH_SAMPLE, payload))
                }
                Log.d(TAG, "Sent sample bpm=$bpm motion=$motion hrv=$hrvMs onBody=$onBody to ${nodes.size} node(s)")
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
        private const val OFFBODY_SEND_INTERVAL_MS = 15_000L

        // ~30 paired readings at ~1 Hz ≈ a 30-second HRV window.
        private const val HRV_WINDOW = 30
        private const val HRV_MIN_DIFFS = 5
        private const val HRV_MAX_GAP_MS = 5_000L
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
