package com.example.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.app.ui.theme.AppTheme

/* -------------------------------------------------------------------------
 * Data model
 *
 * One self-contained data class plus four enums. Single-choice answers are
 * nullable (the user is recovering from a panic attack, so nothing is
 * pre-selected and skipping is fine). Each enum carries a human-readable
 * [label] for the UI; persist the enum name (or label) when you send this to
 * Supabase later.
 * ------------------------------------------------------------------------- */

enum class TriggerActivity(val label: String) {
    WORK_STUDY("Work / Studying"),
    DRIVING_TRAFFIC("Driving / Traffic"),
    CONVERSATION_CONFLICT("Conversation / Conflict"),
    NEWS_SOCIAL("News / Social Media"),
    RESTING_SLEEPING("Resting / Sleeping"),
    OTHER("Other"),
}

enum class PhysicalSymptom(val label: String) {
    RAPID_HEART_RATE("Rapid heart rate"),
    SHORTNESS_OF_BREATH("Shortness of breath"),
    DIZZINESS("Dizziness / Unsteadiness"),
    SWEATING("Sweating / Hot flashes"),
    SHAKING("Shaking / Trembling"),
}

enum class LocationContext(val label: String) {
    HOME("At Home"),
    OUTDOORS("Outdoors (Street, Park)"),
    PUBLIC_SPACE("Public Space (Mall, Bus, Train)"),
    WORK_UNIVERSITY("Work / University"),
    VEHICLE("Inside a Vehicle"),
}

enum class CopingMechanism(val label: String) {
    BREATHING_APP("Breathing exercises in the app"),
    FRESH_AIR("Going outside for fresh air"),
    TALKED_TO_SOMEONE("Talking to someone close"),
    PASSED_ON_OWN("It passed on its own"),
    OTHER("Other"),
}

/** Everything the post-panic questionnaire collects. Ready to map to a row. */
data class PanicQuestionnaireData(
    val trigger: TriggerActivity? = null,
    val triggerOtherText: String? = null,   // only set when trigger == OTHER
    val symptoms: Set<PhysicalSymptom> = emptySet(),
    val location: LocationContext? = null,
    val coping: CopingMechanism? = null,
    val intensity: Int = 3,                  // 1 (Mild) .. 5 (Severe)
)

/* -------------------------------------------------------------------------
 * Screen
 * ------------------------------------------------------------------------- */

@Composable
fun PanicQuestionnaireScreen(
    onSave: (PanicQuestionnaireData) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trigger by remember { mutableStateOf<TriggerActivity?>(null) }
    var triggerOther by remember { mutableStateOf("") }
    val symptoms = remember { mutableStateListOf<PhysicalSymptom>() }
    var location by remember { mutableStateOf<LocationContext?>(null) }
    var coping by remember { mutableStateOf<CopingMechanism?>(null) }
    var intensity by remember { mutableIntStateOf(3) }

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
            CalmHeader()

            QuestionCard(
                number = 1,
                title = "What were you doing?",
                subtitle = "Choose the closest match",
            ) {
                TriggerActivity.entries.forEach { option ->
                    SingleChoiceRow(
                        text = option.label,
                        selected = trigger == option,
                        onSelect = { trigger = option },
                    )
                }
                AnimatedVisibility(visible = trigger == TriggerActivity.OTHER) {
                    OutlinedTextField(
                        value = triggerOther,
                        onValueChange = { triggerOther = it },
                        label = { Text("Tell us more (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }

            QuestionCard(
                number = 2,
                title = "How did your body feel?",
                subtitle = "Select all that apply",
            ) {
                PhysicalSymptom.entries.forEach { option ->
                    MultiChoiceRow(
                        text = option.label,
                        checked = symptoms.contains(option),
                        onToggle = {
                            if (symptoms.contains(option)) symptoms.remove(option)
                            else symptoms.add(option)
                        },
                    )
                }
            }

            QuestionCard(
                number = 3,
                title = "Where were you?",
                subtitle = "Choose one",
            ) {
                LocationContext.entries.forEach { option ->
                    SingleChoiceRow(
                        text = option.label,
                        selected = location == option,
                        onSelect = { location = option },
                    )
                }
            }

            QuestionCard(
                number = 4,
                title = "What helped you through it?",
                subtitle = "Choose one",
            ) {
                CopingMechanism.entries.forEach { option ->
                    SingleChoiceRow(
                        text = option.label,
                        selected = coping == option,
                        onSelect = { coping = option },
                    )
                }
            }

            QuestionCard(
                number = 5,
                title = "How intense did it feel?",
                subtitle = "1 = Mild  -  5 = Severe",
            ) {
                IntensitySelector(value = intensity, onValueChange = { intensity = it })
            }

            Button(
                onClick = {
                    onSave(
                        PanicQuestionnaireData(
                            trigger = trigger,
                            triggerOtherText = triggerOther.trim()
                                .ifEmpty { null }
                                ?.takeIf { trigger == TriggerActivity.OTHER },
                            symptoms = symptoms.toSet(),
                            location = location,
                            coping = coping,
                            intensity = intensity,
                        )
                    )
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save & Close", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* -------------------------------------------------------------------------
 * Building blocks
 * ------------------------------------------------------------------------- */

@Composable
private fun CalmHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Spa,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "You got through it",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "When you feel ready, a few gentle questions help us understand " +
                "what happened. There are no wrong answers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QuestionCard(
    number: Int,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SingleChoiceRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MultiChoiceRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun IntensitySelector(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (1..5).forEach { level ->
                val selected = level == value
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onValueChange(level) },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = level.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Mild",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = "Severe",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/* -------------------------------------------------------------------------
 * Preview
 * ------------------------------------------------------------------------- */

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun PanicQuestionnaireScreenPreview() {
    AppTheme {
        PanicQuestionnaireScreen(onSave = {})
    }
}
