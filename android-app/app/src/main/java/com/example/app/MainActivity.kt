package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.BackendApi
import com.example.app.data.BackendClient
import com.example.app.data.BreathingCoach
import com.example.app.data.FakeVitalsRepository
import com.example.app.data.HealthConnectVitalsRepository
import com.example.app.data.HrvSource
import com.example.app.data.LocationProvider
import com.example.app.data.PanicAlertGate
import com.example.app.data.PanicDebouncer
import com.example.app.data.PanicEventContext
import com.example.app.data.PanicFeedbackPayload
import com.example.app.data.PanicModel
import com.example.app.data.PanicModelCache
import com.example.app.data.PanicReportEntity
import com.example.app.data.PanicReportRepository
import com.example.app.data.PingResult
import com.example.app.data.PostResult
import com.example.app.data.SettingsStore
import com.example.app.data.SleepDetector
import com.example.app.data.UploadQueue
import com.example.app.data.VitalsSource
import com.example.app.data.WatchVitalsRepository
import com.example.app.data.motionFeatureFor
import com.example.app.ui.ConsentScreen
import com.example.app.ui.QuestionnaireAnswers
import com.example.app.ui.QuestionnaireScreen
import com.example.app.ui.ReportDetailScreen
import com.example.app.ui.ReportsScreen
import com.example.app.ui.SettingsScreen
import com.example.app.ui.theme.AppTheme
import androidx.compose.material.icons.automirrored.filled.List
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult

// The Android emulator can't reach the host at the host's LAN IP — it sees the
// host only as 10.0.2.2 on its internal NAT. A real phone reaches the backend
// over Tailscale, so we use the Pi's tailnet IP (pi5-home-server-ts). This works
// from anywhere the phone has Tailscale connected — home Wi-Fi or cellular.
// (Pi LAN IP was http://192.168.1.227:8000 — Wi-Fi only; Tailscale supersedes it.)
// MagicDNS name rather than a raw 100.x address, so the app survives the Pi's tailnet
// IP changing. Resolved by Tailscale's own resolver at 100.100.100.100, which every
// connected node gets — verified 2026-08-02 to return 100.76.34.20. Requires Tailscale
// to be connected on the device (it already was, for the tailnet IP to be reachable).
// Equivalent raw IP as of 2026-08-02, if you ever need to fall back: http://100.76.34.20:8000
private const val BACKEND_LAN_URL = "http://pi5-home-server-ts.tail4f470e.ts.net:8000"
private const val BACKEND_EMULATOR_URL = "http://10.0.2.2:8000"

private val isEmulator: Boolean by lazy {
    val fp = Build.FINGERPRINT.orEmpty()
    val model = Build.MODEL.orEmpty()
    val product = Build.PRODUCT.orEmpty()
    val hardware = Build.HARDWARE.orEmpty()
    fp.startsWith("generic") || fp.startsWith("unknown") ||
        model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for") ||
        product.contains("sdk_gphone") || product.contains("emulator") || product.contains("sdk") ||
        hardware.contains("goldfish") || hardware.contains("ranchu")
}

val BACKEND_URL: String get() = if (isEmulator) BACKEND_EMULATOR_URL else BACKEND_LAN_URL
const val USER_ID = "tairsa-dev"

// Intent action carried by panic-alert notifications: opening the app
// through one should surface the "was it a panic?" confirm prompt.
const val ACTION_PANIC_ALERT = "com.example.app.action.PANIC_ALERT"

// Navigation routes — kept as compile-time constants so the NavHost and the
// auto-navigation LaunchedEffect agree on names.
private const val ROUTE_MONITOR = "monitor"
private const val ROUTE_REPORTS = "reports"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REPORT_DETAIL = "report"
private const val ROUTE_QUESTIONNAIRE = "questionnaire"

class HeartRateViewModel : ViewModel() {
    var currentHr by mutableStateOf<Int?>(72)
    var currentHrv by mutableStateOf<Double?>(45.0)
    var hrSampleAgeMin by mutableStateOf<Long?>(null)
    var sampleDelaySec by mutableStateOf<Long?>(null)
    var isMoving by mutableStateOf(false)
    var motionIntensity by mutableStateOf<Float?>(null)
    var hrvSource by mutableStateOf(HrvSource.NONE)
    var isSleeping by mutableStateOf(false)
    var showBreathingExercise by mutableStateOf(false)
    var dataSource by mutableStateOf(VitalsSource.WATCH)
    var healthConnectStatus by mutableStateOf("Not connected")

    var serverStatus by mutableStateOf("Server: …")

    // Backend / ML state
    var userId by mutableStateOf(USER_ID)
    var panicModel by mutableStateOf<PanicModel?>(null)
    var modelStatus by mutableStateOf("model not loaded")
    var lastPanicProbability by mutableDoubleStateOf(0.0)

    // Panic feedback flow (training labels from the user).
    var showPanicConfirm by mutableStateOf(false)
    var showSeveritySheet by mutableStateOf(false)
    var feedbackStatus by mutableStateOf<String?>(null)
    private var pendingDetectedByModel: Boolean = false
    private var pendingHr: Int? = null
    private var pendingHrv: Double? = null
    private var pendingMotion: Float = 0.0f
    private var pendingProbability: Double = 0.0

    // Journal (panic reports).
    private var reportRepo: PanicReportRepository? = null
    var reports by mutableStateOf<List<PanicReportEntity>>(emptyList())
        private set
    /** When non-null, the dashboard should navigate to /questionnaire/<id>. */
    var pendingQuestionnaireId by mutableStateOf<Long?>(null)
    /** Set by MainActivity once it has a Context — drives async GPS capture. */
    private var appContext: Context? = null

    var triggerNotificationCallback: (() -> Unit)? = null
    private var pollJob: Job? = null
    private var pingJob: Job? = null
    private var wasInPanic: Boolean = false
    private val panicDebouncer = PanicDebouncer()

    private val fakeRepo = FakeVitalsRepository()
    private var healthConnectRepo: HealthConnectVitalsRepository? = null
    private var modelCache: PanicModelCache? = null
    private val backend = BackendApi(BACKEND_URL)
    private val pingBackend = BackendClient(BACKEND_URL)

    fun attachHealthConnect(context: Context) {
        SettingsStore.init(context)
        if (healthConnectRepo == null) {
            healthConnectRepo = HealthConnectVitalsRepository(context.applicationContext)
        }
        if (modelCache == null) {
            val cache = PanicModelCache(context.applicationContext)
            modelCache = cache
            // Hydrate from disk before any network call so the on-device model
            // works offline (panic attacks happen in places without signal).
            cache.load()?.let {
                panicModel = it
                modelStatus = "cached model loaded (${it.source}, trained ${it.trainedAt ?: "?"})"
            }
        }
        if (reportRepo == null) {
            val repo = PanicReportRepository.get(context.applicationContext)
            reportRepo = repo
            appContext = context.applicationContext
            viewModelScope.launch {
                repo.observeAll().collectLatest { rows -> reports = rows }
            }
            viewModelScope.launch { repo.syncPending() }
        }
    }

    fun loadModelFromBackend() {
        val priorStatus = modelStatus
        modelStatus = if (panicModel == null) "loading model..." else "refreshing model..."
        viewModelScope.launch {
            try {
                val model = backend.fetchWeights(userId)
                if (model.isUntrained()) {
                    // Don't overwrite a cached trained model with a default response.
                    if (panicModel != null && !panicModel!!.isUntrained()) {
                        modelStatus = "server has no newer weights — using cached model"
                    } else {
                        panicModel = model
                        modelStatus = "server returned defaults (no trained model yet)"
                    }
                } else {
                    panicModel = model
                    modelCache?.save(model)
                    modelStatus = "model loaded: ${model.source} (acc=${model.testAccuracy?.let { "%.2f".format(it) } ?: "?"})"
                }
            } catch (e: Exception) {
                // Offline / server down — keep the cached model if we have one.
                modelStatus = if (panicModel != null && !panicModel!!.isUntrained()) {
                    "offline — using cached model"
                } else {
                    "model load failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    fun requiredHealthPermissions(): Set<String> =
        healthConnectRepo?.requiredPermissions().orEmpty()

    fun startPolling() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshOnce()
                delay(1_000)
            }
        }
    }

    fun startPinging() {
        if (pingJob != null) return
        pingJob = viewModelScope.launch {
            var wasOffline = false
            while (true) {
                val r = pingBackend.ping()
                serverStatus = when (r) {
                    PingResult.Connected -> "Server: connected"
                    is PingResult.HttpError -> "Server: HTTP ${r.code}"
                    is PingResult.NetworkError -> "Server: offline"
                }
                // Connection came back: push everything held while offline.
                if (r == PingResult.Connected) {
                    if (UploadQueue.pendingCount.value > 0) UploadQueue.flush(pingBackend)
                    if (wasOffline) reportRepo?.syncPending()
                    wasOffline = false
                } else {
                    wasOffline = true
                }
                delay(5_000)
            }
        }
    }

    suspend fun refreshOnce() {
        val vitals =
            when (dataSource) {
                VitalsSource.SIMULATED -> fakeRepo.readVitals()
                VitalsSource.HEALTH_CONNECT -> healthConnectRepo?.readVitals() ?: fakeRepo.readVitals()
                VitalsSource.WATCH -> WatchVitalsRepository.readVitals()
            }

        currentHr = vitals.heartRateBpm
        currentHrv = vitals.hrv
        hrSampleAgeMin = vitals.hrSampleAgeMinutes
        sampleDelaySec = vitals.hrSampleAgeSeconds
        isMoving = vitals.isMoving
        motionIntensity = vitals.motionIntensity
        hrvSource = vitals.hrvSource
        // Sleep state is inferred from the watch stream only — simulated and
        // Health Connect sources don't feed the detector.
        isSleeping = dataSource == VitalsSource.WATCH && SleepDetector.isAsleep

        healthConnectStatus = when (dataSource) {
            VitalsSource.SIMULATED -> "Simulation"
            VitalsSource.HEALTH_CONNECT -> when {
                healthConnectRepo?.isAvailable() != true -> "Health Connect not available"
                vitals.heartRateBpm == null -> "No watch data"
                else -> "Connected (reading HR)"
            }
            VitalsSource.WATCH -> when {
                vitals.watchOnBody == false -> "Watch — off wrist"
                vitals.heartRateBpm != null -> "Watch (live)"
                vitals.watchOnBody == true -> "Watch — reading heart rate…"
                vitals.hrSampleAgeMinutes != null -> "Watch — last reading ${vitals.hrSampleAgeMinutes}m ago"
                else -> "Watch — waiting for samples"
            }
        }

        checkPanicRisk(currentHr, currentHrv, isMoving)
    }

    fun simulatePanicAttack() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.STRESS)
        dataSource = VitalsSource.SIMULATED
        // Clear the edge-trigger debounce so the next detection actually fires
        // a fresh notification (otherwise a leftover wasInPanic=true from a
        // previous demo silently swallows it).
        wasInPanic = false
        panicDebouncer.reset()
    }

    /** Fires the full panic UX (notification + breathing overlay) without
     *  touching the live data source — so you can see what a panic feels
     *  like in the app while still monitoring real watch data underneath.
     *  The severity prompt surfaces after the breathing overlay closes. */
    fun triggerPanicDemo() {
        lastPanicProbability = 0.95
        wasInPanic = true
        captureFeedbackContext(detectedByModel = false)
        triggerNotificationCallback?.invoke()
        showBreathingExercise = true
        showSeveritySheet = true
    }

    fun simulateExercise() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.EXERCISE)
        dataSource = VitalsSource.SIMULATED
        wasInPanic = false
        panicDebouncer.reset()
    }

    fun resetStats() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.BASELINE)
        dataSource = VitalsSource.SIMULATED
        wasInPanic = false
        lastPanicProbability = 0.0
        panicDebouncer.reset()
    }

    private fun checkPanicRisk(hr: Int?, hrv: Double?, moving: Boolean) {
        if (!SettingsStore.consentGranted.value || !SettingsStore.monitoringEnabled.value ||
            hr == null || hrv == null
        ) {
            lastPanicProbability = 0.0
            wasInPanic = false
            panicDebouncer.reset()
            return
        }
        val model = panicModel
        val rawPanic: Boolean = if (model != null && !model.isUntrained()) {
            val motion = motionFeatureFor(motionIntensity, moving)
            val threshold = SettingsStore.detectionThreshold.value.toDouble()
            val pred = model.predict(hr.toDouble(), hrv, motion, threshold)
            lastPanicProbability = pred.probability
            pred.isPanic
        } else {
            lastPanicProbability = 0.0
            hr > 120 && hrv < 20.0 && !moving
        }
        // Require the positive to persist before alerting (filters single-sample
        // spikes). Simulation drives the in-app demos, so it fires immediately.
        val isPanic = if (dataSource == VitalsSource.SIMULATED) rawPanic
                      else panicDebouncer.confirm(rawPanic)
        // The cooldown gate is shared with MonitorService, so the two paths
        // together raise at most one alert per cooldown window.
        if (isPanic && !wasInPanic && PanicAlertGate.tryFire()) {
            triggerNotificationCallback?.invoke()
            captureFeedbackContext(detectedByModel = true)
            // In simulation mode, also open the breathing overlay so the demo
            // is visibly self-evident — users miss notifications when they're
            // already inside the app.
            if (dataSource == VitalsSource.SIMULATED) {
                showBreathingExercise = true
            }
            // Ask the user to label this detection once they can answer.
            // Surfaces after the breathing overlay is dismissed (see UI layer).
            showPanicConfirm = true
        }
        wasInPanic = isPanic
    }

    /** A panic-alert notification was tapped: surface the "was it a panic?"
     *  prompt. In-app detections captured their feedback context when they
     *  fired; a background (MonitorService) detection hasn't, so capture one
     *  now rather than clobber an existing snapshot. */
    fun onPanicNotificationOpened() {
        if (PanicEventContext.peek() == null) {
            captureFeedbackContext(detectedByModel = true)
        }
        showPanicConfirm = true
    }

    /** Manual entry: user logs a panic the model missed. Skips the
     *  "was it a panic?" question and goes straight to severity. */
    fun logManualPanic() {
        captureFeedbackContext(detectedByModel = false)
        showSeveritySheet = true
    }

    fun onConfirmPanic(yes: Boolean) {
        showPanicConfirm = false
        if (yes) {
            showSeveritySheet = true
        } else {
            // False positive — submit immediately with was_panic=false.
            submitFeedback(wasPanic = false, severity = null)
        }
    }

    fun onSeveritySelected(severity: Int) {
        showSeveritySheet = false

        // Severity 0 = the user decided after the fact that it wasn't a real
        // panic attack. Log it as a miss (false positive for the model when
        // detected, or a noise event when manually triggered) and skip the
        // report/questionnaire entirely — there's nothing meaningful to
        // journal.
        if (severity == 0) {
            submitFeedback(wasPanic = false, severity = null)
            PanicEventContext.take()
            return
        }

        submitFeedback(wasPanic = true, severity = severity)
        // Persist a panic-report row immediately so the questionnaire screen
        // can fill it in. We snapshot location from PanicEventContext (which
        // may still be filling in asynchronously — that's OK; we update the
        // row again from any later GPS that arrives before save).
        val repo = reportRepo ?: return
        val snap = PanicEventContext.peek()
        viewModelScope.launch {
            val id = repo.insertAndSync(
                PanicReportEntity(
                    timestampMs = snap?.timestampMs ?: System.currentTimeMillis(),
                    severity = severity,
                    detectedByModel = snap?.detectedByModel ?: pendingDetectedByModel,
                    latitude = snap?.latitude,
                    longitude = snap?.longitude,
                    locationAccuracyM = snap?.locationAccuracyM,
                    currentHr = snap?.hr ?: pendingHr,
                    currentHrv = snap?.hrv ?: pendingHrv,
                    currentMotionIntensity = snap?.motionIntensity,
                    duringSleep = snap?.duringSleep,
                )
            )
            pendingQuestionnaireId = id
        }
    }

    fun saveQuestionnaire(reportId: Long, answers: QuestionnaireAnswers) {
        val repo = reportRepo ?: return
        viewModelScope.launch {
            val existing = repo.findById(reportId) ?: return@launch
            // Late-arriving GPS: if a fix landed after the row was inserted,
            // merge it now.
            val late = PanicEventContext.peek()
            val updated = existing.copy(
                feeling = answers.feeling,
                symptoms = answers.symptoms,
                activityBefore = answers.activityBefore,
                whatHelped = answers.whatHelped,
                durationMinutes = answers.durationMinutes,
                latitude = existing.latitude ?: late?.latitude,
                longitude = existing.longitude ?: late?.longitude,
                locationAccuracyM = existing.locationAccuracyM ?: late?.locationAccuracyM,
            )
            repo.updateAndSync(updated)
            PanicEventContext.take()  // clear once we've consumed it
            pendingQuestionnaireId = null
        }
    }

    fun skipQuestionnaire() {
        // Row is already written; nothing to do besides clearing nav signal.
        PanicEventContext.take()
        pendingQuestionnaireId = null
    }

    fun deleteReport(id: Long) {
        val repo = reportRepo ?: return
        viewModelScope.launch { repo.delete(id) }
    }

    fun dismissSeverity() {
        showSeveritySheet = false
    }

    private fun captureFeedbackContext(detectedByModel: Boolean) {
        pendingDetectedByModel = detectedByModel
        pendingHr = currentHr
        pendingHrv = currentHrv
        pendingMotion = motionIntensity ?: if (isMoving) 1.0f else 0.0f
        pendingProbability = lastPanicProbability

        // Snapshot for the journal entry and kick off a GPS fix.
        PanicEventContext.set(
            PanicEventContext.Snapshot(
                timestampMs = System.currentTimeMillis(),
                detectedByModel = detectedByModel,
                hr = currentHr,
                hrv = currentHrv,
                motionIntensity = motionIntensity ?: if (isMoving) 1.0f else 0.0f,
                duringSleep = if (dataSource == VitalsSource.WATCH) isSleeping else null,
            )
        )
        val ctx = appContext ?: return
        viewModelScope.launch {
            val loc = LocationProvider.getCurrentLocation(ctx)
            if (loc != null) {
                PanicEventContext.mergeLocation(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
    }

    private fun submitFeedback(wasPanic: Boolean, severity: Int?) {
        val payload = PanicFeedbackPayload(
            userId = userId,
            wasPanic = wasPanic,
            severity = severity,
            detectedByModel = pendingDetectedByModel,
            currentHr = pendingHr?.toFloat(),
            currentHrv = pendingHrv?.toFloat(),
            currentMotionIntensity = pendingMotion,
            modelProbability = if (pendingDetectedByModel) pendingProbability else null,
        )
        feedbackStatus = "sending…"
        viewModelScope.launch {
            feedbackStatus = when (val r = UploadQueue.postFeedback(pingBackend, payload)) {
                PostResult.Success -> "Feedback saved — thanks!"
                is PostResult.HttpError -> "Feedback failed (HTTP ${r.code})"
                is PostResult.NetworkError -> "Saved on this phone — sends when the server is back"
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: HeartRateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before setContent: the dashboard reads SettingsStore on first compose.
        SettingsStore.init(this)
        UploadQueue.init(this)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        requestLocationPermissionIfNeeded()
        requestBatteryOptimizationExemptionIfNeeded()
        // Monitoring only starts once the user has consented (see ConsentScreen);
        // granting consent starts the service from setContent.
        if (SettingsStore.consentGranted.value) startMonitorService()

        viewModel.triggerNotificationCallback = {
            sendPanicNotification()
        }
        handlePanicIntent(intent)

        setContent {
            AppTheme {
                // First-launch gate: block the app behind the data-tracking consent
                // screen until the user answers. Monitoring is disabled until consent.
                val consentPrompted by SettingsStore.consentPrompted.collectAsState()
                if (!consentPrompted) {
                    ConsentScreen(
                        onConsent = {
                            SettingsStore.setConsent(true)
                            SettingsStore.setMonitoringEnabled(true)
                            startMonitorService()
                        },
                        onDecline = { SettingsStore.setConsent(false) },
                    )
                    return@AppTheme
                }
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.attachHealthConnect(context)
                    viewModel.loadModelFromBackend()
                    viewModel.startPolling()
                    viewModel.startPinging()
                }

                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        contract = PermissionController.createRequestPermissionResultContract()
                    ) { granted ->
                        viewModel.dataSource =
                            if (granted.containsAll(viewModel.requiredHealthPermissions())) {
                                VitalsSource.HEALTH_CONNECT
                            } else {
                                VitalsSource.SIMULATED
                            }
                    }

                val navController = rememberNavController()
                // Hide bottom nav on the questionnaire and report-detail
                // pushed screens (they have their own TopAppBar with back/skip).
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                val showBottomNav = currentRoute == ROUTE_MONITOR ||
                    currentRoute == ROUTE_REPORTS ||
                    currentRoute == ROUTE_SETTINGS

                // Auto-navigate to the questionnaire when a report row was
                // just inserted post-severity.
                LaunchedEffect(viewModel.pendingQuestionnaireId) {
                    viewModel.pendingQuestionnaireId?.let { id ->
                        navController.navigate("$ROUTE_QUESTIONNAIRE/$id")
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (showBottomNav) {
                            CalmSenseBottomNav(navController, currentRoute)
                        }
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_MONITOR,
                        ) {
                            composable(ROUTE_MONITOR) {
                                CalmSenseDashboard(
                                    viewModel = viewModel,
                                    onConnectHealth = {
                                        permissionLauncher.launch(viewModel.requiredHealthPermissions())
                                    },
                                    onUseSimulation = { viewModel.dataSource = VitalsSource.SIMULATED },
                                    onConnectWatch = { viewModel.dataSource = VitalsSource.WATCH }
                                )
                            }
                            composable(ROUTE_SETTINGS) {
                                SettingsScreen()
                            }
                            composable(ROUTE_REPORTS) {
                                ReportsScreen(
                                    reports = viewModel.reports,
                                    onReportClick = { id ->
                                        navController.navigate("$ROUTE_REPORT_DETAIL/$id")
                                    },
                                    onReportDelete = { id -> viewModel.deleteReport(id) },
                                )
                            }
                            composable("$ROUTE_REPORT_DETAIL/{id}") { backStack ->
                                val id = backStack.arguments?.getString("id")?.toLongOrNull()
                                val report = id?.let { viewModel.reports.firstOrNull { r -> r.id == it } }
                                ReportDetailScreen(
                                    report = report,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable("$ROUTE_QUESTIONNAIRE/{id}") { backStack ->
                                val id = backStack.arguments?.getString("id")?.toLongOrNull() ?: -1L
                                QuestionnaireScreen(
                                    onSkip = {
                                        viewModel.skipQuestionnaire()
                                        navController.popBackStack(ROUTE_MONITOR, inclusive = false)
                                    },
                                    onSave = { answers ->
                                        viewModel.saveQuestionnaire(id, answers)
                                        navController.popBackStack(ROUTE_MONITOR, inclusive = false)
                                    },
                                )
                            }
                        }

                        if (viewModel.showBreathingExercise) {
                            BreathingOverlay(onClose = { viewModel.showBreathingExercise = false })
                        }

                        // Dialog asking the user to confirm a model detection.
                        // Hidden while the breathing overlay is up so it surfaces
                        // after the user finishes breathing.
                        if (viewModel.showPanicConfirm && !viewModel.showBreathingExercise) {
                            ConfirmPanicDialog(
                                onYes = { viewModel.onConfirmPanic(true) },
                                onNo = { viewModel.onConfirmPanic(false) },
                            )
                        }

                        // Defer severity prompt until the breathing overlay
                        // is closed, so the user finishes calming down first.
                        if (viewModel.showSeveritySheet && !viewModel.showBreathingExercise) {
                            SeveritySheet(
                                onDismiss = { viewModel.dismissSeverity() },
                                onSubmit = { viewModel.onSeveritySelected(it) },
                            )
                        }

                        viewModel.feedbackStatus?.let { status ->
                            FeedbackToast(
                                text = status,
                                onDismiss = { viewModel.feedbackStatus = null },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CalmSenseBottomNav(navController: NavHostController, currentRoute: String?) {
        NavigationBar {
            NavigationBarItem(
                selected = currentRoute == ROUTE_MONITOR,
                onClick = {
                    if (currentRoute != ROUTE_MONITOR) {
                        navController.navigate(ROUTE_MONITOR) {
                            popUpTo(ROUTE_MONITOR) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(Icons.Default.MonitorHeart, contentDescription = null) },
                label = { Text("Monitor") },
            )
            NavigationBarItem(
                selected = currentRoute == ROUTE_REPORTS,
                onClick = {
                    if (currentRoute != ROUTE_REPORTS) {
                        navController.navigate(ROUTE_REPORTS) {
                            popUpTo(ROUTE_MONITOR) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                label = { Text("Reports") },
            )
            NavigationBarItem(
                selected = currentRoute == ROUTE_SETTINGS,
                onClick = {
                    if (currentRoute != ROUTE_SETTINGS) {
                        navController.navigate(ROUTE_SETTINGS) {
                            popUpTo(ROUTE_MONITOR) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                101,
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "PANIC_CHANNEL_ID",
            "CalmSense Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for stress detection"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startMonitorService() {
        // Respect the Settings off switch: don't bring monitoring back on launch.
        if (!SettingsStore.monitoringEnabled.value) return
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    /** Notifications arrive here while the activity is alive (singleTop via
     *  the panic PendingIntent); cold starts go through onCreate instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePanicIntent(intent)
    }

    private fun handlePanicIntent(intent: Intent?) {
        if (intent?.action == ACTION_PANIC_ALERT) {
            viewModel.onPanicNotificationOpened()
        }
    }

    private fun sendPanicNotification() {
        val openPrompt = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_PANIC_ALERT
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, "PANIC_CHANNEL_ID")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CalmSense: Breathe with me")
            .setContentText("We noticed your heart rate is high. Want to try a 1-minute breathing exercise?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPrompt)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        try {
            val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            NotificationManagerCompat.from(this).notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Permission missing
        }
    }
}

@Composable
fun CalmSenseDashboard(
    viewModel: HeartRateViewModel,
    onConnectHealth: () -> Unit,
    onUseSimulation: () -> Unit,
    onConnectWatch: () -> Unit,
) {
    // Advanced mode = developer view (simulation tools, server/model status,
    // p(panic), motion, threshold, delay). Off = clean end-user dashboard.
    val advanced by SettingsStore.advancedMode.collectAsState()
    val threshold by SettingsStore.detectionThreshold.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "CalmSense",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        HeartRateMonitor(hr = viewModel.currentHr)

        if (viewModel.dataSource == VitalsSource.HEALTH_CONNECT) {
            Spacer(modifier = Modifier.height(8.dp))
            val age = viewModel.hrSampleAgeMin
            val hr = viewModel.currentHr
            val (msg, isErr) = when {
                hr != null && age != null -> "Last reading: ${ageLabel(age)}" to false
                hr == null && age != null -> "Latest reading is ${ageLabel(age)} old. Watch may be off-wrist or sync is stalled." to true
                else -> "No heart rate data found in Health Connect." to true
            }
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (isErr) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = {},
                label = { Text(viewModel.healthConnectStatus) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (advanced) {
                TextButton(onClick = onUseSimulation) { Text("Sim") }
                Button(onClick = onConnectWatch) { Text("Watch") }
            }
        }

        if (advanced) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(viewModel.serverStatus) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = viewModel.modelStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.loadModelFromBackend() }) { Text("Reload model") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                label = "HRV",
                value = viewModel.currentHrv
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) }
                    ?: "--",
                modifier = Modifier.weight(1f)
            )
            if (advanced) {
                StatCard(
                    label = "Status",
                    value = when {
                        viewModel.isSleeping -> "Sleeping"
                        viewModel.isMoving -> "Active"
                        else -> "Resting"
                    },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "p(panic)",
                    value = String.format(Locale.getDefault(), "%.2f", viewModel.lastPanicProbability),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (advanced && viewModel.hrvSource == HrvSource.BPM_DERIVED) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "HRV is an estimate (bpm-derived) — this device can't supply real beat-to-beat intervals, so accuracy is limited.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (advanced) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCard(
                    label = "Threshold",
                    value = String.format(Locale.getDefault(), "%.2f", threshold),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Motion",
                    value = viewModel.motionIntensity
                        ?.let { String.format(Locale.getDefault(), "%.2f", it) }
                        ?: "--",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Delay",
                    value = viewModel.sampleDelaySec?.let { "${it}s" } ?: "--",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        ActionTile(
            title = "Start Breathing",
            subtitle = "A quick way to center yourself",
            icon = Icons.Default.SelfImprovement,
            onClick = { viewModel.showBreathingExercise = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ActionTile(
            title = "I'm having a panic attack",
            subtitle = "Log a panic the app didn't catch — helps train the model.",
            icon = Icons.Default.Warning,
            onClick = { viewModel.logManualPanic() }
        )

        if (advanced) {
            Spacer(modifier = Modifier.height(12.dp))

            var showSimSheet by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showSimSheet = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Run a simulation") }

            if (showSimSheet) {
                SimulationSheet(
                    onDismiss = { showSimSheet = false },
                    onPanicNow = { viewModel.triggerPanicDemo(); showSimSheet = false },
                    onStressVitals = { viewModel.simulatePanicAttack(); showSimSheet = false },
                    onExercise = { viewModel.simulateExercise(); showSimSheet = false },
                    onReset = { viewModel.resetStats(); showSimSheet = false },
                )
            }
        }
    }
}

private fun ageLabel(min: Long): String = when {
    min < 1L -> "just now"
    min == 1L -> "1 min ago"
    min < 60L -> "$min min ago"
    else -> "${min / 60} h ${min % 60} min ago"
}

@Composable
fun HeartRateMonitor(hr: Int?) {
    val infiniteTransition = rememberInfiniteTransition(label = "heartbeat")
    val highHr = hr != null && hr > 120
    val duration = if (highHr) 400 else 1000

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = when {
                    hr == null -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    highHr -> Color(0xFFEF5350)
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(48.dp).scale(scale)
            )
            Text(
                text = hr?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "BPM",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActionTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SmallSimButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulationSheet(
    onDismiss: () -> Unit,
    onPanicNow: () -> Unit,
    onStressVitals: () -> Unit,
    onExercise: () -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                "Run a simulation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "See what the app does for different physiological scenarios. Live watch data resumes after Reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            SimulationRow(
                icon = Icons.Default.Warning,
                tint = Color(0xFFEF5350),
                title = "Panic now",
                subtitle = "Immediately fire the panic notification and open the breathing exercise. Doesn't change your data source.",
                onClick = onPanicNow,
            )
            SimulationRow(
                icon = Icons.Default.MonitorHeart,
                tint = MaterialTheme.colorScheme.primary,
                title = "Stress vitals",
                subtitle = "Feed the model HR ≈ 135, HRV ≈ 15, no motion. The classifier should detect a panic on its own.",
                onClick = onStressVitals,
            )
            SimulationRow(
                icon = Icons.Default.DirectionsRun,
                tint = MaterialTheme.colorScheme.secondary,
                title = "Exercise",
                subtitle = "High HR but moving — the model should NOT flag a panic.",
                onClick = onExercise,
            )
            SimulationRow(
                icon = Icons.Default.Refresh,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                title = "Reset to baseline",
                subtitle = "Calm vitals; ends the simulation. Switch back to Watch to resume live data.",
                onClick = onReset,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SimulationRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
fun ConfirmPanicDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFEF5350),
            )
        },
        title = { Text("Was that a panic attack?") },
        text = {
            Text("Your answer trains the model. \"No\" marks this as a false alarm; \"Yes\" lets you rate the severity 1–10.")
        },
        confirmButton = {
            TextButton(onClick = onYes) { Text("Yes, it was") }
        },
        dismissButton = {
            TextButton(onClick = onNo) { Text("No, false alarm") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeveritySheet(onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var severity by remember { mutableIntStateOf(5) }
    val isMiss = severity == 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                "How severe was it?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "0 = not actually a panic attack (logged as a miss) · 1 = barely noticeable · 10 = the worst you've experienced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text(
                text = if (isMiss) "Not a panic" else severity.toString(),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isMiss) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = severity.toFloat(),
                onValueChange = { severity = it.toInt().coerceIn(0, 10) },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = { onSubmit(severity) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (isMiss) "Log miss" else "Submit") }
            }
        }
    }
}

@Composable
fun FeedbackToast(text: String, onDismiss: () -> Unit) {
    // Auto-dismiss after 3 s so the model status returns to its resting state.
    LaunchedEffect(text) {
        delay(3_000)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun BreathingOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val coach = remember { BreathingCoach() }
    var isMuted by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        coach.init(context) { ttsReady = true }
        onDispose { coach.shutdown() }
    }

    // Audio narration loop. Plays an opening line, then for each breathing
    // cycle says "Breathe in" / "Breathe out" in sync with the 8-second
    // animation cycle. Every 3 breaths it inserts a reassurance during a
    // full extra cycle so the animation stays in phase with the voice.
    LaunchedEffect(ttsReady, isMuted) {
        if (!ttsReady || isMuted) return@LaunchedEffect
        coach.speakOpening()
        delay(8_000) // one full animation cycle of opening + buffer
        if (!isActive) return@LaunchedEffect

        var cycleCount = 0
        while (isActive) {
            coach.speakIn()
            delay(4_000)
            if (!isActive) break
            coach.speakOut()
            delay(4_000)
            if (!isActive) break
            cycleCount++
            if (cycleCount % 3 == 0) {
                // Rest beat: one full animation cycle with no breath prompts,
                // just a reassuring sentence.
                coach.speakReassurance()
                delay(8_000)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_scale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_alpha"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (scale > 1.15f) "Breathe Out" else "Breathe In",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(64.dp))

            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                )
            }

            Spacer(modifier = Modifier.height(80.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { isMuted = !isMuted }) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute voice" else "Mute voice",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                TextButton(onClick = onClose) {
                    Text("Finish", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    AppTheme {
        CalmSenseDashboard(
            viewModel = HeartRateViewModel(),
            onConnectHealth = {},
            onUseSimulation = {},
            onConnectWatch = {}
        )
    }
}
