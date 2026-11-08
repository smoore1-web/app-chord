package com.stm.chordear

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

/**
 * Listens to the microphone and reports the chord it hears roughly 10 times a second.
 * Pipeline: FFT -> spectral peaks -> 12-bin chroma (+ bass chroma) -> smoothing ->
 * template matching -> stability filter.
 */
class ChordEngine(private val listener: Listener) {

    interface Listener {
        /** Called on the audio thread. [chord] is null when it's too quiet / unclear. */
        fun onFrame(chord: Chord?, level: Float, timeSec: Double)
        fun onError(message: String)
    }

    companion object {
        const val SR = 44100
        const val N = 16384          // ~2.7 Hz per bin: enough to separate low notes
        const val HOP = 4096         // ~93 ms between analyses
        private const val STABLE_HOPS = 3
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

    init {
        val levels = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) {
            var r = 0; var x = i
            repeat(levels) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
            window[i] = (0.5 - 0.5 * cos(2 * PI * i / (N - 1))).toFloat()
        }
        for (i in 0 until N / 2) {
            cosT[i] = cos(2 * PI * i / N).toFloat()
            sinT[i] = (-sin(2 * PI * i / N)).toFloat()
        }
        kMin = (55.0 * N / SR).toInt() + 1
        kMax = minOf(N / 2 - 2, (1760.0 * N / SR).toInt())
        midiOf = FloatArray(kMax + 2)
        freqOf = FloatArray(kMax + 2)
        for (k in 1..kMax + 1) {
            val f = k.toDouble() * SR / N
            freqOf[k] = f.toFloat()
            midiOf[k] = (69 + 12 * ln(f / 440.0) / ln(2.0)).toFloat()
        }
        for (q in Music.INTERVALS.indices) {
            for (r in 0 until 12) {
                val v = FloatArray(12)
                Music.INTERVALS[q].forEachIndexed { i, iv -> v[(r + iv) % 12] = if (i == 3) 0.75f else 1f }
                var n = 0f; for (x in v) n += x * x
                val s = sqrt(n); for (i in 0 until 12) v[i] /= s
                templates.add(v); tRoot.add(r); tQual.add(q)
            }
        }
    }

    val isRunning get() = running

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
        try { thread?.join(600) } catch (_: InterruptedException) {}
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, HOP * 8)
            )
        } catch (e: Exception) {
            running = false; listener.onError("Couldn't open the microphone."); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); running = false
            listener.onError("Microphone is busy or permission was denied."); return
        }

        val ring = FloatArray(N)
        val buf = ShortArray(HOP)
        val re = FloatArray(N); val im = FloatArray(N); val mag = FloatArray(N / 2)
        val chroma = FloatArray(12); val bass = FloatArray(12)
        val ema = FloatArray(12); val emb = FloatArray(12)
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
                if (read < HOP) { listener.onError("Lost the microphone."); break }
                hops++

                System.arraycopy(ring, HOP, ring, 0, N - HOP)
                var e = 0.0
                for (i in 0 until HOP) {
                    val s = buf[i] / 32768f
                    ring[N - HOP + i] = s; e += s * s
                }
                val level = sqrt(e / HOP).toFloat()

                for (i in 0 until N) { re[i] = ring[i] * window[i]; im[i] = 0f }
                fft(re, im)
                for (k in 0 until N / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                computeChroma(mag, chroma, bass)

                var cm = 0f; for (x in chroma) cm = max(cm, x)
                var bm = 0f; for (x in bass) bm = max(bm, x)
                for (i in 0 until 12) {
                    ema[i] = 0.55f * ema[i] + 0.45f * (if (cm > 0) chroma[i] / cm else 0f)
                    emb[i] = 0.55f * emb[i] + 0.45f * (if (bm > 0) bass[i] / bm else 0f)
                }
                val t = hops * HOP.toDouble() / SR

                if (level < 0.004f || hops < 4) {
                    candCount = 0
                    listener.onFrame(null, level, t); continue
                }

                var em = 0f; for (x in ema) em = max(em, x)
                if (em <= 0f) { listener.onFrame(null, level, t); continue }
                for (i in 0 until 12) v[i] = ln1p(20f * ema[i] / em)
                var nrm = 0f; for (x in v) nrm += x * x
                nrm = sqrt(nrm)
                var ebm = 0f; for (x in emb) ebm = max(ebm, x)

                var best = -1; var bestScore = -9f; var second = -9f
                for (ti in templates.indices) {
                    val tp = templates[ti]
                    var d = 0f
                    for (i in 0 until 12) d += v[i] / nrm * tp[i]
                    if (ebm > 0) d += 0.12f * emb[tRoot[ti]] / ebm
                    d -= Music.PENALTY[tQual[ti]]
                    if (ti == current) d += 0.03f    // a little stickiness
                    if (d > bestScore) { second = bestScore; bestScore = d; best = ti }
                    else if (d > second) second = d
                }

                if (best == cand) candCount++ else { cand = best; candCount = 1 }
                if (candCount >= STABLE_HOPS) current = cand
                val out = if (current >= 0 && bestScore > 0.55f) Chord(tRoot[current], tQual[current]) else null
                listener.onFrame(out, level, t)
            }
        } catch (e: Exception) {
            listener.onError("Audio error: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            running = false
        }
    }

    private fun computeChroma(mag: FloatArray, out: FloatArray, bass: FloatArray) {
        out.fill(0f); bass.fill(0f)
        var mx = 0f
        for (k in kMin..kMax) if (mag[k] > mx) mx = mag[k]
        if (mx <= 0f) return
        val thr = mx * 0.02f
        for (k in kMin..kMax) {
            val m = mag[k]
            if (m < thr || m <= mag[k - 1] || m < mag[k + 1]) continue
            val md = midiOf[k]
            val r = md.roundToInt()
            val dev = md - r
            val w = cos(dev * PI).toFloat()
            val en = m * w * w
            val pc = ((r % 12) + 12) % 12
            val f = freqOf[k]
            val fw = if (f < 110f) 0.8f else if (f > 1000f) 0.6f else 1f
            out[pc] += en * fw
            if (f < 240f) bass[pc] += en
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

/** Music theory helpers: naming, key detection, progression summary. */
object Music {
    val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    val INTERVALS = arrayOf(
        intArrayOf(0, 4, 7), intArrayOf(0, 3, 7),
        intArrayOf(0, 4, 7, 10), intArrayOf(0, 3, 7, 10), intArrayOf(0, 4, 7, 11),
        intArrayOf(0, 5, 7), intArrayOf(0, 3, 6)
    )
    val SUFFIX = arrayOf("", "m", "7", "m7", "maj7", "sus4", "dim")
    val PENALTY = floatArrayOf(0f, 0f, 0.035f, 0.035f, 0.035f, 0.04f, 0.04f)
    private val MAJ = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MIN = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)
    private val ROMAN_MAJ = arrayOf("I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII")
    private val ROMAN_MIN = arrayOf("I", "bII", "II", "III", "#III", "IV", "#IV", "V", "VI", "#VI", "VII", "#VII")

    data class Key(val tonic: Int, val minor: Boolean) {
        val flats: Boolean get() {
            val majTonic = if (minor) (tonic + 3) % 12 else tonic
            return majTonic in intArrayOf(5, 10, 3, 8, 1, 6)
        }
        fun name(): String = (if (flats) FLAT else SHARP)[tonic] + if (minor) " minor" else " major"
    }

    fun chordName(c: Chord?, key: Key?): String {
        if (c == null) return "–"
        val names = if (key?.flats == true) FLAT else SHARP
        return names[c.root] + SUFFIX[c.quality]
    }

    fun roman(c: Chord, key: Key): String {
        val iv = (c.root - key.tonic + 12) % 12
        var r = if (key.minor) ROMAN_MIN[iv] else ROMAN_MAJ[iv]
        val minorish = c.quality == 1 || c.quality == 3 || c.quality == 6
        if (minorish) r = r.replace("VII", "vii").replace("VI", "vi").replace("IV", "iv")
            .replace("V", "v").replace("III", "iii").replace("II", "ii").replace("I", "i")
        r += when (c.quality) { 2, 3 -> "7"; 4 -> "maj7"; 5 -> "sus4"; 6 -> "°"; else -> "" }
        return r
    }

    /** Krumhansl–Schmuckler key finding from chord durations. */
    fun detectKey(segments: List<Segment>): Key? {
        val prof = FloatArray(12)
        for (s in segments) {
            val c = s.chord ?: continue
            val d = s.duration.toFloat()
            INTERVALS[c.quality].forEachIndexed { i, iv -> prof[(c.root + iv) % 12] += d * (if (i == 0) 1.3f else 1f) }
        }
        if (prof.sum() <= 0f) return null
        var best: Key? = null; var bs = -2f
        for (t in 0 until 12) {
            val rot = FloatArray(12) { prof[(it + t) % 12] }
            val a = corr(rot, MAJ); val b = corr(rot, MIN)
            if (a > bs) { bs = a; best = Key(t, false) }
            if (b > bs) { bs = b; best = Key(t, true) }
        }
        return best
    }

    private fun corr(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat(); val mb = b.average().toFloat()
        var n = 0f; var da = 0f; var db = 0f
        for (i in 0 until 12) { n += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb) }
        val d = sqrt(da * db)
        return if (d > 0f) n / d else 0f
    }

    /** Most repeated run of 4 (or 3, 2) chords. */
    fun mainProgression(segments: List<Segment>): List<Chord> {
        val seq = ArrayList<Chord>()
        for (s in segments) {
            val c = s.chord ?: continue
            if (s.duration < 0.5) continue
            if (seq.isEmpty() || seq.last() != c) seq.add(c)
        }
        for (len in intArrayOf(4, 3, 2)) {
            val counts = LinkedHashMap<List<Chord>, Int>()
            for (i in 0..seq.size - len) {
                val g = seq.subList(i, i + len).toList()
                if (g.toSet().size < 2) continue
                counts[g] = (counts[g] ?: 0) + 1
            }
            val best = counts.maxByOrNull { it.value }
            if (best != null && best.value >= 2) return best.key
        }
        return seq.take(4)
    }
}

data class Segment(val chord: Chord?, val start: Double, var end: Double) {
    val duration get() = end - start
}

/** Collects engine frames into a clean list of chord segments for one listening session. */
class Session {
    private val raw = ArrayList<Segment>()
    var startTime = -1.0
        private set

    fun add(chord: Chord?, t: Double) {
        if (startTime < 0) startTime = t
        val rt = t - startTime
        val last = raw.lastOrNull()
        if (last != null && last.chord == chord) last.end = rt
        else { last?.end = rt; raw.add(Segment(chord, rt, rt)) }
    }

    val elapsed: Double get() = raw.lastOrNull()?.end ?: 0.0

    /** Merge blips shorter than 0.4 s and return the cleaned timeline. */
    fun segments(): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in raw) {
            val prev = out.lastOrNull()
            if (prev != null && (s.duration < 0.4 || prev.chord == s.chord)) prev.end = s.end
            else out.add(s.copy())
        }
        val fin = ArrayList<Segment>()
        for (s in out) {
            val prev = fin.lastOrNull()
            if (prev != null && prev.chord == s.chord) prev.end = s.end else fin.add(s)
        }
        return fin
    }

    fun hasChords(): Boolean = segments().any { it.chord != null && it.duration >= 0.5 }
}

