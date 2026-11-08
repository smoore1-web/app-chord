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
    fun progressionText(): String = progression.joinToString("  –  ") { Music.chordName(it, key) }
    fun romanText(): String = if (key == null) "" else progression.joinToString("  –  ") { Music.roman(it, key) }
    fun timelineText(): String = timeline.joinToString("  ") { Music.chordName(it.chord, key) }
    fun shareText(): String = buildString {
        append("Key: ").append(keyText()).append('\n')
        append("Progression: ").append(progressionText())
        if (key != null) append("  (").append(romanText()).append(')')
        append("\nAll chords heard: ").append(timelineText())
        append("\n— found with ChordEar")
    }
}

/** Runs one listening session (start -> live updates -> result), delivering callbacks on the main thread. */
class ListenController(private val cb: Callback) : ChordEngine.Listener {

    interface Callback {
        fun onLive(chord: Chord?, level: Float, elapsed: Double)
        fun onResult(result: ChordResult?)
        fun onError(message: String)
    }

    companion object {
        const val MAX_SEC = 18.0
        const val MIN_SEC = 5.0
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: ChordEngine? = null
    private var session = Session()

    val listening: Boolean get() = engine != null
    val elapsed: Double get() = synchronized(this) { session.elapsed }

    fun start() {
        if (engine != null) return
        synchronized(this) { session = Session() }
        engine = ChordEngine(this).also { it.start() }
    }

    /** Stop listening and produce a result. */
    fun finish() {
        val e = engine ?: return
        engine = null
        Thread {
            e.stop()
            val r = buildResult()
            main.post { cb.onResult(r) }
        }.start()
    }

    /** Stop without a result. */
    fun cancel() {
        val e = engine ?: return
        engine = null
        Thread { e.stop() }.start()
    }

    override fun onFrame(chord: Chord?, level: Float, timeSec: Double) {
        val el: Double
        synchronized(this) { session.add(chord, timeSec); el = session.elapsed }
        main.post {
            if (engine == null) return@post
            cb.onLive(chord, level, el)
            if (el >= MAX_SEC) finish()
        }
    }

    override fun onError(message: String) {
        main.post {
            engine = null
            cb.onError(message)
        }
    }

    private fun buildResult(): ChordResult? {
        val segs = synchronized(this) { session.segments() }
        val chords = segs.filter { it.chord != null }
        if (chords.none { it.duration >= 0.5 }) return null
        val key = Music.detectKey(chords)
        return ChordResult(key, Music.mainProgression(chords), chords, System.currentTimeMillis())
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
