package com.example.app.data

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Plays pre-recorded calm-voice audio clips for the breathing overlay.
 *
 * Motivation: Android's built-in TextToSpeech always has a faintly synthetic
 * timbre. Real recorded audio (either the user's own voice or an AI-voice
 * MP3 from ElevenLabs) sounds noticeably more human, which is the whole
 * point of this screen.
 *
 * File layout - drop these into app/src/main/res/raw/ (all lowercase,
 * snake_case; Android is picky about that):
 *
 *     opening.mp3        "It will be okay. I am here with you. Let us breathe together."
 *     breathe_in.mp3     "Breathe in."
 *     breathe_out.mp3    "Breathe out."
 *     reassure_1.mp3     "You are doing great."
 *     reassure_2.mp3     "I am right here with you."
 *     reassure_3.mp3     "Keep going. You are safe."
 *     reassure_4.mp3     "Just keep breathing. One breath at a time."
 *     reassure_5.mp3     "Beautiful. Keep going."
 *     reassure_6.mp3     "You are not alone. I have got you."
 *
 * Missing files are tolerated - that clip just won't play, and a warning
 * is logged. That way the app keeps building while you're still generating
 * or recording the audio.
 *
 * Public API is unchanged from the TTS version, so MainActivity doesn't
 * need to change.
 */
class BreathingCoach {

    private var openingPlayer: MediaPlayer? = null
    private var breatheInPlayer: MediaPlayer? = null
    private var breatheOutPlayer: MediaPlayer? = null
    private var reassurePlayers: List<MediaPlayer> = emptyList()

    private var reassureIndex: Int = 0

    @Volatile
    private var isReady: Boolean = false

    /** [onReady] is called synchronously on the caller's thread once loaded. */
    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (isReady) {
            onReady?.invoke()
            return
        }
        val app = context.applicationContext
        openingPlayer = loadRaw(app, "opening")
        breatheInPlayer = loadRaw(app, "breathe_in")
        breatheOutPlayer = loadRaw(app, "breathe_out")
        reassurePlayers = (1..6).mapNotNull { loadRaw(app, "reassure_$it") }
        isReady = true
        onReady?.invoke()
    }

    /** Look up res/raw/<name>.mp3 by name and preload it. Returns null on miss. */
    private fun loadRaw(app: Context, name: String): MediaPlayer? {
        val resId = app.resources.getIdentifier(name, "raw", app.packageName)
        if (resId == 0) {
            Log.w(TAG, "res/raw/$name.mp3 not found - clip will be skipped")
            return null
        }
        return try {
            MediaPlayer.create(app, resId)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to create MediaPlayer for $name: $t")
            null
        }
    }

    /** Spoken once when the overlay opens. */
    fun speakOpening() = play(openingPlayer)

    fun speakIn() = play(breatheInPlayer)
    fun speakOut() = play(breatheOutPlayer)

    /** Speak the next reassurance in rotation. Safe to call between phases. */
    fun speakReassurance() {
        if (reassurePlayers.isEmpty()) return
        val player = reassurePlayers[reassureIndex % reassurePlayers.size]
        reassureIndex++
        play(player)
    }

    private fun play(player: MediaPlayer?) {
        if (!isReady || player == null) return
        try {
            if (player.isPlaying) player.pause()
            player.seekTo(0)
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "playback error: $t")
        }
    }

    fun shutdown() {
        val all = listOfNotNull(openingPlayer, breatheInPlayer, breatheOutPlayer) + reassurePlayers
        all.forEach { p ->
            try {
                if (p.isPlaying) p.stop()
            } catch (_: Throwable) {
            }
            try {
                p.release()
            } catch (_: Throwable) {
            }
        }
        openingPlayer = null
        breatheInPlayer = null
        breatheOutPlayer = null
        reassurePlayers = emptyList()
        reassureIndex = 0
        isReady = false
    }

    private companion object {
        const val TAG = "BreathingCoach"
    }
}
