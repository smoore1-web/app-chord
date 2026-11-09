package com.stm.chordear

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The finished answer for one listen — what the app shows, like a Shazam result card. */
data class ChordResult(
    val key: Music.Key?,
    val progression: List<Chord>,
    val timeline: List<Segment>,
    val whenMillis: Long
) {
    fun keyText(): String = key?.name() ?: "Unknown key"
    fun relativeText(): String = key?.let { "same notes as ${it.relative().name()}" } ?: ""
    fun progressionText(): String = progression.joinToString("  –  ") { Music.chordName(it, key) }
    fun romanText(): String = if (key == null) "" else progression.joinToString("  –  ") { Music.roman(it, key) }
    fun timelineText(): String = timeline.joinToString("  ") { Music.chordName(it.chord, key) }
    fun shareText(): String = buildString {
        append("Key: ").append(keyText())
        if (key != null) append(" (").append(relativeText()).append(')')
        append('\n')
        append("Progression: ").append(progressionText())
        if (key != null) append("  (").append(romanText()).append(')')
        append("\nAll chords heard: ").append(timelineText())
        append("\n— found with ChordEar")
    }
}

/** Keeps the last 30 results, like Shazam's library. */
object History {
    private const val PREF = "history"
    private const val KEY = "items"

    fun add(ctx: Context, r: ChordResult) {
        val stamp = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(r.whenMillis))
        val line = listOf(stamp, r.keyText(), r.progressionText(), r.romanText())
            .joinToString("\u001F") { it.replace("\n", " ") }
        val items = listOf(line) + list(ctx).map { it.joinToString("\u001F") }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, items.take(30).joinToString("\n")).apply()
    }

    /** Each entry: [date, key, progression, roman numerals]. */
    fun list(ctx: Context): List<List<String>> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }.map { it.split("\u001F") }.filter { it.size >= 4 }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
