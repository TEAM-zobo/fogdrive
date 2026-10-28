package com.zobo.burung.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as PlatformTts

/**
 * Short spoken lines while driving, so the driver hears progress instead of looking at the screen.
 * Speaks through the Pleos TextToSpeech SDK (on-device synthesis: no network, no app key) and plays
 * as navigation guidance, so it ducks music like the built-in navigation. Where the Pleos speech
 * service is missing (a non-Pleos device) it falls back to the platform TextToSpeech.
 *
 * Lines are dropped rather than queued up: at most [MAX_QUEUED] wait, and minor lines within
 * [MIN_GAP_MS] of the last one are skipped, so a burst (several stamps at once, the 10x demo) never
 * turns into a long monologue. The queue is ours, one line at a time: the SDK reports progress per
 * engine, not per line.
 */
class Announcer(context: Context, private val enabled: () -> Boolean) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var pleos: PleosTts? = null
    private var platform: PlatformTts? = null
    private var started = false
    @Volatile private var ready = false
    private val pending = ArrayDeque<String>()
    private var current: String? = null
    private var lastAt = 0L
    @Volatile private var busy = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Ends the current line if the engine never reports back, so the queue (and Sfx) can't stall. */
    private val watchdog = Runnable { Log.w(TAG, "no end reported for \"$current\""); finished() }

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = finished()
        override fun onStop() = finished()
        override fun onError(type: ErrorType) {
            Log.w(TAG, "speak failed: $type")
            finished()
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    /** Engine starts lazily on the first line, so a parked car with voice off never binds one. */
    private fun start() {
        if (started) return
        started = true
        runCatching {
            val t = PleosTts(app, Mode.ON_DEVICE)
            pleos = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = synchronized(this@Announcer) {
                    runCatching {
                        t.setLocale(Locale.KOREA)
                        t.setAudioAttributes(attributes)
                        t.addEventListener(events)
                        ready = true
                        Log.i(TAG, "Pleos TextToSpeech ${PleosTts.version} ready (on-device)")
                    }.onFailure { usePlatform("Pleos TextToSpeech setup failed: $it") }
                    Unit
                }

                override fun onFailure(type: ErrorType) = synchronized(this@Announcer) {
                    usePlatform("Pleos TextToSpeech unavailable: $type")
                }
            })
        }.onFailure { synchronized(this) { usePlatform("Pleos TextToSpeech unavailable: $it") } }
    }

    private fun usePlatform(why: String) {
        Log.w(TAG, "$why, falling back to the platform TextToSpeech")
        runCatching { pleos?.release() }
        pleos = null
        ready = false
        platform = runCatching {
            PlatformTts(app) { status ->
                synchronized(this) {
                    val t = platform ?: return@synchronized
                    ready = status == PlatformTts.SUCCESS && t.setLanguage(Locale.KOREAN) >= PlatformTts.LANG_AVAILABLE
                    if (!ready) Log.w(TAG, "Korean platform TTS unavailable (status $status)")
                    t.setAudioAttributes(attributes)
                    t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) = finished()
                        override fun onStop(id: String?, interrupted: Boolean) = finished()
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) = finished()
                    })
                }
            }
        }.onFailure { Log.w(TAG, "no TextToSpeech engine", it) }.getOrNull()
    }

    /** Binds the engine ahead of the first line (called when recording starts). */
    @Synchronized
    fun warmUp() {
        if (enabled()) start()
    }

    /** A line is being spoken or waits to be (interface sounds keep quiet meanwhile). */
    val speaking: Boolean get() = busy

    /** Speaks [text]; [important] lines (arrival, mission, trip summary) skip the gap rule. */
    @Synchronized
    fun say(text: String, important: Boolean = false) {
        if (!enabled()) return
        start()
        if (!ready) return  // the first lines while the engine warms up are dropped, not delayed
        val now = SystemClock.elapsedRealtime()
        val waiting = pending.size + (if (current != null) 1 else 0)
        if (waiting >= MAX_QUEUED || (!important && now - lastAt < MIN_GAP_MS)) return
        lastAt = now
        pending.addLast(text)
        if (current == null) next()
    }

    @Synchronized
    private fun next() {
        val text = pending.removeFirstOrNull()
        current = text
        busy = text != null
        if (text == null) return
        val spoken = runCatching {
            pleos?.speak(text, true)
                ?: platform?.speak(text, PlatformTts.QUEUE_FLUSH, Bundle(), "fog-${SystemClock.elapsedRealtime()}")
                    ?.takeIf { it == PlatformTts.SUCCESS }
                ?: error("engine rejected the line")
        }
        if (spoken.isFailure) {
            Log.w(TAG, "speak failed", spoken.exceptionOrNull())
            current = null
            next()
            return
        }
        Log.i(TAG, "speaking: $text")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_BASE_MS + text.length * WATCHDOG_PER_CHAR_MS)
    }

    @Synchronized
    private fun finished() {
        main.removeCallbacks(watchdog)
        if (current == null) return
        current = null
        next()
    }

    @Synchronized
    fun shutdown() {
        main.removeCallbacks(watchdog)
        runCatching {
            pleos?.removeEventListener(events)
            pleos?.release()
        }
        platform?.shutdown()
        pleos = null
        platform = null
        started = false
        ready = false
        pending.clear()
        current = null
        busy = false
    }

    private companion object {
        const val TAG = "FogVoice"
        const val MAX_QUEUED = 2
        const val MIN_GAP_MS = 4_000L
        /** Korean speech runs about 6-7 syllables a second; the watchdog allows well over that. */
        const val WATCHDOG_BASE_MS = 4_000L
        const val WATCHDOG_PER_CHAR_MS = 250L
    }
}
