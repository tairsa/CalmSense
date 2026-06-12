package com.example.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.app.MonitorService
import com.example.app.data.SettingsStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings tab: detection (sensitivity dial + alert cooldown), display
 * (advanced mode) and the monitoring on/off switch. Profile settings (name,
 * language, password) will join once user accounts land.
 */
@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
        )

        Text(
            "Detection",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        )

        SensitivityCard()

        Spacer(modifier = Modifier.height(12.dp))

        CooldownCard()

        Text(
            "Display",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        AdvancedModeCard()

        Text(
            "Monitoring",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        MonitoringCard()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CooldownCard() {
    val cooldown by SettingsStore.panicCooldownMinutes.collectAsState()
    var text by remember(cooldown) { mutableStateOf(cooldown.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Alert cooldown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Minimum minutes between panic alerts, so one long episode " +
                        "doesn't notify over and over. 0 alerts on every check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    val digits = new.filter { it.isDigit() }.take(4)
                    text = digits
                    digits.toIntOrNull()?.let { SettingsStore.setPanicCooldownMinutes(it) }
                },
                label = { Text("min") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
            )
        }
    }
}

@Composable
private fun MonitoringCard() {
    val enabled by SettingsStore.monitoringEnabled.collectAsState()
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                if (enabled) "CalmSense is on" else "CalmSense is off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (enabled) {
                    "Turning it off stops panic detection and the background " +
                        "monitoring service until you turn it back on here."
                } else {
                    "Detection is stopped and nothing runs in the background. " +
                        "Turn it back on to resume monitoring."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = {
                    if (enabled) {
                        SettingsStore.setMonitoringEnabled(false)
                        context.stopService(Intent(context, MonitorService::class.java))
                    } else {
                        SettingsStore.setMonitoringEnabled(true)
                        ContextCompat.startForegroundService(
                            context, Intent(context, MonitorService::class.java),
                        )
                    }
                },
                colors = if (enabled) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (enabled) "Turn off CalmSense" else "Turn monitoring back on")
            }
        }
    }
}

@Composable
private fun AdvancedModeCard() {
    val advanced by SettingsStore.advancedMode.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Advanced mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Show the developer view of the dashboard: simulation tools, " +
                        "server and model status, p(panic), motion, threshold and " +
                        "data delay. Turn off for a clean everyday view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                )
            }
            Switch(
                checked = advanced,
                onCheckedChange = { SettingsStore.setAdvancedMode(it) },
            )
        }
    }
}

// Threshold values the slider magnetically sticks to while dragging.
private val SNAP_THRESHOLDS = floatArrayOf(0.70f, 0.50f, 0.35f)

// How close (in threshold units) the thumb must be before it snaps.
private const val SNAP_RADIUS = 0.03f

@Composable
private fun SensitivityCard() {
    val threshold by SettingsStore.detectionThreshold.collectAsState()
    val advanced by SettingsStore.advancedMode.collectAsState()
    val defaultThreshold = SettingsStore.defaultThresholdFor(advanced)

    // The slider shows *sensitivity* (right = more alerts), which is the
    // inverse of the model threshold it stores.
    fun toSensitivity(t: Float) =
        (SettingsStore.MAX_THRESHOLD - t) /
            (SettingsStore.MAX_THRESHOLD - SettingsStore.MIN_THRESHOLD)

    fun toThreshold(s: Float) =
        SettingsStore.MAX_THRESHOLD -
            s * (SettingsStore.MAX_THRESHOLD - SettingsStore.MIN_THRESHOLD)

    fun snapped(s: Float): Float {
        val t = toThreshold(s)
        val near = SNAP_THRESHOLDS.firstOrNull { abs(t - it) <= SNAP_RADIUS }
        return if (near != null) toSensitivity(near) else s
    }

    // Local value while dragging; persisted when the gesture ends.
    var dragging by remember { mutableFloatStateOf(-1f) }
    val sensitivity = if (dragging >= 0f) dragging else toSensitivity(threshold)
    val shownThreshold = if (dragging >= 0f) toThreshold(dragging) else threshold
    val isDefault = abs(threshold - defaultThreshold) < 0.005f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Detection sensitivity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "How easily CalmSense decides something is a panic attack. " +
                    "Higher sensitivity catches more events but raises more false alarms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Slider(
                value = sensitivity,
                onValueChange = { dragging = snapped(it) },
                onValueChangeFinished = {
                    if (dragging >= 0f) SettingsStore.setDetectionThreshold(toThreshold(dragging))
                    dragging = -1f
                },
                valueRange = 0f..1f,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Fewer alerts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "More alerts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Alerts when the model is ≥ ${(shownThreshold * 100).roundToInt()}% sure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
                if (!isDefault) {
                    TextButton(onClick = {
                        SettingsStore.resetDetectionThreshold()
                    }) { Text("Reset") }
                }
            }
        }
    }
}
