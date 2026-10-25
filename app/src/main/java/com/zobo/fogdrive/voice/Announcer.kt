package com.zobo.fogdrive.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Short spoken lines while driving, so the driver hears progress instead of looking at the screen.
 * Plays as navigation guidance (ducks music like the built-in navigation). Lines are dropped rather
 * than queued up: at most [MAX_QUEUED] wait, and minor lines within [MIN_GAP_MS] of the last one
 * are skipped, so a burst (several stamps at once, the 10x demo) never turns into a long monologue.
 * Uses the platform TextToSpeech until the Pleos TextToSpeech SDK is published.
 */
class Announcer(context: Context, private val enabled: () -> Boolean) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var lastAt = 0L
    @Volatile private var queued = 0
    private val app = context.applicationContext

    /** Engine starts lazily on the first line, so a parked car with voice off never binds one. */
    private fun engine(): TextToSpeech? {
        tts?.let { return it }
        return runCatching {
            TextToSpeech(app) { status ->
                val t = tts ?: return@TextToSpeech
                ready = status == TextToSpeech.SUCCESS &&
                    t.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
                if (!ready) Log.w(TAG, "Korean TTS unavailable (status $status)")
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = dequeued()
                    // Dropped or interrupted lines end here, not in onDone: they must free their slot too.
                    override fun onStop(id: String?, interrupted: Boolean) = dequeued()
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = dequeued()
                })
            }.also { tts = it }
        }.onFailure { Log.w(TAG, "TTS engine unavailable", it) }.getOrNull()
    }

    /** Binds the engine ahead of the first line (called when recording starts). */
    @Synchronized
    fun warmUp() {
        if (enabled()) engine()
    }

    /** A line is being spoken or waits to be (interface sounds keep quiet meanwhile). */
    val speaking: Boolean get() = queued > 0

    @Synchronized
    private fun dequeued() {
        if (queued > 0) queued--
    }

    /** Speaks [text]; [important] lines (arrival, mission, trip summary) skip the gap rule. */
    @Synchronized
    fun say(text: String, important: Boolean = false) {
        if (!enabled()) return
        val t = engine() ?: return
        if (!ready) return  // the first lines while the engine warms up are dropped, not delayed
        val now = SystemClock.elapsedRealtime()
        if (queued >= MAX_QUEUED || (!important && now - lastAt < MIN_GAP_MS)) return
        // A rejected line gets no onDone/onError, so it must not hold a queue slot.
        if (t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "fog-$now") != TextToSpeech.SUCCESS) return
        lastAt = now
        queued++
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        queued = 0
    }

    private companion object {
        const val TAG = "FogVoice"
        const val MAX_QUEUED = 2
        const val MIN_GAP_MS = 4_000L
    }
}
