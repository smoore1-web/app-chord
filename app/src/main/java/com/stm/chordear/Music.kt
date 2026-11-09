package com.stm.chordear

import kotlin.math.sqrt

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
        /** The key with the same notes: C major <-> A minor. */
        fun relative(): Key = if (minor) Key((tonic + 3) % 12, false) else Key((tonic + 9) % 12, true)
    }

    fun chordName(c: Chord?, key: Key?): String {
        if (c == null) return "–"
        val names = if (key?.flats == true) FLAT else SHARP
        return names[c.root] + SUFFIX[c.quality] + if (c.isInversion) "/" + names[c.bass] else ""
    }

    fun roman(c: Chord, key: Key): String {
        val iv = (c.root - key.tonic + 12) % 12
        var r = if (key.minor) ROMAN_MIN[iv] else ROMAN_MAJ[iv]
        val minorish = c.quality == 1 || c.quality == 3 || c.quality == 6
        if (minorish) r = r.replace("VII", "vii").replace("VI", "vi").replace("IV", "iv")
            .replace("V", "v").replace("III", "iii").replace("II", "ii").replace("I", "i")
        val seventh = c.quality in 2..4
        if (!c.isInversion) {
            r += when (c.quality) { 2, 3 -> "7"; 4 -> "maj7"; 5 -> "sus4"; 6 -> "°"; else -> "" }
        } else {
            // figured-bass inversion symbols: V⁶ = first inversion, V⁶₄ = second, etc.
            val iv = (c.bass - c.root + 12) % 12
            val pos = INTERVALS[c.quality].indexOf(iv)
            if (c.quality == 6) r += "°"
            if (c.quality == 4) r += "maj"
            r += if (seventh) when (pos) { 1 -> "⁶₅"; 2 -> "⁴₃"; 3 -> "⁴₂"; else -> "7" }
                 else when (pos) { 1 -> "⁶"; 2 -> "⁶₄"; else -> "" }
        }
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

    fun detectKeyFromProfile(prof: FloatArray): Key? {
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

    /** Triads that belong to the key (quality 0 = major, 1 = minor, 6 = dim). */
    fun diatonic(key: Key): Set<Chord> {
        val t = key.tonic
        val list = if (!key.minor) listOf(0 to 0, 2 to 1, 4 to 1, 5 to 0, 7 to 0, 9 to 1, 11 to 6)
        else listOf(0 to 1, 2 to 6, 3 to 0, 5 to 1, 7 to 1, 7 to 0, 8 to 0, 10 to 0)
        return list.map { Chord((t + it.first) % 12, it.second) }.toSet()
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
