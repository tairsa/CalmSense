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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.app.R
import com.example.app.data.EventType

/** Answers from the dissociation / flashback questionnaire. All optional. */
data class EventAnswers(
    val emotions: List<String> = emptyList(),
    val feeling: String? = null,
    val activityBefore: String? = null,
    val whatHelped: String? = null,
    val durationMinutes: Int? = null,
    val experiences: List<String> = emptyList(),
    val memoryClarity: String? = null,
    val senses: List<String> = emptyList(),
    val nowness: String? = null,
    val triggers: List<String> = emptyList(),
    val bodyReactions: List<String> = emptyList(),
)

/**
 * Questionnaire for a manual dissociation or flashback report. Same shape as
 * the anxiety [QuestionnaireScreen] but no severity: emotions first, then the
 * type's own questions, then the shared free-text ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventQuestionnaireScreen(
    type: EventType,
    onSkip: () -> Unit,
    onSave: (EventAnswers) -> Unit,
) {
    var emotions by remember { mutableStateOf(setOf<String>()) }
    var experiences by remember { mutableStateOf(setOf<String>()) }
    var memoryClarity by remember { mutableStateOf<String?>(null) }
    var senses by remember { mutableStateOf(setOf<String>()) }
    var nowness by remember { mutableStateOf<String?>(null) }
    var triggers by remember { mutableStateOf(setOf<String>()) }
    var bodyReactions by remember { mutableStateOf(setOf<String>()) }
    var feeling by remember { mutableStateOf("") }
    var activityBefore by remember { mutableStateOf("") }
    var whatHelped by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    val isFlashback = type == EventType.FLASHBACK

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (isFlashback) R.string.fb_quest_title else R.string.dis_quest_title))
                },
                actions = { TextButton(onClick = onSkip) { Text(stringResource(R.string.quest_skip)) } },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    onSave(
                        EventAnswers(
                            emotions = emotions.toList(),
                            feeling = feeling.ifBlank { null },
                            activityBefore = activityBefore.ifBlank { null },
                            whatHelped = whatHelped.ifBlank { null },
                            durationMinutes = durationText.toIntOrNull(),
                            experiences = experiences.toList(),
                            memoryClarity = memoryClarity,
                            senses = senses.toList(),
                            nowness = nowness,
                            triggers = triggers.toList(),
                            bodyReactions = bodyReactions.toList(),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.quest_save))
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            // Emotions first, as requested: the main thing this report is for.
            Label(stringResource(R.string.ev_emotions))
            Text(
                stringResource(R.string.ev_emotions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            MultiChips(EmotionOptions, emotions) { emotions = it }

            if (isFlashback) {
                Gap(); Label(stringResource(R.string.fb_senses))
                MultiChips(FlashbackSenseOptions, senses) { senses = it }
                Gap(); Label(stringResource(R.string.fb_nowness))
                SingleChips(NownessOptions, nowness) { nowness = it }
                Gap(); Label(stringResource(R.string.fb_triggers))
                MultiChips(FlashbackTriggerOptions, triggers) { triggers = it }
                Gap(); Label(stringResource(R.string.fb_body))
                MultiChips(FlashbackBodyReactionOptions, bodyReactions) { bodyReactions = it }
            } else {
                Gap(); Label(stringResource(R.string.dis_experiences))
                MultiChips(DissociationExperienceOptions, experiences) { experiences = it }
                Gap(); Label(stringResource(R.string.dis_memory))
                SingleChips(MemoryClarityOptions, memoryClarity) { memoryClarity = it }
            }

            Gap(); Label(stringResource(if (isFlashback) R.string.fb_feeling else R.string.dis_feeling))
            OutlinedTextField(
                value = feeling,
                onValueChange = { feeling = it },
                placeholder = {
                    Text(stringResource(if (isFlashback) R.string.fb_feeling_hint else R.string.dis_feeling_hint))
                },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = RoundedCornerShape(12.dp),
            )

            Gap(); Label(stringResource(R.string.quest_activity))
            OutlinedTextField(
                value = activityBefore,
                onValueChange = { activityBefore = it },
                placeholder = { Text(stringResource(R.string.quest_activity_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Gap(); Label(stringResource(R.string.ev_helped))
            OutlinedTextField(
                value = whatHelped,
                onValueChange = { whatHelped = it },
                placeholder = { Text(stringResource(R.string.ev_helped_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Gap(); Label(stringResource(R.string.quest_duration))
            OutlinedTextField(
                value = durationText,
                onValueChange = { v -> durationText = v.filter { it.isDigit() }.take(4) },
                placeholder = { Text(stringResource(R.string.quest_duration_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Gap() = Spacer(modifier = Modifier.height(20.dp))

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(bottom = 8.dp),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChips(options: List<SymptomOption>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o.key in selected
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - o.key else selected + o.key) },
                label = { Text(stringResource(o.labelRes)) },
                leadingIcon = if (on) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
            )
        }
    }
}

/** One answer or none; tapping the selected chip clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleChips(options: List<SymptomOption>, selected: String?, onChange: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o.key == selected
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) null else o.key) },
                label = { Text(stringResource(o.labelRes)) },
                leadingIcon = if (on) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
            )
        }
    }
}
