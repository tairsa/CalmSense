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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class HrMonitoringService : Service(), SensorEventListener {

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val sendExecutor = Executors.newSingleThreadExecutor()

    // Held only briefly (timed) around each sample send so the async send
    // finishes before the SoC suspends again — see sendWithWakeLock. HR delivery
    // itself rides the wake-up HR sensor, which wakes the SoC on its own, so we
    // no longer pin the CPU awake 24/7 (that indefinite hold was the main battery
    // drain — it blocked deep sleep).
    private var wakeLock: PowerManager.WakeLock? = null
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var heartBeatSensor: Sensor? = null

    @Volatile private var latestBpm: Int? = null
    @Volatile private var latestMotionRms: Float = 0f
    @Volatile private var latestHrvMs: Float? = null

    // Wrist detection. Assume on-body until the off-body sensor says otherwise
    // (it reports the current state as soon as the listener registers), and on
    // devices without the sensor, so behavior is unchanged there.
    @Volatile private var isOnBody = true

    // Rolling buffer of |a|^2 samples. The accelerometer now runs at ~5 Hz with
    // batching (low power), so 16 samples ≈ a 3 s motion window — short enough
    // that a single movement spike doesn't keep the RMS above the "moving"
    // threshold long after the wearer has gone still.
    private val accelSquares = FloatArray(16)
    private var accelIndex = 0
    private var accelFilled = 0
    @Volatile private var lastSendElapsed = 0L

    // HRV (RMSSD). Preferred source is TYPE_HEART_BEAT, which fires once per
    // detected heartbeat; the gap between consecutive beat timestamps is a real
    // inter-beat (R-R) interval, so the RMS of successive IBI differences is a
    // genuine RMSSD. When the device exposes no beat sensor we fall back to
    // deriving IBI from the smoothed ~1 Hz bpm (60000/bpm) — that only tracks
    // relative trends and reads far below clinical RMSSD (the smoothing strips
    // the beat-to-beat variance), so it is a last resort, not the intended path.
    private val ibiDiffSquares = FloatArray(HRV_WINDOW)
    private var ibiDiffIndex = 0
    private var ibiDiffFilled = 0
    private var lastIbiMs: Float? = null    // previous IBI, for successive-diff
    private var lastHrEventElapsed = 0L      // bpm-fallback gap guard

    // TYPE_HEART_BEAT path: timestamp of the previous beat → real R-R interval.
    @Volatile private var useHeartBeatForHrv = false
    private var lastBeatNs = 0L

    // Samsung Health Sensor SDK path — top priority on Galaxy watches (real IBI).
    private var samsungHrTracker: SamsungHrTracker? = null
    @Volatile private var useSamsungIbiForHrv = false
    // While the Samsung stream is live it owns the optical sensor: the platform
    // TYPE_HEART_RATE client is released so two clients don't contend for the
    // PPG (observed on the Watch5: the SDK stream stalls ~8 s in when both run).
    @Volatile private var useSamsungHr = false
    @Volatile private var shuttingDown = false
    // Serializes HRV-buffer access across the sensor (main) and SDK (binder) threads.
    private val hrvLock = Any()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification("Starting…"))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalmSense:HrMonitor")
            .apply { setReferenceCounted(false) }

        // Prefer the wake-up variant when the hardware has one — belt and
        // braces alongside the wake lock.
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hrSensor != null) {
            // Batched: buffer ~1 Hz HR in the FIFO and wake the SoC only every
            // ~10 s to deliver the burst, instead of every second.
            sensorManager.registerListener(this, hrSensor, HR_SAMPLING_US, HR_BATCH_US)
            Log.i(TAG, "HR sensor listener registered (${hrSensor!!.name})")
        } else {
            Log.w(TAG, "No HR sensor available on this device")
        }

        // Per-beat sensor → real R-R intervals for a true RMSSD. Prefer the
        // wake-up variant so beats keep arriving with the screen off.
        heartBeatSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_BEAT, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_BEAT)
        useHeartBeatForHrv = heartBeatSensor != null
        if (heartBeatSensor != null) {
            sensorManager.registerListener(this, heartBeatSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Heart-beat sensor registered (${heartBeatSensor!!.name}) — HRV from real R-R")
        } else {
            Log.w(TAG, "No heart-beat sensor — HRV falls back to bpm-derived approximation")
        }

        // Top-priority HRV: Samsung Health Sensor SDK real IBI. Reverts to the
        // sensor fallbacks above if it can't connect / isn't allowed on this device.
        samsungHrTracker = SamsungHrTracker(
            context = this,
            onBpm = ::onSamsungBpm,
            onIbi = ::onSamsungIbi,
            onIbiDropped = ::onSamsungIbiDropped,
            onUnavailable = ::onSamsungUnavailable,
        ).also { it.start() }

        // Linear acceleration removes gravity, so RMS reflects actual motion.
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor != null) {
            // ~5 Hz batched into ~10 s bursts: the hardware FIFO buffers samples
            // and the SoC sleeps between bursts instead of being woken at 15 Hz.
            sensorManager.registerListener(this, accelSensor, ACCEL_SAMPLING_US, ACCEL_BATCH_US)
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
        shuttingDown = true
        sensorManager.unregisterListener(this)
        samsungHrTracker?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
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
                // transition (or the Samsung takeover) unregisters the HR
                // sensor — drop them.
                if (!isOnBody || useSamsungHr) return
                if (bpm.isNaN() || bpm < 20f || bpm > 250f) return
                latestBpm = bpm.toInt()
                if (!useSamsungIbiForHrv && !useHeartBeatForHrv) updateHrv(bpm)
                maybeSendSample()
            }
            Sensor.TYPE_HEART_BEAT -> {
                if (!isOnBody) return
                onHeartBeat(event.timestamp)
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

    /** Real-R-R HRV: the gap between consecutive beat timestamps is a true
     *  inter-beat interval. Reject physiologically implausible intervals
     *  (missed/double beats, gaps) so they don't corrupt RMSSD. */
    private fun onHeartBeat(timestampNs: Long) {
        if (useSamsungIbiForHrv) return  // Samsung SDK is higher priority
        synchronized(hrvLock) {
            val prevBeat = lastBeatNs
            lastBeatNs = timestampNs
            if (prevBeat == 0L) return
            val ibiMs = (timestampNs - prevBeat) / 1_000_000f
            if (ibiMs !in MIN_IBI_MS..MAX_IBI_MS) {
                lastIbiMs = null  // break the diff chain across the bad interval
                return
            }
            acceptIbi(ibiMs)
        }
    }

    /** Valid heart rate from the Samsung SDK. First delivery proves the stream
     *  is live, which is when we release the contending platform HR client. */
    private fun onSamsungBpm(bpm: Int) {
        if (!isOnBody) return
        onSamsungStreamActive()
        latestBpm = bpm
        // Keep the bpm-derived HRV estimate flowing until real IBIs validate
        // (same fallback the platform HR events drove before the takeover).
        if (!useSamsungIbiForHrv) updateHrv(bpm.toFloat())
        maybeSendSample()
    }

    /** The Samsung stream owns the PPG from the first delivery onward: drop the
     *  platform TYPE_HEART_RATE client so the two stacks stop contending for
     *  the sensor (the contention stalls the SDK stream). */
    private fun onSamsungStreamActive() {
        if (useSamsungHr) return
        useSamsungHr = true
        hrSensor?.let { sensorManager.unregisterListener(this, it) }
        Log.i(TAG, "Samsung stream live — released platform HR sensor (PPG contention)")
    }

    /** Samsung stream gone (connection ended/failed or tracker error). Fall
     *  back to the platform HR sensor unless we're off-wrist or shutting down. */
    private fun onSamsungUnavailable() {
        useSamsungIbiForHrv = false
        if (!useSamsungHr) return
        useSamsungHr = false
        if (isOnBody && !shuttingDown) {
            hrSensor?.let { sensorManager.registerListener(this, it, HR_SAMPLING_US, HR_BATCH_US) }
            Log.i(TAG, "Samsung stream gone — platform HR sensor re-registered")
        }
    }

    /** Real IBI from the Samsung SDK — already an inter-beat interval in ms, so
     *  no timestamp diffing needed. Highest-priority HRV source. */
    private fun onSamsungIbi(ibiMs: Int) {
        // Stray callbacks can arrive after the off-body transition stops the
        // tracker — off-wrist optical noise must not enter the HRV window.
        if (!isOnBody) return
        onSamsungStreamActive()
        synchronized(hrvLock) {
            if (!useSamsungIbiForHrv) {
                // First real IBI — discard any fallback diffs already accumulated.
                ibiDiffIndex = 0
                ibiDiffFilled = 0
                lastIbiMs = null
                useSamsungIbiForHrv = true
                Log.i(TAG, "First Samsung IBI received — HRV source is now REAL_IBI")
            }
            acceptIbi(ibiMs.toFloat())
        }
    }

    /** The SDK rejected an IBI (bad status / implausible range). The valid IBIs
     *  on either side of the hole are not adjacent beats, so their difference
     *  is meaningless — break the chain instead of letting it inflate RMSSD. */
    private fun onSamsungIbiDropped() {
        if (!isOnBody) return
        onSamsungStreamActive()  // even rejected IBIs prove the stream is live
        synchronized(hrvLock) { lastIbiMs = null }
    }

    /** Adds an IBI to the rolling window with artifact rejection: an interval
     *  deviating more than 25% from its predecessor is almost always a missed
     *  or double-counted beat (a missed beat reports a doubled interval that
     *  still passes the absolute 300–2000 ms check). Rejecting it breaks the
     *  chain, so the next interval rebaselines cleanly. Call under [hrvLock]. */
    private fun acceptIbi(ibiMs: Float) {
        val prev = lastIbiMs
        if (prev != null) {
            if (abs(ibiMs - prev) > prev * MAX_IBI_REL_DEVIATION) {
                lastIbiMs = null
                return
            }
            addIbiDiff(ibiMs - prev)
        }
        lastIbiMs = ibiMs
        recomputeHrv()
    }

    /** Fallback HRV when the device has no per-beat sensor: derive IBI from the
     *  smoothed bpm. Reads well below true RMSSD — see the field comment. */
    private fun updateHrv(bpm: Float) {
        synchronized(hrvLock) {
            val now = SystemClock.elapsedRealtime()
            // Only pair consecutive readings; across a gap (sensor dropout, watch
            // off wrist) the difference is meaningless. Stale entries age out of
            // the rolling window as new readings arrive.
            if (now - lastHrEventElapsed > HRV_MAX_GAP_MS) lastIbiMs = null
            lastHrEventElapsed = now
            acceptIbi(60_000f / bpm)
        }
    }

    private fun addIbiDiff(diffMs: Float) {
        ibiDiffSquares[ibiDiffIndex] = diffMs * diffMs
        ibiDiffIndex = (ibiDiffIndex + 1) % ibiDiffSquares.size
        if (ibiDiffFilled < ibiDiffSquares.size) ibiDiffFilled++
    }

    private fun recomputeHrv() {
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
            hrSensor?.let { sensorManager.registerListener(this, it, HR_SAMPLING_US, HR_BATCH_US) }
            heartBeatSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            samsungHrTracker?.start()
            updateNotification("On wrist — waiting for heart rate…")
        } else {
            hrSensor?.let { sensorManager.unregisterListener(this, it) }
            heartBeatSensor?.let { sensorManager.unregisterListener(this, it) }
            // Off-wrist the SDK's continuous PPG would keep streaming optical
            // noise (and burning battery); stop it like the platform sensors.
            samsungHrTracker?.stop()
            useSamsungIbiForHrv = false
            useSamsungHr = false
            // Let the watch sleep while it's off the wrist; the wake-up
            // off-body sensor brings us back when it's worn again.
            wakeLock?.takeIf { it.isHeld }?.release()
            latestBpm = null
            synchronized(hrvLock) {
                latestHrvMs = null
                lastIbiMs = null
                lastBeatNs = 0L
                ibiDiffIndex = 0
                ibiDiffFilled = 0
            }
            updateNotification("Off wrist — heart-rate monitoring paused")
        }
        lastSendElapsed = SystemClock.elapsedRealtime()
        sendWithWakeLock(bpm = -1, motion = latestMotionRms, hrvMs = null, onBody = onBody)
    }

    private fun maybeSendSample() {
        val now = SystemClock.elapsedRealtime()
        // Off-wrist there's nothing to monitor — just a slow "still off" beacon
        // so the phone can tell off-wrist apart from a dead connection.
        val interval = if (isOnBody) SEND_MIN_INTERVAL_MS else OFFBODY_SEND_INTERVAL_MS
        if (now - lastSendElapsed < interval) return
        if (!isOnBody) {
            lastSendElapsed = now
            sendWithWakeLock(bpm = -1, motion = latestMotionRms, hrvMs = null, onBody = false)
            return
        }
        val bpm = latestBpm ?: return
        lastSendElapsed = now
        val motion = latestMotionRms
        val hrv = latestHrvMs
        val hrvText = if (hrv != null) "HRV ${hrv.toInt()} · " else ""
        updateNotification("HR $bpm · ${hrvText}motion ${"%.2f".format(motion)}")
        sendWithWakeLock(bpm, motion, hrv, onBody = true)
    }

    /** Hold the CPU just long enough (timed, auto-releasing) to finish the async
     *  send, then let the SoC sleep again — replaces the old 24/7 wake lock. */
    private fun sendWithWakeLock(bpm: Int, motion: Float, hrvMs: Float?, onBody: Boolean) {
        runCatching { wakeLock?.acquire(SEND_WAKELOCK_MS) }
        sendSampleToPhone(bpm, motion, hrvMs, onBody)
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
                // 5th field: HRV provenance (see HrvSource.wireCode) so the phone
                // can flag bpm-derived estimates. Older phone builds ignore it.
                val hrvSourceCode = when {
                    hrvMs == null -> HRV_SRC_NONE
                    useSamsungIbiForHrv || useHeartBeatForHrv -> HRV_SRC_REAL_IBI
                    else -> HRV_SRC_BPM_DERIVED
                }
                val text = String.format(
                    java.util.Locale.US, "%d,%.3f,%.1f,%d,%d",
                    bpm, motion, hrvMs ?: -1f, if (onBody) 1 else 0, hrvSourceCode
                )
                val payload = text.toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    Tasks.await(messageClient.sendMessage(node.id, MSG_PATH_SAMPLE, payload))
                }
                Log.d(TAG, "Sent sample bpm=$bpm motion=$motion hrv=$hrvMs onBody=$onBody to ${nodes.size} node(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send sample to phone", t)
            } finally {
                // Release as soon as the send is done (timed acquire is the backstop).
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
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
        // Timed CPU wake lock around each send; auto-releases as a backstop.
        private const val SEND_WAKELOCK_MS = 4_000L
        // Sensor batching (microseconds): buffer in the hardware FIFO and wake the
        // SoC only every ~10 s, so it can deep-sleep between bursts. HR ~1 Hz,
        // accel ~5 Hz; the ~10 s latency is the worst-case detection delay.
        private const val HR_SAMPLING_US = 1_000_000
        private const val HR_BATCH_US = 10_000_000
        private const val ACCEL_SAMPLING_US = 200_000
        private const val ACCEL_BATCH_US = 10_000_000

        // ~30 paired readings at ~1 Hz ≈ a 30-second HRV window.
        private const val HRV_WINDOW = 30
        private const val HRV_MIN_DIFFS = 5
        private const val HRV_MAX_GAP_MS = 5_000L
        // Plausible R-R interval bounds (30–200 bpm); reject anything outside.
        private const val MIN_IBI_MS = 300f
        private const val MAX_IBI_MS = 2_000f
        // Beat-to-beat artifact filter: successive IBIs deviating more than this
        // fraction are treated as missed/double beats, not real variability.
        private const val MAX_IBI_REL_DEVIATION = 0.25f
        // HRV provenance codes sent to the phone — keep in sync with HrvSource.wireCode.
        private const val HRV_SRC_NONE = 0
        private const val HRV_SRC_REAL_IBI = 1
        private const val HRV_SRC_BPM_DERIVED = 2
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
