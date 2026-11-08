package com.stm.chordear

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whole-clip chord recognition, run once listening stops.
 * Spectra -> tuning estimate -> spectral whitening (removes drums/noise floor) ->
 * chroma + bass chroma -> key detection -> HMM/Viterbi chord tracking with a key prior.
 */
object Analyzer {

    /**
     * @param spectra magnitude spectra (bins 0..kMax+1) for each hop
     * @param levels  RMS level for each hop
     * @param sr sample rate, n FFT size, hop hop size
     */
    fun analyze(
        spectra: List<FloatArray>, levels: List<Float>,
        sr: Int, n: Int, hop: Int, extended: Boolean
    ): ChordResult? {
        val T = minOf(spectra.size, levels.size)
        if (T < 20) return null
        val maxLevel = levels.take(T).maxOrNull() ?: 0f
        if (maxLevel < 0.0015f) return null

        val bins = spectra[0].size
        val freq = FloatArray(bins) { it.toFloat() * sr / n }
        val kMin = max(2, (65f * n / sr).toInt())
        val kMax = minOf(bins - 2, (2000f * n / sr).toInt())

        // ---- 1. tuning (how far the song sits from A=440) ----
        var sc = 0.0; var ss = 0.0
        for (t in 0 until T) {
            if (levels[t] < 0.08f * maxLevel) continue
            val m = spectra[t]
            var mx = 0f; for (k in kMin..kMax) if (m[k] > mx) mx = m[k]
            for (k in kMin..kMax) {
                val v = m[k]
                if (v < mx * 0.1f || v <= m[k - 1] || v < m[k + 1]) continue
                val a = m[k - 1]; val c = m[k + 1]
                val den = a - 2 * v + c
                val p = if (den != 0f) 0.5f * (a - c) / den else 0f
                val f = (k + p) * sr / n
                if (f < 100f || f > 1500f) continue
                val md = 69 + 12 * ln(f / 440.0) / ln(2.0)
                val dev = md - md.roundToInt()
                sc += v * cos(2 * PI * dev); ss += v * sin(2 * PI * dev)
            }
        }
        val tuning = (atan2(ss, sc) / (2 * PI)).toFloat()
        val midi = FloatArray(bins) { k ->
            if (k == 0) 0f else (69 + 12 * ln(freq[k] / 440.0) / ln(2.0)).toFloat() - tuning
        }

        // ---- 2. whitened chroma per frame ----
        val chroma = Array(T) { FloatArray(12) }
        val bass = Array(T) { FloatArray(12) }
        val prefix = DoubleArray(bins + 1)
        val white = FloatArray(bins)
        for (t in 0 until T) {
            val m = spectra[t]
            for (k in 0 until bins) prefix[k + 1] = prefix[k] + m[k]
            for (k in kMin..kMax) {
                val w = 2 + k / 14                         // ~quarter-octave neighbourhood
                val lo = max(0, k - w); val hi = minOf(bins - 1, k + w)
                val mean = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
                white[k] = max(0f, m[k] - 1.15f * mean)
            }
            var mx = 0f; for (k in kMin..kMax) if (white[k] > mx) mx = white[k]
            if (mx <= 0f) continue
            for (k in kMin..kMax) {
                val v = white[k]
                if (v < mx * 0.03f || v <= white[k - 1] || v < white[k + 1]) continue
                val md = midi[k]; val r = md.roundToInt(); val dev = md - r
                val wt = cos(dev * PI).toFloat(); val e = v * wt * wt
                val pc = ((r % 12) + 12) % 12
                val f = freq[k]
                chroma[t][pc] += e * (if (f > 1200f) 0.6f else 1f)
                if (f < 260f) bass[t][pc] += e
            }
        }

        // ---- 3. smooth over ±2 hops, compress, normalise ----
        val cn = Array(T) { FloatArray(12) }
        val bn = Array(T) { FloatArray(12) }
        val profile = FloatArray(12)
        for (t in 0 until T) {
            val c = cn[t]; val b = bn[t]
            for (d in -2..2) {
                val u = (t + d).coerceIn(0, T - 1)
                for (i in 0 until 12) { c[i] += chroma[u][i]; b[i] += bass[u][i] }
            }
            var mx = 0f; for (x in c) mx = max(mx, x)
            if (mx > 0f) {
                for (i in 0 until 12) c[i] = ln1p(15f * c[i] / mx)
                var nr = 0f; for (x in c) nr += x * x; nr = sqrt(nr)
                for (i in 0 until 12) c[i] /= nr
            }
            var bm = 0f; for (x in b) bm = max(bm, x)
            if (bm > 0f) for (i in 0 until 12) b[i] /= bm
            if (levels[t] > 0.08f * maxLevel) {
                var cm = 0f; for (x in chroma[t]) cm = max(cm, x)
                if (cm > 0f) for (i in 0 until 12) profile[i] += chroma[t][i] / cm
            }
        }
        val key = Music.detectKeyFromProfile(profile)

        // ---- 4. templates with a gentle key prior ----
        val qualities = if (extended) Music.INTERVALS.indices.toList() else listOf(0, 1)
        val tVec = ArrayList<FloatArray>(); val tChord = ArrayList<Chord>(); val tBias = ArrayList<Float>()
        val diatonic = if (key != null) Music.diatonic(key) else emptySet()
        for (q in qualities) for (r in 0 until 12) {
            val v = FloatArray(12)
            Music.INTERVALS[q].forEachIndexed { i, iv -> v[(r + iv) % 12] = if (i == 3) 0.75f else 1f }
            var nr = 0f; for (x in v) nr += x * x; nr = sqrt(nr)
            for (i in 0 until 12) v[i] /= nr
            val ch = Chord(r, q)
            tVec.add(v); tChord.add(ch)
            val baseQ = if (q == 1 || q == 3) 1 else if (q == 6) 6 else 0
            val inKey = Chord(r, baseQ) in diatonic
            tBias.add((if (inKey) 0.04f else 0f) - Music.PENALTY[q])
        }
        val S = tVec.size + 1           // last state = no chord

        // ---- 5. Viterbi ----
        val penalty = 0.5f
        var prev = FloatArray(S); var cur = FloatArray(S)
        val back = IntArray(T * S)
        val emit = FloatArray(S)
        for (t in 0 until T) {
            val quiet = levels[t] < 0.06f * maxLevel
            val c = cn[t]; val b = bn[t]
            for (s in 0 until S - 1) {
                if (quiet) { emit[s] = 0f; continue }
                val v = tVec[s]; var d = 0f
                for (i in 0 until 12) d += c[i] * v[i]
                emit[s] = d + 0.1f * b[tChord[s].root] + tBias[s]
            }
            emit[S - 1] = if (quiet) 1f else 0.45f
            if (t == 0) { for (s in 0 until S) prev[s] = emit[s]; continue }
            var bestP = -1e9f; var bi = 0
            for (s in 0 until S) if (prev[s] > bestP) { bestP = prev[s]; bi = s }
            for (s in 0 until S) {
                val stay = prev[s]; val sw = bestP - penalty
                if (stay >= sw) { cur[s] = stay + emit[s]; back[t * S + s] = s }
                else { cur[s] = sw + emit[s]; back[t * S + s] = bi }
            }
            var m = -1e9f; for (x in cur) m = max(m, x)
            for (s in 0 until S) cur[s] -= m
            val tmp = prev; prev = cur; cur = tmp
        }
        var s = 0; var bv = -1e9f
        for (i in 0 until S) if (prev[i] > bv) { bv = prev[i]; s = i }
        val path = IntArray(T)
        for (t in T - 1 downTo 0) { path[t] = s; s = back[t * S + s] }

        // ---- 6. segments ----
        val dt = hop.toDouble() / sr
        val raw = ArrayList<Segment>()
        for (t in 0 until T) {
            val ch = if (path[t] == S - 1) null else tChord[path[t]]
            val last = raw.lastOrNull()
            if (last != null && last.chord == ch) last.end = (t + 1) * dt
            else raw.add(Segment(ch, t * dt, (t + 1) * dt))
        }
        val merged = ArrayList<Segment>()
        for (g in raw) {
            val p = merged.lastOrNull()
            if (p != null && (g.duration < 0.5 || p.chord == g.chord)) p.end = g.end else merged.add(g.copy())
        }
        val fin = ArrayList<Segment>()
        for (g in merged) {
            val p = fin.lastOrNull()
            if (p != null && p.chord == g.chord) p.end = g.end else fin.add(g)
        }
        val chords = fin.filter { it.chord != null }
        if (chords.none { it.duration >= 0.5 }) return null
        return ChordResult(key, Music.mainProgression(chords), chords, System.currentTimeMillis())
    }
}
