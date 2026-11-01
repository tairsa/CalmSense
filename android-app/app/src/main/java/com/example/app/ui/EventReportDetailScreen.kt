package com.example.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.R
import com.example.app.data.EventReportEntity
import com.example.app.data.EventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Read-only view of one dissociation or flashback report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventReportDetailScreen(report: EventReportEntity?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(report?.let { stringResource(eventTypeLabelRes(it.type)) } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { inner ->
        if (report == null) {
            Text(stringResource(R.string.ev_not_found), modifier = Modifier.padding(inner).padding(24.dp))
            return@Scaffold
        }
        val isFlashback = report.type == EventType.FLASHBACK
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                Instant.ofEpochMilli(report.timestampMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Chips(stringResource(R.string.ev_emotions), report.emotions)
            if (isFlashback) {
                Chips(stringResource(R.string.fb_senses), report.senses)
                report.nowness?.let { Field(stringResource(R.string.fb_nowness), optionLabel(NownessOptions, it)) }
                Chips(stringResource(R.string.fb_triggers), report.triggers)
                Chips(stringResource(R.string.fb_body), report.bodyReactions)
            } else {
                Chips(stringResource(R.string.dis_experiences), report.experiences)
                report.memoryClarity?.let { Field(stringResource(R.string.dis_memory), optionLabel(MemoryClarityOptions, it)) }
            }
            report.feeling?.let { Field(stringResource(if (isFlashback) R.string.fb_feeling else R.string.dis_feeling), it) }
            report.activityBefore?.let { Field(stringResource(R.string.quest_activity), it) }
            report.whatHelped?.let { Field(stringResource(R.string.ev_helped), it) }
            report.durationMinutes?.let { Field(stringResource(R.string.quest_duration), it.toString()) }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

fun eventTypeLabelRes(type: EventType): Int = when (type) {
    EventType.ANXIETY -> R.string.ev_type_anxiety
    EventType.DISSOCIATION -> R.string.ev_type_dissociation
    EventType.FLASHBACK -> R.string.ev_type_flashback
}

@Composable
private fun optionLabel(options: List<SymptomOption>, key: String): String =
    options.firstOrNull { it.key == key }?.let { stringResource(it.labelRes) } ?: key

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: String, keys: List<String>) {
    if (keys.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { k ->
            val label = eventChipLabelResOrNull(k)?.let { stringResource(it) } ?: k
            AssistChip(onClick = {}, label = { Text(label) })
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(value, modifier = Modifier.padding(12.dp))
        }
    }
}
