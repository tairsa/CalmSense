package com.example.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.data.PanicReportEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportDetailScreen(
    report: PanicReportEntity?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        if (report == null) {
            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                Text(
                    "Report not found.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "Severity ${report.severity}/10",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.padding(end = 12.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (report.detectedByModel) "Auto-detected" else "Manually logged")
                    },
                )
            }
            Text(
                formatTimestamp(report.timestampMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.padding(top = 16.dp))

            if (report.durationMinutes != null) {
                FieldRow("Duration", "${report.durationMinutes} minutes")
            }
            if (report.feeling != null) {
                FieldRow("What you felt", report.feeling)
            }
            if (report.symptoms.isNotEmpty()) {
                Text(
                    "Symptoms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    report.symptoms.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            if (report.activityBefore != null) {
                FieldRow("Before it started", report.activityBefore)
            }
            if (report.whatHelped != null) {
                FieldRow("What helped", report.whatHelped)
            }

            if (report.latitude != null && report.longitude != null) {
                Text(
                    "Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                val context = LocalContext.current
                FilledTonalButton(
                    onClick = { openInMaps(context, report.latitude, report.longitude, report.timestampMs) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(modifier = Modifier.padding(end = 8.dp))
                    Text("Open in Google Maps")
                }
                Text(
                    "%.5f, %.5f%s".format(
                        report.latitude,
                        report.longitude,
                        report.locationAccuracyM?.let { "  ·  ±${it.toInt()} m" } ?: ""
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text(
                "Vitals at event start",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (report.currentHr != null) FieldRow("Heart rate", "${report.currentHr} bpm", topPad = 8)
            if (report.currentHrv != null) FieldRow("HRV", "%.1f ms".format(report.currentHrv), topPad = 8)
            if (report.currentMotionIntensity != null) FieldRow("Motion", "%.2f".format(report.currentMotionIntensity), topPad = 8)

            Spacer(modifier = Modifier.padding(top = 32.dp))
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, topPad: Int = 16) {
    Column(modifier = Modifier.padding(top = topPad.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(value, modifier = Modifier.padding(12.dp))
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return zdt.format(DateTimeFormatter.ofPattern("EEEE, MMM d • HH:mm"))
}

/**
 * Open the report's coordinates in Google Maps (or any map app). We prefer
 * the universal `geo:` URI with a labeled pin so the user sees "Panic
 * attack at <time>" instead of bare coordinates; the URL-fallback is the
 * Google Maps web URL, which any Android device can render via Chrome.
 */
private fun openInMaps(
    context: android.content.Context,
    lat: Double,
    lng: Double,
    timestampMs: Long,
) {
    val label = "Panic attack at " +
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d HH:mm"))
    val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
    val resolved = geoIntent.resolveActivity(context.packageManager) != null
    val intent = if (resolved) {
        geoIntent
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
