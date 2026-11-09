package com.stm.chordear

/** A guitar chord shape: fret per string, low E to high E (-1 = muted, 0 = open). */
data class GuitarShape(val frets: IntArray, val barreFret: Int) {
    val baseFret: Int get() {
        val fretted = frets.filter { it > 0 }
        if (fretted.isEmpty()) return 1
        return if (fretted.maxOrNull()!! <= 4) 1 else fretted.minOrNull()!!
    }
    fun midi(): IntArray = frets.indices.filter { frets[it] >= 0 }.map { Voicing.OPEN[it] + frets[it] }.toIntArray()
}

/** Turns a detected chord (with its bass note) into playable piano and guitar voicings. */
object Voicing {
    /** Standard tuning E2 A2 D3 G3 B3 E4 as MIDI notes. */
    val OPEN = intArrayOf(40, 45, 50, 55, 59, 64)
    private val cache = HashMap<Chord, GuitarShape?>()

    fun tones(c: Chord): IntArray = Music.INTERVALS[c.quality].map { (c.root + it) % 12 }.toIntArray()

    /**
     * Piano: bass note in the left hand (octave 3), right hand plays the chord
     * in the inversion that matches the bass (so C/E is E–G–C, not C–E–G).
     */
    fun piano(c: Chord): IntArray {
        val t = tones(c)
        val bassMidi = 48 + c.bass                 // C3..B3
        val start = t.indexOf(c.bass).let { if (it < 0) 0 else it }
        val right = ArrayList<Int>()
        var prev = 60 + c.bass - 1                 // right hand starts on the bass pitch class, octave 4
        for (i in t.indices) {
            val pc = t[(start + i) % t.size]
            var m = 60 + pc
            while (m <= prev) m += 12
            while (m - 12 > prev) m -= 12
            right.add(m); prev = m
        }
        return (listOf(bassMidi) + right).toIntArray()
    }

    /** Best playable guitar shape whose lowest note is the chord's bass note. */
    fun guitar(c: Chord): GuitarShape? = synchronized(cache) {
        cache.getOrPut(c) { search(c, strict = true) ?: search(c, strict = false) }
    }

    private fun search(c: Chord, strict: Boolean): GuitarShape? {
        val tones = tones(c).toSet()
        val required = tones(c).toMutableSet()
        if (!strict || tones.size == 4) required.remove((c.root + 7) % 12)  // the fifth may be left out
        var best: GuitarShape? = null
        var bestScore = Double.MAX_VALUE
        val cur = IntArray(6)

        for (w in 0..9) {
            val lo = maxOf(1, w); val hi = w + 3
            fun rec(s: Int) {
                if (s == 6) {
                    val sc = score(cur, c, required, strict) ?: return
                    if (sc.first < bestScore) { bestScore = sc.first; best = GuitarShape(cur.copyOf(), sc.second) }
                    return
                }
                cur[s] = -1; rec(s + 1)
                if ((OPEN[s] % 12) in tones && w <= 2) { cur[s] = 0; rec(s + 1) }
                for (f in lo..hi) if (((OPEN[s] + f) % 12) in tones) { cur[s] = f; rec(s + 1) }
            }
            rec(0)
        }
        return best
    }

    /** Returns (score, barreFret) or null if the shape isn't valid/playable. Lower score = better. */
    private fun score(fr: IntArray, c: Chord, required: Set<Int>, strict: Boolean): Pair<Double, Int>? {
        val sounding = fr.indices.filter { fr[it] >= 0 }
        if (sounding.size < (if (strict) 4 else 3)) return null
        // muted strings only as a block on the bass side
        val firstSounding = sounding.first()
        for (s in firstSounding until 6) if (fr[s] < 0) return null
        // lowest note must be the bass
        if ((OPEN[firstSounding] + fr[firstSounding]) % 12 != c.bass) return null
        val pcs = sounding.map { (OPEN[it] + fr[it]) % 12 }.toSet()
        if (!pcs.containsAll(required)) return null

        val fretted = sounding.filter { fr[it] > 0 }
        var barre = 0
        var fingers = fretted.size
        if (fretted.isNotEmpty()) {
            val minF = fretted.minOf { fr[it] }; val maxF = fretted.maxOf { fr[it] }
            if (maxF - minF > 3) return null
            if (fingers > 4) {
                // try a barre across the lowest fret
                val atMin = fretted.filter { fr[it] == minF }
                if (atMin.size < 2) return null
                val from = atMin.first()
                if (from > firstSounding + 1) return null   // barre must start on (or next to) the bass string
                // nothing open above the barre
                for (s in from until 6) if (fr[s] == 0) return null
                fingers = 1 + fretted.count { fr[it] > minF }
                if (fingers > 4) return null
                barre = minF
            }
        }
        val position = if (fretted.isEmpty()) 0 else fretted.minOf { fr[it] }
        val opens = sounding.count { fr[it] == 0 }
        val span = if (fretted.isEmpty()) 0 else fretted.maxOf { fr[it] } - position
        // prefer: low on the neck, more strings, fewer fingers, no barre, root/bass doubled sensibly
        var s = 1.6 * position - 1.6 * sounding.size + 0.7 * fingers + 0.8 * span - 0.25 * opens
        if (barre > 0) s += 2.0
        if (!pcs.contains((c.root + 7) % 12)) s += 1.5      // missing fifth
        // avoid doubling the 3rd on top of the bass in triads
        val topPc = (OPEN[sounding.last()] + fr[sounding.last()]) % 12
        if (topPc == (c.root + Music.INTERVALS[c.quality][1]) % 12 && c.bass != topPc) s += 0.2
        return s to barre
    }
}
