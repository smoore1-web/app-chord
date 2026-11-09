package com.stm.chordear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Instrument { PIANO, GUITAR }

/** Small built-in synthesizer: an additive piano and a Karplus–Strong plucked guitar. */
object Synth {
    private const val SR = 44100
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopFlag = false
    private var thread: Thread? = null

    val playing get() = thread?.isAlive == true

    fun notesFor(c: Chord, inst: Instrument): IntArray =
        if (inst == Instrument.PIANO) Voicing.piano(c)
        else Voicing.guitar(c)?.midi() ?: Voicing.piano(c)

    /**
     * Plays chords one after another. [onChord] fires (on the main thread) as each starts,
     * [onDone] when finished or stopped.
     */
    fun play(chords: List<Chord>, seconds: List<Double>, inst: Instrument,
             onChord: (Int) -> Unit = {}, onDone: () -> Unit = {}) {
        stop()
        stopFlag = false
        val t = Thread({
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(minBuf, SR / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var written = 0L
                for (i in chords.indices) {
                    if (stopFlag) break
                    val pcm = render(notesFor(chords[i], inst), inst, seconds.getOrElse(i) { 1.6 })
                    main.post { if (!stopFlag) onChord(i) }
                    var off = 0
                    while (off < pcm.size && !stopFlag) {
                        val n = track.write(pcm, off, min(2048, pcm.size - off))
                        if (n <= 0) break
                        off += n
                    }
                    written += off
                }
                // let the tail finish
                while (!stopFlag && track.playbackHeadPosition < written) Thread.sleep(20)
            } catch (_: Exception) {
            } finally {
                try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
                main.post(onDone)
            }
        }, "Synth")
        thread = t
        t.start()
    }

    fun stop() {
        stopFlag = true
        try { thread?.join(300) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun freq(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    fun render(notes: IntArray, inst: Instrument, seconds: Double): ShortArray {
        val n = (SR * seconds).toInt()
        val buf = FloatArray(n)
        if (inst == Instrument.PIANO) piano(notes, buf) else guitar(notes, buf)
        // fade the very end so chords join without clicks
        val fade = min(n, SR / 25)
        for (i in 0 until fade) buf[n - 1 - i] *= i / fade.toFloat()
        var peak = 1e-6f; for (v in buf) peak = max(peak, kotlin.math.abs(v))
        val g = 0.75f / max(peak, 0.35f)
        return ShortArray(n) { (buf[it] * g * 32767f).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun piano(notes: IntArray, out: FloatArray) {
        val n = out.size
        notes.forEachIndexed { idx, m ->
            val f = freq(m)
            val vel = if (idx == 0) 1.0f else 0.8f
            val damp = (f / 261.6).pow(0.35)
            for (h in 1..8) {
                val fh = f * h * sqrt(1 + 0.0004 * h * h)       // slight piano inharmonicity
                if (fh > SR / 2.2) break
                val amp = vel / h.toDouble().pow(1.6)
                val decay = (0.9 + 0.45 * h) * damp
                val w = 2 * PI * fh / SR
                val ph = Random.nextDouble() * 6.28
                for (i in 0 until n) {
                    val t = i.toDouble() / SR
                    val att = if (t < 0.004) t / 0.004 else 1.0
                    out[i] += (amp * att * exp(-t * decay) * sin(w * i + ph)).toFloat()
                }
            }
        }
    }

    /** Plucked steel string: exact-pitch partials shaped by pluck position, brighter at the attack. */
    private fun guitar(notes: IntArray, out: FloatArray) {
        val n = out.size
        notes.forEachIndexed { idx, m ->
            val start = (idx * 0.028 * SR).toInt()             // strum low to high
            if (start >= n) return@forEachIndexed
            val f = freq(m)
            val pluck = 0.2                                    // plucked ~1/5 along the string
            for (h in 1..14) {
                val fh = f * h * (1 + 0.00008 * h * h)
                if (fh > SR / 2.2) break
                val amp = kotlin.math.abs(sin(PI * h * pluck)) / h.toDouble().pow(1.05)
                val decay = 1.1 + 0.55 * h * (f / 196.0).pow(0.5)
                val w = 2 * PI * fh / SR
                val ph = Random.nextDouble() * 6.28
                for (i in start until n) {
                    val t = (i - start).toDouble() / SR
                    val att = if (t < 0.002) t / 0.002 else 1.0
                    out[i] += (0.8 * amp * att * exp(-t * decay) * sin(w * (i - start) + ph)).toFloat()
                }
            }
            // short pick noise
            for (i in start until min(n, start + SR / 200)) out[i] += (Random.nextFloat() * 2 - 1) * 0.08f * (1 - (i - start) / (SR / 200f))
        }
    }
}
