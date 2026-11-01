package com.example.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.app.R
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class GroundingMode { FIND, TENSE }

// "Find around you" tasks (5-4-3-2-1 style). Shuffled each time for variety.
private val FindTasks = listOf(
    R.string.gr_find_green, R.string.gr_find_round, R.string.gr_find_sounds,
    R.string.gr_find_touch, R.string.gr_find_blue, R.string.gr_find_square,
    R.string.gr_find_smell, R.string.gr_find_letters,
)

// Tense-and-release order, top to bottom (short progressive muscle relaxation).
private val BodyParts = listOf(
    R.string.gr_part_hands, R.string.gr_part_arms, R.string.gr_part_shoulders,
    R.string.gr_part_face, R.string.gr_part_belly, R.string.gr_part_legs, R.string.gr_part_feet,
)
private const val TENSE_SECONDS = 5
private const val RELEASE_SECONDS = 10

/**
 * Grounding exercise shown instead of breathing for dissociation and
 * flashback reports. "Done" is always available: finishing an exercise is
 * never required to reach the questionnaire.
 */
@Composable
fun GroundingOverlay(onDone: () -> Unit) {
    var mode by remember { mutableStateOf(GroundingMode.FIND) }
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                stringResource(R.string.gr_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            // Orientation to the present: the core of grounding during a flashback.
            Text(
                stringResource(R.string.gr_orientation, today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 20.dp)) {
                FilterChip(
                    selected = mode == GroundingMode.FIND,
                    onClick = { mode = GroundingMode.FIND },
                    label = { Text(stringResource(R.string.gr_mode_find)) },
                )
                FilterChip(
                    selected = mode == GroundingMode.TENSE,
                    onClick = { mode = GroundingMode.TENSE },
                    label = { Text(stringResource(R.string.gr_mode_tense)) },
                )
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (mode) {
                    GroundingMode.FIND -> FindAround()
                    GroundingMode.TENSE -> TenseRelease()
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gr_done))
            }
        }
    }
}

@Composable
private fun FindAround() {
    val tasks = remember { FindTasks.shuffled() }
    var i by remember { mutableIntStateOf(0) }
    Text(
        stringResource(tasks[i % tasks.size]),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.gr_find_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    OutlinedButton(onClick = { i++ }) { Text(stringResource(R.string.gr_next)) }
}

@Composable
private fun TenseRelease() {
    var part by remember { mutableIntStateOf(0) }
    var tensing by remember { mutableStateOf(true) }
    var secondsLeft by remember { mutableIntStateOf(TENSE_SECONDS) }

    // One coroutine drives the whole sequence; leaving this mode cancels it.
    LaunchedEffect(Unit) {
        while (part < BodyParts.size) {
            tensing = true
            for (s in TENSE_SECONDS downTo 1) { secondsLeft = s; delay(1_000) }
            tensing = false
            for (s in RELEASE_SECONDS downTo 1) { secondsLeft = s; delay(1_000) }
            part++
        }
    }

    if (part >= BodyParts.size) {
        Text(stringResource(R.string.gr_tense_finished), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        return
    }
    val partName = stringResource(BodyParts[part])
    Text(
        if (tensing) stringResource(R.string.gr_tense, partName) else stringResource(R.string.gr_release),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        secondsLeft.toString(),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        "${part + 1} / ${BodyParts.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
