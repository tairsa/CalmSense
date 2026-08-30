package com.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.data.TherapistApi
import com.example.app.ui.theme.AppTheme

/**
 * Landing screen when the signed-in user has role="therapist".
 *
 * Shows the list of clients that have granted consent, plus a "Add client"
 * action that generates a short code the therapist shares out-of-band. Tap
 * a client to open [PatientDetailScreen].
 */
@Composable
fun TherapistDashboardScreen(
    patients: List<TherapistApi.PatientSummary>,
    generatedCode: TherapistApi.ConsentCodeResponse?,
    generating: Boolean,
    error: String?,
    onGenerateCode: () -> Unit,
    onDismissCode: () -> Unit,
    onPatientClick: (patientId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onGenerateCode = onGenerateCode, generating = generating)

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (patients.isEmpty()) {
                EmptyState()
            } else {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        patients.forEachIndexed { i, p ->
                            PatientRow(patient = p, onClick = { onPatientClick(p.userId) })
                            if (i < patients.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal showing a generated consent code, dismissable.
    if (generatedCode != null) {
        val clipboard = LocalClipboardManager.current
        var copied by remember(generatedCode) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissCode,
            confirmButton = {
                TextButton(onClick = onDismissCode) { Text("Done") }
            },
            title = { Text("Share this code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Give this code to your client. They enter it in their app to grant you view access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        generatedCode.code,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 40.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    // One-tap copy so the therapist can paste it into
                    // WhatsApp / SMS / email without retyping.
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(generatedCode.code))
                            copied = true
                        },
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "Copied!" else "Copy code")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Expires in 30 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            },
        )
    }
}

@Composable
private fun Header(onGenerateCode: () -> Unit, generating: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Your clients",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a client to view their patterns.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onGenerateCode,
            enabled = !generating,
            shape = RoundedCornerShape(14.dp),
        ) {
            if (generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.Add, contentDescription = null)
            }
            Spacer(Modifier.width(6.dp))
            Text("Add a client")
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "No clients yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap \"Add a client\" to generate a consent code. Give the code to your client, and once they enter it in their app, they'll appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PatientRow(patient: TherapistApi.PatientSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                patient.displayName?.takeIf { it.isNotBlank() } ?: "Client",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                patient.userId.take(8) + "...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TherapistDashboardScreenPreview() {
    AppTheme {
        TherapistDashboardScreen(
            patients = listOf(
                TherapistApi.PatientSummary("abc-123", "Anna Cohen"),
                TherapistApi.PatientSummary("def-456", null),
            ),
            generatedCode = null,
            generating = false,
            error = null,
            onGenerateCode = {},
            onDismissCode = {},
            onPatientClick = {},
        )
    }
}
