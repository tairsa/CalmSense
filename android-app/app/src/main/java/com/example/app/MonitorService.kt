package com.example.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.app.data.BackendClient
import com.example.app.data.HealthConnectVitalsRepository
import com.example.app.data.PanicAlertGate
import com.example.app.data.PanicDebouncer
import com.example.app.data.PanicModelCache
import com.example.app.data.PostResult
import com.example.app.data.SensorPayload
import com.example.app.data.SettingsStore
import com.example.app.data.SleepDetector
import com.example.app.data.UploadQueue
import com.example.app.data.Vitals
import com.example.app.data.motionFeature
import com.example.app.data.WatchVitalsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var repo: HealthConnectVitalsRepository
    private lateinit var modelCache: PanicModelCache
    private val backend = BackendClient(BACKEND_URL)
    private val panicDebouncer = PanicDebouncer()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        SettingsStore.init(applicationContext)
        UploadQueue.init(applicationContext)
        repo = HealthConnectVitalsRepository(applicationContext)
        modelCache = PanicModelCache(applicationContext)
        startInForeground(buildMonitorNotification("Starting…"))
        startPolling()
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
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                val vitals = readVitals()
                handleVitals(vitals)
                delay(if (isElevated(vitals)) ELEVATED_INTERVAL_MS else NORMAL_INTERVAL_MS)
            }
        }
    }

    private suspend fun readVitals(): Vitals {
        // Prefer the watch when it has a fresh sample — bypasses Samsung Health/HC sync delay.
        val fromWatch = WatchVitalsRepository.readVitals()
        if (fromWatch.heartRateBpm != null) return fromWatch
        // Watch explicitly off-wrist: anything in Health Connect is from before
        // it came off, so don't fall back to it.
        if (fromWatch.watchOnBody == false) return fromWatch
        return runCatching { repo.readVitals() }.getOrNull()
            ?: Vitals(null, null, false)
    }

    private fun isElevated(v: Vitals): Boolean {
        val hr = v.heartRateBpm ?: return false
        val hrv = v.hrv ?: 100.0
        return hr > 110 || hrv < 25.0
    }

    private fun handleVitals(v: Vitals) {
        // No detection or upload of health data without the user's consent. If a
        // system restart revived the service without it, stop ourselves.
        if (!SettingsStore.consentGranted.value) {
            stopSelf()
            return
        }
        val statusText = when {
            v.watchOnBody == false -> "Paused — watch is off your wrist"
            v.heartRateBpm != null && SleepDetector.isAsleep ->
                "Monitoring (sleeping) — ${v.heartRateBpm} bpm"
            v.heartRateBpm != null -> "Monitoring — ${v.heartRateBpm} bpm"
            v.hrSampleAgeMinutes != null -> "Monitoring — last reading ${v.hrSampleAgeMinutes} min ago"
            else -> "Monitoring — waiting for watch data"
        }
        updateMonitorNotification(statusText)

        // Require the detection to persist before acting (filters single-sample
        // spikes); the cooldown gate then keeps a sustained episode from
        // re-notifying on every poll (every 5 s when elevated).
        val panic = panicDebouncer.confirm(isPanic(v))
        if (panic && PanicAlertGate.tryFire()) firePanicNotification()

        uploadIfFresh(v, panic)
    }

    /** Same decision the in-app classifier makes: trained model at the user's
     *  sensitivity threshold, falling back to the fixed rule when no trained
     *  model is cached yet. Reloading the cache each poll (every 5–30 s) keeps
     *  this in sync with weights the app fetches while we run. */
    private fun isPanic(v: Vitals): Boolean {
        val hr = v.heartRateBpm ?: return false
        val hrv = v.hrv ?: return false
        val model = modelCache.load()
        return if (model != null && !model.isUntrained()) {
            // Mirror HeartRateViewModel.checkPanicRisk's motion encoding so
            // foreground and background detections always agree.
            val motion = v.motionFeature()
            val threshold = SettingsStore.detectionThreshold.value.toDouble()
            model.predict(hr.toDouble(), hrv, motion, threshold).isPanic
        } else {
            hr > 120 && hrv < 20.0 && !v.isMoving
        }
    }

    private fun uploadIfFresh(v: Vitals, panic: Boolean) {
        val hr = v.heartRateBpm ?: return  // skip when there's no real reading
        val payload = SensorPayload(
            userId = USER_ID,
            panicAttackDetection = panic,
            currentHr = hr.toFloat(),
            currentHrv = (v.hrv ?: 0.0).toFloat(),
            currentMotionIntensity = v.motionIntensity ?: if (v.isMoving) 1.0f else 0.0f,
        )
        scope.launch {
            // Queued on network failure and re-sent (oldest first) once the
            // server answers again — see UploadQueue.
            when (val r = UploadQueue.postSensor(backend, payload)) {
                PostResult.Success -> Log.d(TAG, "POST ok: hr=$hr panic=$panic")
                is PostResult.HttpError -> Log.w(TAG, "POST failed: HTTP ${r.code}")
                is PostResult.NetworkError -> Log.w(TAG, "POST queued: ${r.reason}")
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(MONITOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(MONITOR_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // On a fresh install the health FGS type is rejected until a
            // qualifying runtime permission (e.g. Health Connect heart rate)
            // is granted — without this the whole app crashes on first run.
            // In-app monitoring still works; we retry on next app launch.
            Log.w(TAG, "Foreground start rejected — stopping monitor service", e)
            stopSelf()
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "CalmSense Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background heart-rate monitoring" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                PANIC_CHANNEL_ID,
                "CalmSense Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for stress detection" }
        )
    }

    private fun buildMonitorNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("CalmSense")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop monitoring",
                stopPI
            )
            .build()
    }

    private fun updateMonitorNotification(text: String) {
        if (!hasPostPermission()) return
        NotificationManagerCompat.from(this)
            .notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(text))
    }

    private fun firePanicNotification() {
        if (!hasPostPermission()) return
        // ACTION_PANIC_ALERT makes MainActivity surface the "was it a panic?"
        // prompt when the user opens the app through this notification.
        val openApp = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_PANIC_ALERT
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, PANIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CalmSense: Breathe with me")
            .setContentText("Your heart rate is high. Try a 1-minute breathing exercise?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(this).notify(PANIC_NOTIFICATION_ID, n)
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_STOP = "com.example.app.action.STOP_MONITORING"
        const val MONITOR_CHANNEL_ID = "MONITOR_CHANNEL_ID"
        const val PANIC_CHANNEL_ID = "PANIC_CHANNEL_ID"
        const val MONITOR_NOTIFICATION_ID = 100
        const val PANIC_NOTIFICATION_ID = 1
        const val NORMAL_INTERVAL_MS = 30_000L
        const val ELEVATED_INTERVAL_MS = 5_000L
    }
}
