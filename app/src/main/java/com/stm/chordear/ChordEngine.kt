package com.stm.chordear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.annotation.TargetApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One detected chord: root pitch class (0 = C) and quality index into [Music.SUFFIX]. */
data class Chord(val root: Int, val quality: Int)

/** Where the audio comes from. */
object AudioSources {
    private val FORMAT: AudioFormat = AudioFormat.Builder()
        .setSampleRate(ChordEngine.SR)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufSize() = max(
        AudioRecord.getMinBufferSize(ChordEngine.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        ChordEngine.HOP * 8
    )

    /** Microphone, with the phone's voice processing (noise suppression / auto gain) avoided where possible. */
    @SuppressLint("MissingPermission")
    fun mic(ctx: Context): AudioRecord {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        return AudioRecord(src, ChordEngine.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize())
    }

    /** The phone's own playback (Spotify, YouTube…), captured digitally. Android 10+. */
    @SuppressLint("MissingPermission")
    @TargetApi(29)
    fun phone(projection: MediaProjection): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(FORMAT)
            .setBufferSizeInBytes(bufSize())
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    val phoneCaptureSupported get() = Build.VERSION.SDK_INT >= 29
}

/**
 * Reads audio, keeps every spectrum for the final whole-clip analysis ([Analyzer]),
 * and gives a quick live guess roughly 10 times a second for the on-screen display.
 */
class ChordEngine(private val listener: Listener, private val makeRecord: () -> AudioRecord) {

    interface Listener {
        /** Called on the audio thread. [chord] is the live guess (null when unclear). */
        fun onFrame(chord: Chord?, level: Float, timeSec: Double)
        fun onError(message: String)
    }

    companion object {
        const val SR = 44100
        const val N = 16384          // ~2.7 Hz per bin: enough to separate low notes
        const val HOP = 4096         // ~93 ms between analyses
        private const val STABLE_HOPS = 5
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    private val cosT = FloatArray(N / 2)
    private val sinT = FloatArray(N / 2)
    private val rev = IntArray(N)
    private val window = FloatArray(N)
    private val kMin: Int
    private val kMax: Int
    private val midiOf: FloatArray
    private val freqOf: FloatArray
    private val templates = ArrayList<FloatArray>()
    private val tRoot = ArrayList<Int>()
    private val tQual = ArrayList<Int>()

    /** Stored for the final analysis. */
    val spectra = ArrayList<FloatArray>()
    val levels = ArrayList<Float>()
    val storeBins: Int

    init {
        val levelsN = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) {
            var r = 0; var x = i
            repeat(levelsN) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
            window[i] = (0.5 - 0.5 * cos(2 * PI * i / (N - 1))).toFloat()
        }
        for (i in 0 until N / 2) {
            cosT[i] = cos(2 * PI * i / N).toFloat()
            sinT[i] = (-sin(2 * PI * i / N)).toFloat()
        }
        kMin = (55.0 * N / SR).toInt() + 1
        kMax = minOf(N / 2 - 2, (1760.0 * N / SR).toInt())
        storeBins = minOf(N / 2, (2100.0 * N / SR).toInt() + 2)
        midiOf = FloatArray(kMax + 2)
        freqOf = FloatArray(kMax + 2)
        for (k in 1..kMax + 1) {
            val f = k.toDouble() * SR / N
            freqOf[k] = f.toFloat()
            midiOf[k] = (69 + 12 * ln(f / 440.0) / ln(2.0)).toFloat()
        }
        for (q in 0..1) {            // live display: major/minor only (steadier)
            for (r in 0 until 12) {
                val v = FloatArray(12)
                Music.INTERVALS[q].forEach { iv -> v[(r + iv) % 12] = 1f }
                for (i in 0 until 12) v[i] /= sqrt(3f)
                templates.add(v); tRoot.add(r); tQual.add(q)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ChordEngine").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun loop() {
        val rec: AudioRecord = try { makeRecord() } catch (e: Exception) {
            running = false; listener.onError("Couldn't start listening (${e.message})."); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); running = false
            listener.onError("Couldn't start listening — the microphone may be busy."); return
        }

        val ring = FloatArray(N)
        val buf = ShortArray(HOP)
        val re = FloatArray(N); val im = FloatArray(N); val mag = FloatArray(N / 2)
        val chroma = FloatArray(12)
        val ema = FloatArray(12)
        val v = FloatArray(12)
        var cand = -1; var candCount = 0; var current = -1
        var hops = 0L

        try {
            rec.startRecording()
            while (running) {
                var read = 0
                while (read < HOP && running) {
                    val r = rec.read(buf, read, HOP - read)
                    if (r <= 0) break
                    read += r
                }
                if (!running) break
                if (read < HOP) { listener.onError("Lost the audio input."); break }
                hops++

                System.arraycopy(ring, HOP, ring, 0, N - HOP)
                var e = 0.0
                for (i in 0 until HOP) {
                    val s = buf[i] / 32768f
                    ring[N - HOP + i] = s; e += s * s
                }
                val level = sqrt(e / HOP).toFloat()
                val t = hops * HOP.toDouble() / SR

                for (i in 0 until N) { re[i] = ring[i] * window[i]; im[i] = 0f }
                fft(re, im)
                for (k in 0 until N / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])

                // the ring buffer is only full after N/HOP hops
                if (hops >= N / HOP) synchronized(spectra) {
                    spectra.add(mag.copyOf(storeBins)); levels.add(level)
                }

                computeChroma(mag, chroma)
                var cm = 0f; for (x in chroma) cm = max(cm, x)
                for (i in 0 until 12) ema[i] = 0.7f * ema[i] + 0.3f * (if (cm > 0) chroma[i] / cm else 0f)

                if (level < 0.002f || hops < 4) {
                    candCount = 0; listener.onFrame(null, level, t); continue
                }
                var em = 0f; for (x in ema) em = max(em, x)
                if (em <= 0f) { listener.onFrame(null, level, t); continue }
                for (i in 0 until 12) v[i] = ln1p(15f * ema[i] / em)
                var nrm = 0f; for (x in v) nrm += x * x
                nrm = sqrt(nrm)
                var best = -1; var bestScore = -9f
                for (ti in templates.indices) {
                    val tp = templates[ti]
                    var d = 0f
                    for (i in 0 until 12) d += v[i] / nrm * tp[i]
                    if (ti == current) d += 0.04f
                    if (d > bestScore) { bestScore = d; best = ti }
                }
                if (best == cand) candCount++ else { cand = best; candCount = 1 }
                if (candCount >= STABLE_HOPS) current = cand
                listener.onFrame(if (current >= 0) Chord(tRoot[current], tQual[current]) else null, level, t)
            }
        } catch (e: Exception) {
            listener.onError("Audio error: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            running = false
        }
    }

    private fun computeChroma(mag: FloatArray, out: FloatArray) {
        out.fill(0f)
        var mx = 0f
        for (k in kMin..kMax) if (mag[k] > mx) mx = mag[k]
        if (mx <= 0f) return
        val thr = mx * 0.03f
        for (k in kMin..kMax) {
            val m = mag[k]
            if (m < thr || m <= mag[k - 1] || m < mag[k + 1]) continue
            val md = midiOf[k]
            val r = md.roundToInt()
            val w = cos((md - r) * PI).toFloat()
            out[((r % 12) + 12) % 12] += m * w * w
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size shr 1
            val step = N / size
            var i = 0
            while (i < N) {
                var k = 0
                for (j in 0 until half) {
                    val a = i + j; val b = a + half
                    val tr = re[b] * cosT[k] - im[b] * sinT[k]
                    val ti = re[b] * sinT[k] + im[b] * cosT[k]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
