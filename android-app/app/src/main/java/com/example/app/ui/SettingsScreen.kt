package com.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.example.app.MonitoringSnooze
import com.example.app.data.SettingsStore
import com.example.app.data.TherapistApi
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings tab: the signed-in account, detection (sensitivity dial + alert
 * cooldown), display (advanced mode) and the monitoring on/off switch.
 *
 * Profile sits first and owns identity: who you are signed in as, who can see
 * your data, and signing out. Sign out used to be an icon in the top app bar,
 * where it was one mistap away on every screen.
 */
@Composable
fun SettingsScreen(
    email: String?,
    displayName: String?,
    role: String?,
    therapists: List<TherapistApi.LinkedTherapist> = emptyList(),
    onLogout: () -> Unit = {},
) {
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
            "Profile",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        )

        ProfileCard(
            email = email,
            displayName = displayName,
            role = role,
            therapists = therapists,
            onLogout = onLogout,
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

        Text(
            "Privacy",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        ConsentCard()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Who you are, who can see your data, and the way out.
 *
 * The therapist list is only meaningful for a patient: it is the visible
 * counterpart of the consent they granted by redeeming a code, so they can
 * check at a glance who currently has access. A therapist sees their own role
 * instead - their client list already lives on their dashboard.
 */
@Composable
private fun ProfileCard(
    email: String?,
    displayName: String?,
    role: String?,
    therapists: List<TherapistApi.LinkedTherapist>,
    onLogout: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Name is the friendlier label, so it leads when set and
                    // the email drops to a subtitle. With no name the email
                    // takes the title slot rather than leaving a blank line.
                    Text(
                        displayName?.takeIf { it.isNotBlank() } ?: email ?: "Signed in",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!displayName.isNullOrBlank() && !email.isNullOrBlank()) {
                        Text(
                            email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        when (role) {
                            "therapist" -> "Therapist"
                            "patient" -> "Patient"
                            else -> "Account"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            if (role == "patient") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (therapists.isEmpty()) "Therapist" else
                        if (therapists.size == 1) "Your therapist" else "Your therapists",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (therapists.isEmpty()) {
                    Text(
                        "No therapist connected. Enter a code from your therapist " +
                            "on the Stats tab to share your reports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    therapists.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                t.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "They can see your panic reports and vitals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            TextButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Sign out",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}


@Composable
private fun ConsentCard() {
    val consent by SettingsStore.consentGranted.collectAsState()
    val context = LocalContext.current

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
                    "Heart-rate data consent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "CalmSense reads heart rate and HRV from your watch to detect " +
                        "panic attacks. Turning this off revokes consent and stops " +
                        "all monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                )
            }
            Switch(
                checked = consent,
                onCheckedChange = { granted ->
                    if (granted) {
                        SettingsStore.setConsent(true)
                        MonitoringSnooze.turnOn(context)
                    } else {
                        SettingsStore.setConsent(false)
                        MonitoringSnooze.turnOff(context, null)
                    }
                },
            )
        }
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

// Label → snooze duration; null = off until turned back on manually.
private val SNOOZE_OPTIONS: List<Pair<String, Long?>> = listOf(
    "For 30 minutes" to 30L * 60_000L,
    "For 1 hour" to 60L * 60_000L,
    "For 2 hours" to 2L * 60L * 60_000L,
    "For 8 hours" to 8L * 60L * 60_000L,
    "For 24 hours" to 24L * 60L * 60_000L,
    "Until I turn it back on" to null,
)

@Composable
private fun MonitoringCard() {
    val enabled by SettingsStore.monitoringEnabled.collectAsState()
    val offUntil by SettingsStore.monitoringOffUntil.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

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
                when {
                    enabled ->
                        "Turning it off stops panic detection and the background " +
                            "monitoring service — for a set time, or until you " +
                            "turn it back on here."
                    offUntil > 0L ->
                        "Detection is stopped and nothing runs in the background. " +
                            "Monitoring turns back on by itself at ${formatOffUntil(offUntil)}."
                    else ->
                        "Detection is stopped and nothing runs in the background. " +
                            "Turn it back on to resume monitoring."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (enabled) {
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = { menuOpen = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("Turn off CalmSense…")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SNOOZE_OPTIONS.forEach { (label, duration) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menuOpen = false
                                    MonitoringSnooze.turnOff(context, duration)
                                },
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = { MonitoringSnooze.turnOn(context) },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Turn monitoring back on")
                }
            }
        }
    }
}

private fun formatOffUntil(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(epochMs))

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
