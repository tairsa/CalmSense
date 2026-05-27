package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import com.example.app.data.PanicModel
import com.example.app.data.PanicModelCache
import com.example.app.data.PingResult
import com.example.app.data.VitalsSource
import com.example.app.data.WatchVitalsRepository
import com.example.app.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult

// The Android emulator can't reach the host at the host's LAN IP — it sees the
// host only as 10.0.2.2 on its internal NAT. A real phone on the same Wi-Fi
// sees the host at its LAN IP. We pick whichever fits the current device.
private const val BACKEND_LAN_URL = "http://192.168.1.72:8000"
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

class HeartRateViewModel : ViewModel() {
    var currentHr by mutableStateOf<Int?>(72)
    var currentHrv by mutableStateOf<Double?>(45.0)
    var hrSampleAgeMin by mutableStateOf<Long?>(null)
    var isMoving by mutableStateOf(false)
    var showBreathingExercise by mutableStateOf(false)
    var dataSource by mutableStateOf(VitalsSource.WATCH)
    var healthConnectStatus by mutableStateOf("Not connected")

    var serverStatus by mutableStateOf("Server: …")

    // Backend / ML state
    var userId by mutableStateOf(USER_ID)
    var panicModel by mutableStateOf<PanicModel?>(null)
    var modelStatus by mutableStateOf("model not loaded")
    var lastPanicProbability by mutableDoubleStateOf(0.0)

    var triggerNotificationCallback: (() -> Unit)? = null
    private var pollJob: Job? = null
    private var pingJob: Job? = null
    private var wasInPanic: Boolean = false

    private val fakeRepo = FakeVitalsRepository()
    private var healthConnectRepo: HealthConnectVitalsRepository? = null
    private var modelCache: PanicModelCache? = null
    private val backend = BackendApi(BACKEND_URL)
    private val pingBackend = BackendClient(BACKEND_URL)

    fun attachHealthConnect(context: Context) {
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
            while (true) {
                serverStatus = when (val r = pingBackend.ping()) {
                    PingResult.Connected -> "Server: connected"
                    is PingResult.HttpError -> "Server: HTTP ${r.code}"
                    is PingResult.NetworkError -> "Server: offline"
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
        isMoving = vitals.isMoving

        healthConnectStatus = when (dataSource) {
            VitalsSource.SIMULATED -> "Simulation"
            VitalsSource.HEALTH_CONNECT -> when {
                healthConnectRepo?.isAvailable() != true -> "Health Connect not available"
                vitals.heartRateBpm == null -> "No watch data"
                else -> "Connected (reading HR)"
            }
            VitalsSource.WATCH -> when {
                vitals.heartRateBpm != null -> "Watch (live)"
                vitals.hrSampleAgeMinutes != null -> "Watch — last reading ${vitals.hrSampleAgeMinutes}m ago"
                else -> "Watch — waiting for samples"
            }
        }

        checkPanicRisk(currentHr, currentHrv, isMoving)
    }

    fun simulatePanicAttack() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.STRESS)
        dataSource = VitalsSource.SIMULATED
    }

    fun simulateExercise() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.EXERCISE)
        dataSource = VitalsSource.SIMULATED
    }

    fun resetStats() {
        fakeRepo.setMode(FakeVitalsRepository.Mode.BASELINE)
        dataSource = VitalsSource.SIMULATED
    }

    private fun checkPanicRisk(hr: Int?, hrv: Double?, moving: Boolean) {
        if (hr == null || hrv == null) {
            lastPanicProbability = 0.0
            wasInPanic = false
            return
        }
        val model = panicModel
        val isPanic: Boolean = if (model != null && !model.isUntrained()) {
            val motion = if (moving) 0.7 else 0.05
            val pred = model.predict(hr.toDouble(), hrv, motion)
            lastPanicProbability = pred.probability
            pred.isPanic
        } else {
            lastPanicProbability = 0.0
            hr > 120 && hrv < 20.0 && !moving
        }
        if (isPanic && !wasInPanic) {
            triggerNotificationCallback?.invoke()
        }
        wasInPanic = isPanic
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: HeartRateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        requestBatteryOptimizationExemptionIfNeeded()
        startMonitorService()

        viewModel.triggerNotificationCallback = {
            sendPanicNotification()
        }

        setContent {
            AppTheme {
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

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        CalmSenseDashboard(
                            viewModel = viewModel,
                            onConnectHealth = {
                                permissionLauncher.launch(viewModel.requiredHealthPermissions())
                            },
                            onUseSimulation = { viewModel.dataSource = VitalsSource.SIMULATED },
                            onConnectWatch = { viewModel.dataSource = VitalsSource.WATCH }
                        )

                        if (viewModel.showBreathingExercise) {
                            BreathingOverlay(onClose = { viewModel.showBreathingExercise = false })
                        }
                    }
                }
            }
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

    private fun sendPanicNotification() {
        val builder = NotificationCompat.Builder(this, "PANIC_CHANNEL_ID")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CalmSense: Breathe with me")
            .setContentText("We noticed your heart rate is high. Want to try a 1-minute breathing exercise?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

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
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        Spacer(modifier = Modifier.weight(1f))

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
            TextButton(onClick = onUseSimulation) { Text("Sim") }
            TextButton(onClick = onConnectHealth) { Text("HC") }
            Button(onClick = onConnectWatch) { Text("Watch") }
        }

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
            StatCard(
                label = "Status",
                value = if (viewModel.isMoving) "Active" else "Resting",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "p(panic)",
                value = String.format(Locale.getDefault(), "%.2f", viewModel.lastPanicProbability),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        ActionTile(
            title = "Start Breathing",
            subtitle = "A quick way to center yourself",
            icon = Icons.Default.SelfImprovement,
            onClick = { viewModel.showBreathingExercise = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Simulations",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )

        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallSimButton("Stress", onClick = { viewModel.simulatePanicAttack() })
            SmallSimButton("Exercise", onClick = { viewModel.simulateExercise() })
            SmallSimButton("Reset", onClick = { viewModel.resetStats() })
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
