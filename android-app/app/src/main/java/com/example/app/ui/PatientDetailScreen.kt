package com.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.app.data.TherapistApi
import com.example.app.ui.theme.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Therapist-only clinical view of a single client. Intentionally distinct
 * from the patient-facing StatsScreen: it reads directly from the backend
 * (therapist has no local copy of the patient's data), leads with a compact
 * summary useful for a session, and puts the report timeline front and
 * center.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailScreen(
    patientLabel: String,
    reports: List<TherapistApi.PatientReport>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(patientLabel) },
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
    ) { pad ->
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryCard(reports = reports, loading = loading)

            if (error != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            SeverityBar(reports = reports)

            TimelineCard(reports = reports, loading = loading)
        }
    }
}

@Composable
private fun SummaryCard(reports: List<TherapistApi.PatientReport>, loading: Boolean) {
    val n = reports.size
    val avg = if (reports.isEmpty()) 0.0 else reports.map { it.severity }.average()
    val topTrigger = reports
        .mapNotNull { it.activityBefore?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
        ?.let { prettify(it) } ?: "-"

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Clinical summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("episodes", n.toString(), Modifier.weight(1f))
                Stat("avg severity", String.format(Locale.US, "%.1f", avg), Modifier.weight(1f))
                Stat("top trigger", topTrigger, Modifier.weight(1f))
            }
            if (loading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact severity distribution 1..10 — same style as StatsScreen. */
@Composable
private fun SeverityBar(reports: List<TherapistApi.PatientReport>) {
    val counts = (1..10).map { level -> reports.count { it.severity == level } }
    val maxCount = counts.max().coerceAtLeast(1)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Severity distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "1 = mild  ·  10 = severe",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                counts.forEachIndexed { i, c ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            val frac = c.toFloat() / maxCount
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(if (c == 0) 0.03f else frac.coerceAtLeast(0.06f))
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (c == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.primary,
                                    ),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (i + 1).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(reports: List<TherapistApi.PatientReport>, loading: Boolean) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Episode timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            if (reports.isEmpty() && !loading) {
                Text(
                    "No episodes have been logged by this client yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                reports
                    .sortedByDescending { it.timestamp ?: "" }
                    .forEachIndexed { i, r ->
                        if (i > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            )
                        }
                        EpisodeRow(r)
                    }
            }
        }
    }
}

@Composable
private fun EpisodeRow(r: TherapistApi.PatientReport) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    r.severity.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTs(r.timestamp) + if (r.detectedByModel) "  ·  model-detected" else "  ·  manual log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            val activity = r.activityBefore?.let { prettify(it) }
            val helped = r.whatHelped
            val header = listOfNotNull(activity).joinToString(" · ")
            if (header.isNotBlank()) {
                Text(
                    header,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            r.feeling?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "\"$it\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
            if (r.symptoms.isNotEmpty()) {
                Text(
                    "Symptoms: " + r.symptoms.joinToString(", ") { prettify(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            helped?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Helped: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            val hr = r.currentHr
            val hrv = r.currentHrv
            if (hr != null || hrv != null) {
                Text(
                    "Vitals at event: " +
                        listOfNotNull(
                            hr?.let { "HR ${it.toInt()} bpm" },
                            hrv?.let { String.format(Locale.US, "HRV %.1f ms", it) },
                        ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

private fun prettify(raw: String): String =
    raw.replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w ->
            w.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() }
        }

private fun formatTs(ts: String?): String {
    if (ts.isNullOrBlank()) return "unknown time"
    return runCatching {
        val instant = Instant.parse(ts)
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(ts)
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PatientDetailScreenPreview() {
    val sample = listOf(
        TherapistApi.PatientReport(
            severity = 8,
            timestamp = "2026-08-04T14:20:00Z",
            detectedByModel = true,
            feeling = "Overwhelmed and shaky.",
            activityBefore = "work_study",
            whatHelped = "Breathing exercises in the app",
            currentHr = 141.0,
            currentHrv = 10.5,
            currentMotionIntensity = 0.08,
            symptoms = listOf("rapid_heart_rate", "shortness_of_breath"),
        ),
        TherapistApi.PatientReport(
            severity = 4,
            timestamp = "2026-08-01T09:00:00Z",
            detectedByModel = false,
            feeling = null,
            activityBefore = "news_social",
            whatHelped = null,
            currentHr = null,
            currentHrv = null,
            currentMotionIntensity = null,
            symptoms = emptyList(),
        ),
    )
    AppTheme {
        PatientDetailScreen(
            patientLabel = "Anna Cohen",
            reports = sample,
            loading = false,
            error = null,
            onBack = {},
        )
    }
}
