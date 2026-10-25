package com.zobo.fogdrive.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Interface sounds, synthesised at start-up instead of shipped as audio files (no bytes in the APK).
 * Meant to stay in the background: near-pure tones in the low-mid register, slow soft attacks, a dark
 * low-pass and a faint room, at a modest level; the settings slider scales them further within the
 * car's system-sound volume. Each is one to three sine notes (a tap of ~0.1 s up to a chime of ~1 s)
 * with an exponential decay, preloaded into a static AudioTrack for low latency and played as an
 * interface sound, so the car mixes them like its own UI's. One sound per kind of action:
 */
enum class Sound {
    /** A button, list item or chip: a soft low tap. */
    TAP,
    /** Moving to another screen (tabs): a soft upward glide. */
    SCREEN,
    /** A switch turned on / off: two notes up / down. */
    TOGGLE_ON,
    TOGGLE_OFF,
    /** Driving begins (the driving view opens): a gentle three-note chime. */
    START,
    /** Parked again (back from the driving view): two notes falling. */
    STOP,
    /** A 읍·면·동 reached an exploration step: a soft rising sparkle. */
    ACHIEVE,
}

object Sfx {
    private const val TAG = "Sfx"
    private const val SAMPLE_RATE = 48_000
    /** The same sound closer together than this plays once (a fast double tap shouldn't stack). */
    private const val MIN_GAP_MS = 45L
    /**
     * A sound that would start this long after its tap is dropped: when the app stalls (a heavy map
     * frame, the 10x demo), late sounds would otherwise come out after the fact and pile up back to
     * back, which sounds dragged out. About the point where a click stops feeling attached to a tap.
     */
    private const val MAX_LATE_MS = 150L

    // Audio priority: plays are not held up behind the map's render and decode threads.
    private val worker = Handler(HandlerThread("Sfx", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }.looper)
    private val tracks = HashMap<Sound, AudioTrack>()
    private var enabled: () -> Boolean = { false }
    private var volume: () -> Float = { 1f }
    @Volatile private var lastAt = 0L
    private var lastSound: Sound? = null

    /**
     * Builds the sounds off the main thread. [enabled] (the settings switch) and [volume] (the
     * settings slider, 0–1) are read on every play.
     */
    fun init(enabled: () -> Boolean, volume: () -> Float) {
        this.enabled = enabled
        this.volume = volume
        worker.post {
            if (tracks.isNotEmpty()) return@post
            for (s in Sound.entries) {
                runCatching { tracks[s] = build(pcm(s)) }.onFailure { Log.w(TAG, "sound $s unavailable", it) }
            }
        }
    }

    /** When a control last made its own sound (uptime ms): that tap is not ticked over. */
    @Volatile var lastOwnAt = 0L
        private set

    /**
     * Plays [sound]. [byControl]: a control made it for the tap being handled (a tab, a switch), so
     * the generic tap tick is skipped for that tap; false for sounds not caused by a tap (driving).
     * [at]: when the tap happened (uptime ms, e.g. the finger lifting; default: now for a control's
     * sound); a tap sound that can't start within [MAX_LATE_MS] of it is skipped rather than played
     * late. Sounds not caused by a tap (driving starts, a milestone) always play.
     */
    fun play(sound: Sound, byControl: Boolean = true, at: Long? = null) {
        if (!enabled()) return
        val now = SystemClock.uptimeMillis()
        val since = at ?: now.takeIf { byControl }
        if (since != null && now - since > MAX_LATE_MS) return
        // Only a repeat is dropped: a chime right after a tap (driving starts) still plays.
        if (sound == lastSound && now - lastAt < MIN_GAP_MS) return
        lastAt = now
        lastSound = sound
        if (byControl) lastOwnAt = now
        val gain = volume().coerceIn(0f, 1f)
        worker.post {
            val t = tracks[sound] ?: return@post
            val late = if (since == null) 0L else SystemClock.uptimeMillis() - since
            if (late > MAX_LATE_MS) {
                Log.d(TAG, "$sound dropped, ${late} ms late")
                return@post
            }
            runCatching {
                t.setVolume(gain)
                // A static track replays from the start after stop + reload.
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.reloadStaticData()
                t.play()
            }.onFailure { Log.w(TAG, "$sound failed", it) }
        }
    }

    private fun build(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            // Fast track: mixed by the real-time FastMixer, so system load doesn't stretch or stutter it.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    /**
     * One note of an additive tone: [f] Hz (with a slight downward pitch settle of
     * [bend] over the first 15 ms, like a struck bar), starting [at] s in, rising over [attack] s and
     * dying away over [decay] s. [partials] are (frequency ratio, level): the upper ones fade faster,
     * so a note starts with a little shimmer and ends round.
     */
    private class Note(
        val at: Double,
        val f: Double,
        val attack: Double,
        val decay: Double,
        val gain: Double = 1.0,
        val bend: Double = 0.0,
        val partials: List<Pair<Double, Double>> = SOFT,
    )

    // Pitches sit low (G3–C6, mostly G4–D5) and every note swells in over 14–35 ms: no edge to the
    // start, nothing piercing. Levels are about 3–4 dB below the first version, which was too sharp.
    private fun pcm(sound: Sound): ShortArray = when (sound) {
        // A soft, low "pup" (G4): felt more than heard.
        Sound.TAP -> render(0.20, Note(0.0, 392.0, attack = 0.014, decay = 0.035, partials = SOFT))
        // A rising fourth, breathed in: A4 then D5.
        Sound.SCREEN -> render(
            0.18,
            Note(0.00, 440.0, attack = 0.035, decay = 0.090, partials = SOFT),
            Note(0.08, 587.3, attack = 0.035, decay = 0.110, gain = 0.7, partials = SOFT),
        )
        // A major third up / down, quietly: G4 – B4.
        Sound.TOGGLE_ON -> render(
            0.20,
            Note(0.000, 392.0, attack = 0.016, decay = 0.040, gain = 0.8, partials = SOFT),
            Note(0.075, 493.9, attack = 0.016, decay = 0.055, partials = SOFT),
        )
        Sound.TOGGLE_OFF -> render(
            0.20,
            Note(0.000, 493.9, attack = 0.016, decay = 0.040, gain = 0.8, partials = SOFT),
            Note(0.075, 392.0, attack = 0.016, decay = 0.055, partials = SOFT),
        )
        // G major, arpeggiated softly and left to ring: G4 – B4 – D5.
        Sound.START -> render(
            0.18,
            Note(0.00, 392.0, attack = 0.030, decay = 0.22, partials = WARM),
            Note(0.11, 493.9, attack = 0.030, decay = 0.22, gain = 0.85, partials = WARM),
            Note(0.22, 587.3, attack = 0.032, decay = 0.28, gain = 0.75, partials = WARM),
        )
        // A rising sparkle, gently: C5 – E5 – G5 – C6 (an octave below a bright chime).
        Sound.ACHIEVE -> render(
            0.18,
            Note(0.00, 523.3, attack = 0.030, decay = 0.16, gain = 0.8, partials = WARM),
            Note(0.09, 659.3, attack = 0.030, decay = 0.18, gain = 0.75, partials = WARM),
            Note(0.18, 784.0, attack = 0.030, decay = 0.22, gain = 0.7, partials = WARM),
            Note(0.27, 1_046.5, attack = 0.034, decay = 0.30, gain = 0.55, partials = WARM),
        )
        // Settling down: D4 – G3.
        Sound.STOP -> render(
            0.20,
            Note(0.00, 293.7, attack = 0.030, decay = 0.18, gain = 0.85, partials = WARM),
            Note(0.13, 196.0, attack = 0.032, decay = 0.26, partials = WARM),
        )
    }

    private fun render(volume: Double, vararg notes: Note): ShortArray {
        val seconds = notes.maxOf { it.at + it.attack + it.decay * TAIL_DECAYS } + ROOM_TAIL_S
        val n = (SAMPLE_RATE * seconds).toInt()
        val mix = DoubleArray(n)
        for (note in notes) {
            val start = (note.at * SAMPLE_RATE).toInt()
            val len = ((note.attack + note.decay * TAIL_DECAYS) * SAMPLE_RATE).toInt()
            val phases = DoubleArray(note.partials.size)
            for (i in 0 until len) {
                val k = start + i
                if (k >= n) break
                val t = i.toDouble() / SAMPLE_RATE
                val settle = 1 + note.bend * exp(-t / 0.015)
                // Raised-cosine attack: no corner at the start, so no click.
                val rise = if (t < note.attack) 0.5 - 0.5 * kotlin.math.cos(PI * t / note.attack) else 1.0
                var v = 0.0
                note.partials.forEachIndexed { p, (ratio, level) ->
                    phases[p] += 2 * PI * note.f * settle * ratio / SAMPLE_RATE
                    // Higher partials die sooner (by their ratio), so a note ends as a pure tone.
                    v += level * exp(-t / (note.decay / ratio)) * sin(phases[p])
                }
                // Smooth fade over the last 10 % so the tail never ends in a step.
                val fade = min(1.0, (len - i).toDouble() / (len * 0.1))
                mix[k] += note.gain * rise * fade * v
            }
        }
        // A faint small-room reflection or two: depth without echo.
        val room = DoubleArray(n)
        for ((delayMs, level) in ROOM_TAPS) {
            val d = (SAMPLE_RATE * delayMs / 1000).toInt()
            for (k in d until n) room[k] += mix[k - d] * level
        }
        for (k in 0 until n) mix[k] += room[k]
        // Low-pass (one pole, ~1.6 kHz): nothing bright or sharp is left.
        val a = exp(-2 * PI * LOWPASS_HZ / SAMPLE_RATE)
        var y = 0.0
        for (k in 0 until n) {
            y = (1 - a) * mix[k] + a * y
            mix[k] = y
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(n) { (mix[it] / peak * volume * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Almost a pure tone: a whisper of octave only (taps, switches, screen changes). */
    private val SOFT = listOf(1.0 to 1.0, 2.0 to 0.03)
    /** A little warmth for the chimes, no bright partials. */
    private val WARM = listOf(1.0 to 1.0, 2.0 to 0.08)
    /** A note is cut after this many decay times (about -35 dB: inaudible under the next sound). */
    private const val TAIL_DECAYS = 4.0
    private val ROOM_TAPS = listOf(23.0 to 0.10, 41.0 to 0.05)
    private const val ROOM_TAIL_S = 0.08
    private const val LOWPASS_HZ = 1_600.0
}
