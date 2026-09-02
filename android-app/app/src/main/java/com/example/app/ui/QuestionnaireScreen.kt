package com.example.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.res.stringResource
import com.example.app.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionnaireScreen(
    onSkip: () -> Unit,
    onSave: (QuestionnaireAnswers) -> Unit,
) {
    var feeling by remember { mutableStateOf("") }
    var selectedSymptoms by remember { mutableStateOf(setOf<String>()) }
    var activityBefore by remember { mutableStateOf("") }
    var whatHelped by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quest_title)) },
                actions = {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.quest_skip)) }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        onSave(
                            QuestionnaireAnswers(
                                feeling = feeling.ifBlank { null },
                                symptoms = selectedSymptoms.toList(),
                                activityBefore = activityBefore.ifBlank { null },
                                whatHelped = whatHelped.ifBlank { null },
                                durationMinutes = durationText.toIntOrNull(),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.quest_save))
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.quest_all_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            SectionLabel(stringResource(R.string.quest_feeling))
            OutlinedTextField(
                value = feeling,
                onValueChange = { feeling = it },
                placeholder = { Text(stringResource(R.string.quest_feeling_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.quest_symptoms))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DefaultSymptoms.forEach { option ->
                    // Selection is tracked by the stable key, not the label, so
                    // what is stored does not depend on the display language.
                    val selected = option.key in selectedSymptoms
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedSymptoms = if (selected) selectedSymptoms - option.key
                                               else selectedSymptoms + option.key
                        },
                        label = { Text(stringResource(option.labelRes)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.quest_activity))
            OutlinedTextField(
                value = activityBefore,
                onValueChange = { activityBefore = it },
                placeholder = { Text(stringResource(R.string.quest_activity_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.quest_helped))
            OutlinedTextField(
                value = whatHelped,
                onValueChange = { whatHelped = it },
                placeholder = { Text(stringResource(R.string.quest_helped_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.quest_duration))
            OutlinedTextField(
                value = durationText,
                onValueChange = { v -> durationText = v.filter { it.isDigit() }.take(4) },
                placeholder = { Text(stringResource(R.string.quest_duration_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

data class QuestionnaireAnswers(
    val feeling: String?,
    val symptoms: List<String>,
    val activityBefore: String?,
    val whatHelped: String?,
    val durationMinutes: Int?,
)
