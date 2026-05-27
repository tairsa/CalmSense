package com.example.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.data.PanicReportEntity
import java.time.Duration
import java.time.Instant

enum class TimeWindow(val label: String, val durationMs: Long?) {
    LAST_24H("24h", 24L * 60 * 60 * 1000),
    LAST_7D("7d", 7L * 24 * 60 * 60 * 1000),
    LAST_30D("30d", 30L * 24 * 60 * 60 * 1000),
    ALL("All", null),
}

@Composable
fun ReportsScreen(
    reports: List<PanicReportEntity>,
    onReportClick: (Long) -> Unit,
    onReportDelete: (Long) -> Unit,
) {
    var window by remember { mutableStateOf(TimeWindow.LAST_7D) }
    val cutoff = window.durationMs?.let { System.currentTimeMillis() - it } ?: 0L
    val filtered = remember(reports, window) {
        reports.filter { it.timestampMs >= cutoff }
    }

    // Two-stage delete: long-press → "Delete this report?" → "Are you sure?"
    var stage1ReportId by remember { mutableStateOf<Long?>(null) }
    var stage2ReportId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Reports",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            TimeWindow.values().forEach { w ->
                FilterChip(
                    selected = window == w,
                    onClick = { window = w },
                    label = { Text(w.label) },
                )
            }
        }

        if (filtered.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { report ->
                    ReportCard(
                        report = report,
                        onClick = { onReportClick(report.id) },
                        onLongClick = { stage1ReportId = report.id },
                    )
                }
            }
        }
    }

    stage1ReportId?.let { id ->
        AlertDialog(
            onDismissRequest = { stage1ReportId = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete this report?") },
            text = {
                Text("This removes it from your history. The labeled training signal stays on the server.")
            },
            confirmButton = {
                TextButton(onClick = {
                    stage1ReportId = null
                    stage2ReportId = id
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { stage1ReportId = null }) { Text("Cancel") }
            },
        )
    }

    stage2ReportId?.let { id ->
        AlertDialog(
            onDismissRequest = { stage2ReportId = null },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFEF5350),
                )
            },
            title = { Text("Are you sure?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onReportDelete(id)
                    stage2ReportId = null
                }) { Text("Yes, delete") }
            },
            dismissButton = {
                TextButton(onClick = { stage2ReportId = null }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No reports in this period.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Logged panic attacks will appear here so you can review them over time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportCard(
    report: PanicReportEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeverityBadge(report.severity)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    relativeTime(report.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                val summary = report.feeling
                    ?: report.symptoms.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
                    ?: if (report.detectedByModel) "Detected by app" else "Manually logged"
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
            }
            if (report.latitude != null && report.longitude != null) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = "Location captured",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: Int) {
    val color = when {
        severity >= 8 -> Color(0xFFEF5350)
        severity >= 5 -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                severity.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

private fun relativeTime(timestampMs: Long): String {
    val d = Duration.between(Instant.ofEpochMilli(timestampMs), Instant.now())
    val mins = d.toMinutes()
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}
