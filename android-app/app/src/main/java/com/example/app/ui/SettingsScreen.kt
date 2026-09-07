package com.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.app.MonitoringSnooze
import com.example.app.R
import com.example.app.data.LanguageManager
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
    onRemoveTherapist: (String) -> Unit = {},
    onConnectTherapist: () -> Unit = {},
    onRenameSelf: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
        )

        Text(
            stringResource(R.string.settings_profile),
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
            onRemoveTherapist = onRemoveTherapist,
            onConnectTherapist = onConnectTherapist,
            onRenameSelf = onRenameSelf,
        )

        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        LanguageCard()

        // Detection, display and monitoring all configure the patient-side
        // monitoring service. A therapist account is not monitored, so showing
        // them a sensitivity dial would imply something the app does not do.
        if (role != "therapist") {

        Text(
            stringResource(R.string.settings_detection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        )

        SensitivityCard()

        Spacer(modifier = Modifier.height(12.dp))

        CooldownCard()

        Text(
            stringResource(R.string.settings_display),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        AdvancedModeCard()

        Text(
            stringResource(R.string.settings_monitoring),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        MonitoringCard()

        Text(
            stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
        )

        ConsentCard()

        }  // end patient-only sections

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
    onRemoveTherapist: (String) -> Unit,
    onConnectTherapist: () -> Unit,
    onRenameSelf: (String) -> Unit,
) {
    // Null while closed; holds the in-progress text while the dialog is open so
    // the field survives recomposition without committing anything.
    var renameDraft by remember { mutableStateOf<String?>(null) }

    renameDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { renameDraft = null },
            title = { Text(stringResource(R.string.profile_name_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.profile_name_label)) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.profile_name_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSelf(draft)
                    renameDraft = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameDraft = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Which therapist the user has asked to remove, pending confirmation.
    // Revoking clinical access is not something to do on a single stray tap.
    var pendingRemoval by remember { mutableStateOf<TherapistApi.LinkedTherapist?>(null) }

    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.therapist_remove_title)) },
            // Names what is lost and what is kept: people hesitate here because
            // they cannot tell whether "remove" also deletes their history.
            text = { Text(stringResource(R.string.therapist_remove_body, target.label)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveTherapist(target.therapistId)
                    pendingRemoval = null
                }) {
                    Text(
                        stringResource(R.string.therapist_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
                    // The name leads. When unset we say so instead of falling
                    // back to the email, which hid the fact that this is a
                    // field to fill in - and it is the name the other side
                    // sees, so a blank one is worth surfacing.
                    Text(
                        displayName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.profile_name_none),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (displayName.isNullOrBlank())
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!email.isNullOrBlank()) {
                        Text(
                            email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        when (role) {
                            "therapist" -> stringResource(R.string.role_therapist)
                            "patient" -> stringResource(R.string.role_patient)
                            else -> stringResource(R.string.settings_account)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = { renameDraft = displayName.orEmpty() }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.profile_name_edit),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (role == "patient") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (therapists.isEmpty()) stringResource(R.string.profile_therapist_label) else
                        if (therapists.size == 1) stringResource(R.string.profile_your_therapist) else stringResource(R.string.profile_your_therapists),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (therapists.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_no_therapist),
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
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pendingRemoval = t }) {
                                Text(
                                    stringResource(R.string.therapist_remove),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.profile_therapist_can_see),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                // Present whether or not a therapist is connected, so
                // "change" is one action here rather than remove-then-go-
                // looking-for-connect on another tab.
                TextButton(onClick = onConnectTherapist) {
                    Text(
                        stringResource(
                            if (therapists.isEmpty()) R.string.therapist_connect
                            else R.string.therapist_change
                        )
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
                    stringResource(R.string.profile_sign_out),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}


/**
 * Language picker.
 *
 * Placed directly under Profile rather than in a submenu: someone who has
 * landed in a language they cannot read needs to find this without navigating
 * through more of it.
 *
 * The choice is applied by AppCompatDelegate, which recreates the activity, so
 * there is nothing to save and no restart to prompt for.
 */
@Composable
private fun LanguageCard() {
    // Re-read on each composition rather than holding state: after a change
    // the activity is recreated, so this is always the live value and cannot
    // drift from what is actually applied.
    val current = LanguageManager.current()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            LanguageManager.Language.entries.forEach { lang ->
                val label = when (lang) {
                    LanguageManager.Language.SYSTEM -> stringResource(R.string.language_system)
                    LanguageManager.Language.ENGLISH -> stringResource(R.string.language_english)
                    LanguageManager.Language.HEBREW -> stringResource(R.string.language_hebrew)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { LanguageManager.set(lang) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    RadioButton(
                        selected = lang == current,
                        // Row handles the click so the whole row is the target;
                        // a radio-sized hit area is an accessibility problem.
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        // English and Hebrew names are shown in their own
                        // script, so they stay recognisable whichever language
                        // the rest of the UI is currently in.
                        fontWeight = if (lang == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Text(
                stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
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
                    stringResource(R.string.consent_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.consent_card_desc),
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
                    stringResource(R.string.cooldown_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.cooldown_desc),
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
                label = { Text(stringResource(R.string.cooldown_unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
            )
        }
    }
}

// Label resource → snooze duration; null = off until turned back on manually.
// Resource ids rather than strings: this is a top-level val, so it is built
// before any composition exists and cannot call stringResource itself. The
// label is resolved where the menu is rendered, which also means it follows a
// language change without the app restarting.
private val SNOOZE_OPTIONS: List<Pair<Int, Long?>> = listOf(
    R.string.snooze_30_minutes to 30L * 60_000L,
    R.string.snooze_1_hour to 60L * 60_000L,
    R.string.snooze_2_hours to 2L * 60L * 60_000L,
    R.string.snooze_8_hours to 8L * 60L * 60_000L,
    R.string.snooze_24_hours to 24L * 60L * 60_000L,
    R.string.snooze_indefinite to null,
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
                if (enabled) stringResource(R.string.monitoring_on) else stringResource(R.string.monitoring_off),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    enabled ->
                        stringResource(R.string.monitoring_on_desc)
                    offUntil > 0L ->
                        stringResource(R.string.monitoring_off_until, formatOffUntil(offUntil))
                    else ->
                        stringResource(R.string.monitoring_off_desc)
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
                        Text(stringResource(R.string.monitoring_turn_off))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SNOOZE_OPTIONS.forEach { (labelRes, duration) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
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
                    Text(stringResource(R.string.monitoring_turn_on))
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
                    stringResource(R.string.advanced_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.advanced_mode_desc),
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
                stringResource(R.string.detection_sensitivity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.detection_sensitivity_desc),
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
                    stringResource(R.string.detection_fewer_alerts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.detection_more_alerts),
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
                    stringResource(R.string.detection_threshold_summary, (shownThreshold * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
                if (!isDefault) {
                    TextButton(onClick = {
                        SettingsStore.resetDetectionThreshold()
                    }) { Text(stringResource(R.string.detection_reset)) }
                }
            }
        }
    }
}
