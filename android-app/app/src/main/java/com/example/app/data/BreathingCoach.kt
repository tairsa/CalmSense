package com.example.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Wraps Android's TextToSpeech for the breathing-exercise overlay.
 *
 * Trade-off: built-in TTS always has a faintly synthetic timbre. To make
 * the guide sound truly human we would need to ship pre-recorded audio
 * (either user-recorded or AI-generated clips placed in res/raw). See
 * the project README for that path. This wrapper does as much as TTS
 * allows: slow rate, low pitch, prefer the highest-quality installed
 * voice, and weave reassuring phrases into the breathing cycle.
 */
class BreathingCoach {

    private var tts: TextToSpeech? = null
    @Volatile private var isReady: Boolean = false

    private val reassurances: List<String> = listOf(
        "You are doing great.",
        "I am right here with you.",
        "Keep going. You are safe.",
        "Just keep breathing. One breath at a time.",
        "Beautiful. Keep going.",
        "You are not alone. I have got you.",
    )
    private var reassuranceIndex: Int = 0

    /** [onReady] is called on the main thread once the engine is initialized. */
    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) {
            if (isReady) onReady?.invoke()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.US
                    // Slow + slightly lower pitch -> calmer perceived voice.
                    // 1.0 is the default. Below 0.7 starts sounding "off"; below
                    // 0.85 pitch begins distorting on some engines.
                    engine.setSpeechRate(0.72f)
                    engine.setPitch(0.90f)
                    pickBestVoice(engine)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {}
                        @Deprecated("deprecated in API level 21", ReplaceWith(""))
                        override fun onError(utteranceId: String?) {}
                    })
                }
                isReady = true
                onReady?.invoke()
            }
        }
    }

    /**
     * Pick the highest-quality English voice the device has installed.
     * Heuristic: prefer non-default voices, prefer female (typically used
     * for guided meditation), prefer those with the highest declared quality.
     * Quietly falls back to the engine default if anything goes wrong.
     */
    private fun pickBestVoice(engine: TextToSpeech) {
        try {
            val voices = engine.voices ?: return
            val best = voices
                .filter { it.locale.language == Locale.US.language }
                .filter { !it.isNetworkConnectionRequired }
                .maxByOrNull { v ->
                    var score = v.quality   // android Voice quality 100..500
                    if (v.name.contains("female", ignoreCase = true)) score += 50
                    if (v.name.contains("network", ignoreCase = true)) score += 25
                    if (v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) score -= 1000
                    score
                }
            if (best != null) engine.voice = best
        } catch (_: Throwable) {
            // ignore — fall back to engine default
        }
    }

    /** Spoken once when the overlay opens. */
    fun speakOpening() = speak(
        "It will be okay. I am here with you. Let us breathe together.",
        id = "opening",
        flush = true,
    )

    fun speakIn() = speak("Breathe in.", id = "in", flush = false)
    fun speakOut() = speak("Breathe out.", id = "out", flush = false)

    /** Speak the next reassurance in rotation. Safe to call between phases. */
    fun speakReassurance() {
        val text = reassurances[reassuranceIndex % reassurances.size]
        reassuranceIndex++
        speak(text, id = "reassure-$reassuranceIndex", flush = false)
    }

    private fun speak(text: String, id: String, flush: Boolean) {
        val engine = tts ?: return
        if (!isReady) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, id)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        reassuranceIndex = 0
    }
}
