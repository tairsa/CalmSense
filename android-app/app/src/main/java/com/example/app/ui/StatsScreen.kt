package com.example.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.app.data.PanicReportEntity
import com.example.app.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/* ---------------------------------------------------------------------------
 * StatsScreen
 *
 * Pattern-tracking dashboard the user sees on their own device (and later,
 * the therapist sees for their clients). Reads from the local report list so
 * it works offline and updates instantly as new reports come in.
 *
 * Three charts, all hand-drawn with Compose primitives - no chart library:
 *   1. Panic frequency by day (last 14 days) - vertical bars.
 *   2. Trigger breakdown - horizontal bars per activity_before value.
 *   3. Severity distribution (1..10) - vertical bars.
 *
 * Plus a summary card up top: total episodes, average severity, top trigger.
 * ------------------------------------------------------------------------- */

@Composable
fun StatsScreen(
    reports: List<PanicReportEntity>,
    modifier: Modifier = Modifier,
    userEmail: String? = null,
    onConnectTherapist: (() -> Unit)? = null,
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
            Header(userEmail = userEmail)
            if (reports.isEmpty()) {
                EmptyState()
            } else {
                SummaryCard(reports)
                FrequencyChartCard(reports)
                TriggerBreakdownCard(reports)
                SeverityDistributionCard(reports)
            }
            if (onConnectTherapist != null) {
                ConnectTherapistCard(onClick = onConnectTherapist)
            }
        }
    }
}

@Composable
private fun ConnectTherapistCard(onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Connect a therapist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Have a code from your therapist? Enter it here to let them see your reports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClick) {
                Text("Enter a therapist code")
            }
        }
    }
}

/* ---------- Header + empty state ----------------------------------------- */

@Composable
private fun Header(userEmail: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Your patterns",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (userEmail.isNullOrBlank()) {
                "See when attacks happen and what triggers them."
            } else {
                "Signed in as $userEmail"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState() {
    ChartCard(title = "Nothing to show yet") {
        Text(
            "Once you log a few panic attacks in the questionnaire, patterns and " +
                "statistics will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/* ---------- Summary card -------------------------------------------------- */

@Composable
private fun SummaryCard(reports: List<PanicReportEntity>) {
    val total = reports.size
    val avgSeverity = reports.map { it.severity }.average()
    val topTrigger = reports
        .mapNotNull { it.activityBefore?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?.let { prettyTrigger(it) }
        ?: "-"

    ChartCard(title = "At a glance") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatCell(total.toString(), "episodes", Modifier.weight(1f))
            StatCell(String.format(Locale.US, "%.1f", avgSeverity), "avg severity", Modifier.weight(1f))
            StatCell(topTrigger, "top trigger", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/* ---------- Chart 1: Panic frequency (last 14 days) ---------------------- */

@Composable
private fun FrequencyChartCard(reports: List<PanicReportEntity>) {
    // Bucket by local calendar day for the last 14 days ending today.
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val days = (13 downTo 0).map { offset ->
        (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -offset) }
    }
    val dayFormat = SimpleDateFormat("d", Locale.US)   // day-of-month
    val counts = days.map { day ->
        val start = day.timeInMillis
        val end = start + 24L * 60 * 60 * 1000
        reports.count { it.timestampMs in start until end }
    }
    val maxCount = counts.max().coerceAtLeast(1)

    ChartCard(
        title = "Panic frequency",
        subtitle = "Last 14 days",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEachIndexed { i, day ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val c = counts[i]
                    // Drawing area: everything above the day label.
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
                        dayFormat.format(day.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/* ---------- Chart 2: Trigger breakdown (horizontal bars) ----------------- */

@Composable
private fun TriggerBreakdownCard(reports: List<PanicReportEntity>) {
    val counts = reports
        .mapNotNull { it.activityBefore?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }

    ChartCard(title = "Top triggers") {
        if (counts.isEmpty()) {
            Text(
                "No trigger data yet - fill in \"What were you doing?\" in the questionnaire.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            return@ChartCard
        }
        val maxN = counts.first().second
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            counts.forEach { (label, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prettyTrigger(label),
                        modifier = Modifier.width(110.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(n.toFloat() / maxN)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        n.toString(),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(24.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/* ---------- Chart 3: Severity distribution (1..10) ----------------------- */

@Composable
private fun SeverityDistributionCard(reports: List<PanicReportEntity>) {
    val counts = (1..10).map { level -> reports.count { it.severity == level } }
    val maxCount = counts.max().coerceAtLeast(1)

    ChartCard(
        title = "How intense",
        subtitle = "Severity 1 (mild) - 10 (severe)",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            counts.forEachIndexed { i, c ->
                val level = i + 1
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
                        level.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/* ---------- Shared building blocks --------------------------------------- */

@Composable
private fun ChartCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Turn a raw activityBefore value (e.g. "work_study") into a display label. */
private fun prettyTrigger(raw: String): String =
    raw.replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w ->
            w.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() }
        }

/* ---------- Preview ------------------------------------------------------- */

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun StatsScreenPreview() {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val sample = listOf(
        PanicReportEntity(
            id = 1, timestampMs = now - 1 * day, severity = 8, detectedByModel = true,
            activityBefore = "work_study",
        ),
        PanicReportEntity(
            id = 2, timestampMs = now - 2 * day, severity = 6, detectedByModel = true,
            activityBefore = "driving_traffic",
        ),
        PanicReportEntity(
            id = 3, timestampMs = now - 2 * day, severity = 7, detectedByModel = false,
            activityBefore = "work_study",
        ),
        PanicReportEntity(
            id = 4, timestampMs = now - 5 * day, severity = 4, detectedByModel = true,
            activityBefore = "news_social",
        ),
        PanicReportEntity(
            id = 5, timestampMs = now - 9 * day, severity = 9, detectedByModel = true,
            activityBefore = "conversation_conflict",
        ),
    )
    AppTheme {
        StatsScreen(reports = sample)
    }
}
