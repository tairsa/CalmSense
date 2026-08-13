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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    // Separate, indefinitely-held lock used ONLY on devices with no wake-up
    // accelerometer to tick — see the ticker setup in onCreate. Kept distinct from
    // [wakeLock] because that one is released after every send, which would
    // otherwise drop this hold too (both are setReferenceCounted(false)).
    private var keepAliveLock: PowerManager.WakeLock? = null

    // Guaranteed-cadence ticker, only started in the no-wake-up-sensor fallback.
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var tickerFuture: ScheduledFuture<*>? = null
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var heartBeatSensor: Sensor? = null

    @Volatile private var latestBpm: Int? = null
    @Volatile private var latestMotionRms: Float = 0f
    @Volatile private var latestHrvMs: Float? = null
    // When latestBpm was last refreshed. Periodic sends now fire without a fresh
    // HR event, so without this a long-dead reading would keep being re-sent and
    // the phone — which trusts the arrival time, not the value — would show a
    // stale bpm as current indefinitely. See currentBpm().
    @Volatile private var lastBpmElapsed = 0L

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
    // Watchdog state. The SDK stream can go silent without ever reporting an
    // error — observed on the Watch5 the moment the watch enters Doze: data
    // points stop mid-stream, no onConnectionEnded/onError fires, so
    // onSamsungUnavailable() never runs and the platform HR sensor it displaced
    // is never restored. The result was a live service reporting no heart rate
    // at all. See checkSamsungStall().
    @Volatile private var lastSamsungDataElapsed = 0L
    @Volatile private var lastSamsungRestartElapsed = 0L
    // Serializes HRV-buffer access across the sensor (main) and SDK (binder) threads.
    private val hrvLock = Any()

    // Duty-cycling state for keepAliveLock. The platform HR sensor is itself a
    // wake-up sensor on this hardware (flags 0x3 on the Watch5) batched at
    // HR_BATCH_US, so while it is registered it wakes the SoC on exactly the
    // cadence we need and the keep-alive lock is redundant. The lock is only
    // required when nothing else can wake us — i.e. while the Samsung SDK has
    // displaced the platform sensor. Tracked here and reconciled by
    // updateKeepAlive() at every point either input can change.
    @Volatile private var platformHrRegistered = false
    private var hasWakeUpAccel = false
    private val hrIsWakeUp: Boolean get() = hrSensor?.isWakeUpSensor == true

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
            platformHrRegistered = true
            Log.i(TAG, "HR sensor listener registered (${hrSensor!!.name}, wakeUp=$hrIsWakeUp)")
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
        // Prefer the WAKE-UP variant: it is what keeps samples flowing with the
        // screen off. Batched at ACCEL_BATCH_US, a wake-up sensor wakes the SoC
        // itself every ~10 s to deliver its FIFO burst, and that wake is what
        // drives the periodic send in maybeSendSample(). The non-wake-up variant
        // cannot wake the SoC, so its events (and therefore all sends) stall
        // while the watch sleeps — which is exactly the bug this replaces. This
        // sensor is deliberately never unregistered while the service lives, so
        // the Samsung SDK taking over the PPG can't silence the heartbeat.
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor != null) {
            // ~5 Hz batched into ~10 s bursts: the hardware FIFO buffers samples
            // and the SoC sleeps between bursts instead of being woken at 15 Hz.
            sensorManager.registerListener(this, accelSensor, ACCEL_SAMPLING_US, ACCEL_BATCH_US)
            Log.i(TAG, "Accelerometer listener registered (${accelSensor!!.name}, wakeUp=${accelSensor!!.isWakeUpSensor})")
        } else {
            Log.w(TAG, "No accelerometer available on this device")
        }

        // Fallback for hardware with no wake-up accelerometer: there is no cheap
        // always-on wake source, so we may have to pin the CPU awake to keep the
        // cadence. That lock is duty-cycled rather than simply held — see
        // updateKeepAlive() — because the platform HR sensor covers the same job
        // for free whenever it is the active HR source. The ticker is harmless
        // to leave scheduled: while the lock is off and the SoC is suspended it
        // simply doesn't run, and the HR sensor's batch wakes drive sends instead.
        hasWakeUpAccel = accelSensor?.isWakeUpSensor == true
        if (!hasWakeUpAccel) {
            Log.w(TAG, "No wake-up accelerometer — keep-alive lock available, duty-cycled against the platform HR sensor")
            keepAliveLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalmSense:KeepAlive")
                .apply { setReferenceCounted(false) }
            tickerFuture = ticker.scheduleWithFixedDelay(
                { runCatching { maybeSendSample() }.onFailure { Log.w(TAG, "Ticker send failed", it) } },
                SEND_MAX_INTERVAL_MS, SEND_MAX_INTERVAL_MS, TimeUnit.MILLISECONDS,
            )
        }
        updateKeepAlive()

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
        tickerFuture?.cancel(false)
        ticker.shutdownNow()
        wakeLock?.takeIf { it.isHeld }?.release()
        keepAliveLock?.takeIf { it.isHeld }?.release()
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
                lastBpmElapsed = SystemClock.elapsedRealtime()
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
                // This is the cadence heartbeat, on-wrist and off. On a wake-up
                // accelerometer each batched burst is delivered by an SoC wake,
                // so this call is what guarantees a sample lands every ~10 s with
                // the screen off — regardless of whether HR events are arriving
                // (off wrist, or while the Samsung SDK owns the PPG). The whole
                // burst arrives in one wake, so the interval gate in
                // maybeSendSample collapses it to a single send.
                maybeSendSample()
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
        lastBpmElapsed = SystemClock.elapsedRealtime()
        // Keep the bpm-derived HRV estimate flowing until real IBIs validate
        // (same fallback the platform HR events drove before the takeover).
        if (!useSamsungIbiForHrv) updateHrv(bpm.toFloat())
        maybeSendSample()
    }

    /** The Samsung stream owns the PPG from the first delivery onward: drop the
     *  platform TYPE_HEART_RATE client so the two stacks stop contending for
     *  the sensor (the contention stalls the SDK stream). */
    private fun onSamsungStreamActive() {
        // Stamped on every delivery (valid, dropped, or plain bpm) — this is the
        // liveness signal the stall watchdog measures against.
        lastSamsungDataElapsed = SystemClock.elapsedRealtime()
        if (useSamsungHr) return
        useSamsungHr = true
        hrSensor?.let { sensorManager.unregisterListener(this, it) }
        platformHrRegistered = false
        Log.i(TAG, "Samsung stream live — released platform HR sensor (PPG contention)")
        // We just gave away our free wake source; the lock has to cover the gap.
        updateKeepAlive()
    }

    /** Samsung stream gone (connection ended/failed or tracker error). Fall
     *  back to the platform HR sensor unless we're off-wrist or shutting down. */
    private fun onSamsungUnavailable() {
        useSamsungIbiForHrv = false
        if (!useSamsungHr) return
        useSamsungHr = false
        if (isOnBody && !shuttingDown) {
            hrSensor?.let { sensorManager.registerListener(this, it, HR_SAMPLING_US, HR_BATCH_US) }
            platformHrRegistered = hrSensor != null
            Log.i(TAG, "Samsung stream gone — platform HR sensor re-registered")
            // Back on a wake-up sensor: drop the lock and let the watch sleep.
            updateKeepAlive()
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
            platformHrRegistered = hrSensor != null
            heartBeatSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            samsungHrTracker?.start()
            updateNotification("On wrist — waiting for heart rate…")
        } else {
            hrSensor?.let { sensorManager.unregisterListener(this, it) }
            platformHrRegistered = false
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
        // Wrist state changed the HR-sensor registration either way — reconcile
        // the keep-alive hold before we go quiet again.
        updateKeepAlive()
        lastSendElapsed = SystemClock.elapsedRealtime()
        sendWithWakeLock(bpm = -1, motion = latestMotionRms, hrvMs = null, onBody = onBody)
    }

    /** Hold the keep-alive lock only when nothing else can wake the SoC on our
     *  cadence, so the watch can still reach deep sleep for most of the time it
     *  is worn. A wake-up accelerometer (absent on the Watch5) or a registered
     *  wake-up HR sensor both tick us for free; the lock is needed only in the
     *  gap where the Samsung SDK owns the PPG, and off-wrist we deliberately let
     *  the watch sleep and rely on the wake-up off-body sensor to bring us back.
     *  No-op on hardware that has a wake-up accelerometer — keepAliveLock is
     *  never created there. */
    private fun updateKeepAlive() {
        val lock = keepAliveLock ?: return
        val haveFreeTicker = hasWakeUpAccel || (platformHrRegistered && hrIsWakeUp)
        val need = isOnBody && !haveFreeTicker && !shuttingDown
        if (need && !lock.isHeld) {
            runCatching { lock.acquire() }
            Log.i(TAG, "Keep-alive lock ACQUIRED — no free wake source (Samsung SDK owns the PPG)")
        } else if (!need && lock.isHeld) {
            runCatching { lock.release() }
            val why = if (!isOnBody) "off wrist" else "wake-up HR sensor is ticking us"
            Log.i(TAG, "Keep-alive lock RELEASED — $why; watch may deep-sleep")
        }
    }

    /** Recover from a Samsung stream that stopped delivering without reporting an
     *  error. Reinstating the platform HR sensor matters most in Doze: it is a
     *  wake-up sensor (flags 0x3 on the Watch5), so it keeps delivering batches
     *  where the SDK stream does not. The tracker is then bounced in case it can
     *  reconnect; if it does, onSamsungStreamActive takes the PPG back. Rate
     *  limited so a permanently dead stream can't thrash the sensor stack. */
    private fun checkSamsungStall() {
        if (!useSamsungHr || shuttingDown) return
        val now = SystemClock.elapsedRealtime()
        val silentFor = now - lastSamsungDataElapsed
        if (silentFor < SAMSUNG_STALL_TIMEOUT_MS) return
        if (now - lastSamsungRestartElapsed < SAMSUNG_RESTART_MIN_INTERVAL_MS) return
        lastSamsungRestartElapsed = now
        Log.w(TAG, "Samsung stream silent for ${silentFor}ms — restoring platform HR sensor and bouncing the tracker")
        onSamsungUnavailable()  // clears useSamsungHr and re-registers the HR sensor
        if (isOnBody) {
            samsungHrTracker?.stop()
            samsungHrTracker?.start()
        }
    }

    private fun maybeSendSample() {
        if (isOnBody) checkSamsungStall()
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
        // No early return on a missing bpm any more: the point of the periodic
        // send is that the phone hears from the watch on a fixed cadence even
        // when HR is briefly unavailable (sensor still locking on, PPG handover).
        // bpm = -1 is the established "no reading" wire value and the phone keeps
        // its previous vitals rather than clearing them.
        val bpm = currentBpm()
        lastSendElapsed = now
        val motion = latestMotionRms
        // HRV without a current HR is not attributable to a current heartbeat.
        val hrv = if (bpm > 0) latestHrvMs else null
        val hrText = if (bpm > 0) "HR $bpm" else "HR —"
        val hrvText = if (hrv != null) "HRV ${hrv.toInt()} · " else ""
        updateNotification("$hrText · ${hrvText}motion ${"%.2f".format(motion)}")
        sendWithWakeLock(bpm, motion, hrv, onBody = true)
    }

    /** Latest heart rate, or -1 when there isn't a current one. A cached reading
     *  older than [BPM_STALE_AFTER_MS] is treated as absent: the phone judges
     *  freshness by when a sample arrived, so re-sending an old value on the
     *  periodic tick would launder it as live. */
    private fun currentBpm(): Int {
        val bpm = latestBpm ?: return -1
        if (SystemClock.elapsedRealtime() - lastBpmElapsed > BPM_STALE_AFTER_MS) return -1
        return bpm
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
        // Target worst-case gap between samples, screen on or off. Matches
        // ACCEL_BATCH_US: the wake-up accelerometer's batch delivery is what wakes
        // the SoC on that cadence, and it doubles as the fallback ticker period.
        // (SEND_MIN_INTERVAL_MS still allows faster sends while the CPU is awake.)
        private const val SEND_MAX_INTERVAL_MS = 10_000L
        // A cached bpm older than this is reported as "no reading" — see currentBpm().
        private const val BPM_STALE_AFTER_MS = 30_000L
        // The SDK streams ~1 data point/s, so this much silence means it stalled
        // rather than paused. Kept below BPM_STALE_AFTER_MS so recovery starts
        // before the phone would ever see a gap in heart rate.
        private const val SAMSUNG_STALL_TIMEOUT_MS = 15_000L
        private const val SAMSUNG_RESTART_MIN_INTERVAL_MS = 60_000L
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
