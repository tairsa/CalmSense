package com.example.app.ui

import androidx.annotation.StringRes
import com.example.app.R

/**
 * Preset symptom chips shown in the post-event questionnaire. Order is the
 * order users see them; keep most-common first.
 *
 * Key and label are deliberately separate. The [key] is what gets stored on
 * the report and sent to the backend; the [labelRes] is only ever displayed.
 * Storing the translated text instead would split the same symptom into one
 * bucket per language, so a patient who switched languages would look like
 * two different people to the therapist views and to any aggregation over
 * symptoms.
 *
 * The keys are the original English strings rather than new slugs, so reports
 * written before the app was translated keep matching the ones written after.
 */
data class SymptomOption(
    val key: String,
    @StringRes val labelRes: Int,
)

val DefaultSymptoms = listOf(
    SymptomOption("Shortness of breath", R.string.symptom_shortness_of_breath),
    SymptomOption("Racing heart", R.string.symptom_racing_heart),
    SymptomOption("Chest tightness", R.string.symptom_chest_tightness),
    SymptomOption("Cold sweats", R.string.symptom_cold_sweats),
    SymptomOption("Trembling", R.string.symptom_trembling),
    SymptomOption("Dizziness", R.string.symptom_dizziness),
    SymptomOption("Nausea", R.string.symptom_nausea),
    SymptomOption("Numbness or tingling", R.string.symptom_numbness),
    SymptomOption("Choking sensation", R.string.symptom_choking),
    SymptomOption("Feeling of unreality", R.string.symptom_unreality),
    SymptomOption("Fear of losing control", R.string.symptom_losing_control),
    SymptomOption("Hot flashes or chills", R.string.symptom_hot_flashes),
)

/**
 * Display text for a stored symptom key, falling back to the key itself.
 *
 * The fallback matters: reports can contain symptoms that are no longer in
 * [DefaultSymptoms], and showing the stored English is better than showing
 * nothing.
 */
fun symptomLabelResOrNull(key: String): Int? =
    DefaultSymptoms.firstOrNull { it.key == key }?.labelRes
