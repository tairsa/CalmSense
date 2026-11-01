package com.example.app.ui

import com.example.app.R

/*
 * Chip options for the dissociation and flashback questionnaires. Same rule as
 * Symptoms.kt: the key (stable English) is stored and sent, the label is only
 * displayed. Never rename a key; add a new one instead.
 *
 * Sources: dissociation items follow the CADSS domains (depersonalisation,
 * derealisation, amnesia); flashback items follow Brewin's description
 * (involuntary, sensory, felt as happening now); emotions follow the DSM-5
 * PTSD negative emotional states plus numbness.
 */

val EmotionOptions = listOf(
    SymptomOption("Fear", R.string.emo_fear),
    SymptomOption("Horror", R.string.emo_horror),
    SymptomOption("Shame", R.string.emo_shame),
    SymptomOption("Guilt", R.string.emo_guilt),
    SymptomOption("Anger", R.string.emo_anger),
    SymptomOption("Sadness", R.string.emo_sadness),
    SymptomOption("Helplessness", R.string.emo_helplessness),
    SymptomOption("Disgust", R.string.emo_disgust),
    SymptomOption("Confusion", R.string.emo_confusion),
    SymptomOption("Numbness or emptiness", R.string.emo_numbness),
    SymptomOption("Loneliness", R.string.emo_loneliness),
)

val DissociationExperienceOptions = listOf(
    SymptomOption("Detached from body", R.string.dis_detached),
    SymptomOption("Watching myself from outside", R.string.dis_outside),
    SymptomOption("Body felt numb or not mine", R.string.dis_body_not_mine),
    SymptomOption("World felt unreal", R.string.dis_unreal),
    SymptomOption("Sounds and sights far away", R.string.dis_far_away),
    SymptomOption("Time slowed or sped up", R.string.dis_time),
    SymptomOption("Froze, hard to move or speak", R.string.dis_froze),
    SymptomOption("Mind went blank", R.string.dis_blank),
    SymptomOption("Lost time or memory gaps", R.string.dis_lost_time),
)

val MemoryClarityOptions = listOf(
    SymptomOption("clear", R.string.dis_memory_clear),
    SymptomOption("partial", R.string.dis_memory_partial),
    SymptomOption("none", R.string.dis_memory_none),
)

val FlashbackSenseOptions = listOf(
    SymptomOption("Images", R.string.fb_sense_images),
    SymptomOption("Sounds", R.string.fb_sense_sounds),
    SymptomOption("Smells", R.string.fb_sense_smells),
    SymptomOption("Body sensations or pain", R.string.fb_sense_body),
    SymptomOption("Taste", R.string.fb_sense_taste),
    SymptomOption("Emotions only", R.string.fb_sense_emotions),
)

val NownessOptions = listOf(
    SymptomOption("memory", R.string.fb_now_memory),
    SymptomOption("partial", R.string.fb_now_partial),
    SymptomOption("now", R.string.fb_now_now),
)

val FlashbackTriggerOptions = listOf(
    SymptomOption("Sound", R.string.fb_trig_sound),
    SymptomOption("Smell", R.string.fb_trig_smell),
    SymptomOption("Place", R.string.fb_trig_place),
    SymptomOption("Person", R.string.fb_trig_person),
    SymptomOption("News or media", R.string.fb_trig_media),
    SymptomOption("Body sensation", R.string.fb_trig_body),
    SymptomOption("Date or anniversary", R.string.fb_trig_date),
    SymptomOption("Don't know", R.string.fb_trig_unknown),
)

val FlashbackBodyReactionOptions = listOf(
    SymptomOption("Racing heart", R.string.symptom_racing_heart),
    SymptomOption("Sweating", R.string.fb_body_sweating),
    SymptomOption("Froze", R.string.fb_body_froze),
    SymptomOption("Urge to run or fight", R.string.fb_body_fight_flight),
    SymptomOption("Crying", R.string.fb_body_crying),
    SymptomOption("Shaking", R.string.symptom_trembling),
    SymptomOption("Felt detached", R.string.fb_body_detached),
)

private val allEventOptions = EmotionOptions + DissociationExperienceOptions + FlashbackSenseOptions +
    FlashbackTriggerOptions + FlashbackBodyReactionOptions

/** Display label for a stored chip key, or null (then show the key itself). */
fun eventChipLabelResOrNull(key: String): Int? = allEventOptions.firstOrNull { it.key == key }?.labelRes
