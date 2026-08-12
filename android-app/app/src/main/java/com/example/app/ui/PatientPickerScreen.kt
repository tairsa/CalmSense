package com.example.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.app.data.TherapistApi
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * Therapist-only list of accounts that have data on the backend; picking one
 * opens their [StatsScreen].
 *
 * Loads over the network rather than from the local store, because a
 * therapist's phone holds none of their patients' reports. Access is enforced
 * server-side - a non-therapist token gets a 403 that surfaces here as a
 * message rather than an empty list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientPickerScreen(
    accessToken: String,
    onBack: () -> Unit,
    onPatientSelected: (userId: String) -> Unit,
) {
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(accessToken, reloadToken) {
        state = PickerState.Loading
        state = when (val result = TherapistApi.listPatients(accessToken)) {
            is TherapistApi.Result.Ok -> PickerState.Loaded(result.value)
            is TherapistApi.Result.Err -> PickerState.Failed(result.message)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Patients") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is PickerState.Loading -> CircularProgressIndicator()

                is PickerState.Failed -> CenteredMessage(
                    title = "Could not load patients",
                    body = s.message,
                    onRetry = { reloadToken++ },
                )

                is PickerState.Loaded -> if (s.patients.isEmpty()) {
                    CenteredMessage(
                        title = "No patients yet",
                        body = "Accounts appear here once they have synced data to the backend.",
                        onRetry = { reloadToken++ },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.patients, key = { it.userId }) { patient ->
                            PatientCard(patient) { onPatientSelected(patient.userId) }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PickerState {
    data object Loading : PickerState
    data class Loaded(val patients: List<TherapistApi.Patient>) : PickerState
    data class Failed(val message: String) : PickerState
}

@Composable
private fun PatientCard(patient: TherapistApi.Patient, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    patient.userId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(
                            when (patient.reportCount) {
                                0 -> "No reports"
                                1 -> "1 report"
                                else -> "${patient.reportCount} reports"
                            }
                        )
                        formatLastSeen(patient.lastSeen)?.let { append(" · last $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun CenteredMessage(title: String, body: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

/** ISO timestamp -> short local date, or null when absent/unparseable. */
private fun formatLastSeen(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        DateFormat.getDateInstance(DateFormat.MEDIUM)
            .format(Date(Instant.parse(raw).toEpochMilli()))
    } catch (_: Exception) {
        null
    }
}
