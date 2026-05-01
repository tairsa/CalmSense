package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.example.app.data.FakeVitalsRepository
import com.example.app.data.HealthConnectVitalsRepository
import com.example.app.data.PanicModel
import com.example.app.data.VitalsSource
import com.example.app.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult

class HeartRateViewModel : ViewModel() {
    var currentHr by mutableIntStateOf(72)
    var currentHrv by mutableDoubleStateOf(45.0)
    var isMoving by mutableStateOf(false)
    var showBreathingExercise by mutableStateOf(false)
    var dataSource by mutableStateOf(VitalsSource.SIMULATED)
    var healthConnectStatus by mutableStateOf("Not connected")

    // Backend / ML state
    var userId by mutableStateOf("demo-user")
    var panicModel by mutableStateOf<PanicModel?>(null)
    var modelStatus by mutableStateOf("model not loaded")
    var lastPanicProbability by mutableDoubleStateOf(0.0)

    var triggerNotificationCallback: (() -> Unit)? = null
    private var pollJob: Job? = null
    // Tracks whether we were in panic state on the previous tick, so we
    // only fire a notification on the false -> true transition (not every tick).
    private var wasInPanic: Boolean = false

    private val fakeRepo = FakeVitalsRepository()
    private var healthConnectRepo: HealthConnectVitalsRepository? = null
    private val backend = BackendApi()

    fun attachHealthConnect(context: Context) {
        if (healthConnectRepo == null) {
            healthConnectRepo = HealthConnectVitalsRepository(context.applicationContext)
        }
    }

    /** Fetch the trained model weights from the server. Safe to call multiple times. */
    fun loadModelFromBackend() {
        modelStatus = "loading model..."
        viewModelScope.launch {
            try {
                val model = backend.fetchWeights(userId)
                panicModel = model
                modelStatus = if (model.isUntrained())
                    "server returned defaults (no trained model yet)"
                else
                    "model loaded: ${model.source} (acc=${model.testAccuracy?.let { "%.2f".format(it) } ?: "?"})"
            } catch (e: Exception) {
                panicModel = null
                modelStatus = "model load failed: ${e.message ?: e.javaClass.simpleName}"
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

    suspend fun refreshOnce() {
        val vitals =
            when (dataSource) {
                VitalsSource.SIMULATED -> fakeRepo.readVitals()
                VitalsSource.HEALTH_CONNECT -> healthConnectRepo?.readVitals() ?: fakeRepo.readVitals()
            }

        currentHr = vitals.heartRateBpm
        currentHrv = vitals.hrv
        isMoving = vitals.isMoving

        if (dataSource == VitalsSource.HEALTH_CONNECT) {
            healthConnectStatus =
                if (healthConnectRepo?.isAvailable() == true) "Connected (reading HR)" else "Health Connect not available"
        } else {
            healthConnectStatus = "Simulation"
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

    private fun checkPanicRisk(hr: Int, hrv: Double, moving: Boolean) {
        // Use the trained logistic-regression model when available; fall back
        // to the simple rule if the model hasn't been fetched yet.
        val model = panicModel
        val isPanic: Boolean = if (model != null && !model.isUntrained()) {
            // Map boolean isMoving to the [0,1] motion intensity the server expects.
            val motion = if (moving) 0.7 else 0.05
            val pred = model.predict(hr.toDouble(), hrv, motion)
            lastPanicProbability = pred.probability
            pred.isPanic
        } else {
            lastPanicProbability = 0.0
            hr > 120 && hrv < 20.0 && !moving
        }
        // Only fire on the transition into panic, not every tick we stay in it.
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
                            onUseSimulation = { viewModel.dataSource = VitalsSource.SIMULATED }
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
            // Use a unique notification ID per fire so each new panic episode
            // alerts (sound + vibration) instead of silently updating the
            // previous notification of ID=1.
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

        // Heart Rate Visualizer
        HeartRateMonitor(hr = viewModel.currentHr)

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
            TextButton(onClick = onUseSimulation) { Text("Use simulation") }
            Button(onClick = onConnectHealth) { Text("Connect Health") }
        }

        // Model status row
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

        // Stats Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                label = "HRV",
                value = String.format(Locale.getDefault(), "%.1f", viewModel.currentHrv),
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

        // Actions
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

@Composable
fun HeartRateMonitor(hr: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "heartbeat")
    val duration = if (hr > 120) 400 else 1000

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
        // Pulse circles
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
                tint = if (hr > 120) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp).scale(scale)
            )
            Text(
                text = "$hr",
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

            Spacer(modifier = Modifier.height(100.dp))

            TextButton(onClick = onClose) {
                Text("Finish", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
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
            onUseSimulation = {}
        )
    }
}
