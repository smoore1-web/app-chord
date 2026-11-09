package com.stm.chordear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

object C {
    val BG = Color.parseColor("#14110F")
    val PANEL = Color.parseColor("#1E1A17")
    val PANEL2 = Color.parseColor("#2A2421")
    val LINE = Color.parseColor("#3A332E")
    val INK = Color.parseColor("#F3ECE4")
    val MUTE = Color.parseColor("#A99D92")
    val AMBER = Color.parseColor("#F2A541")
    val AMBER2 = Color.parseColor("#FFCF87")
    val ROSE = Color.parseColor("#E2725B")
    val TEAL = Color.parseColor("#5FB3A3")
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Number, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun Context.text(s: String, sizeSp: Float, color: Int = C.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
    text(label, 15f, if (primary) Color.parseColor("#1B1208") else C.INK, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = if (primary) rounded(C.AMBER, 14) else rounded(C.PANEL2, 14, C.LINE)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** The result card: key, progression, instrument view with playback, and every chord heard. */
fun Context.resultView(r: ChordResult): LinearLayout = ResultCard(this, r)

class ResultCard(ctx: Context, private val r: ChordResult) : LinearLayout(ctx) {
    private val flats = r.key?.flats == true
    private var inst = if (Prefs.guitar(ctx)) Instrument.GUITAR else Instrument.PIANO
    private var selected: Chord? = r.progression.firstOrNull() ?: r.timeline.firstOrNull()?.chord
    private val progChips = ArrayList<Pair<Chord, View>>()
    private val heardChips = ArrayList<Pair<Chord, View>>()
    private val piano = PianoView(ctx)
    private val guitar = GuitarView(ctx)
    private val selName = ctx.text("", 20f, C.AMBER2, true)
    private val selNotes = ctx.text("", 13f, C.MUTE)
    private val pianoTab: TextView
    private val guitarTab: TextView
    private val playProg: TextView
    private val playHeard: TextView
    private var playingButton: TextView? = null

    init {
        orientation = VERTICAL
        background = ctx.rounded(C.PANEL, 20, C.LINE)
        setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))

        addView(ctx.text("KEY", 11f, C.MUTE, true).apply { letterSpacing = 0.15f })
        addView(ctx.text(r.keyText(), 26f, C.INK, true))
        if (r.key != null) addView(ctx.text(r.relativeText().replaceFirstChar { it.uppercase() }, 13f, C.MUTE))

        addView(header("CHORD PROGRESSION  ·  tap to hear"))
        val chips = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        r.progression.forEachIndexed { i, ch ->
            val chip = LinearLayout(ctx).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(10), ctx.dp(6), ctx.dp(10))
                addView(ctx.text(Music.chordName(ch, r.key), if (ch.isInversion) 17f else 22f, C.AMBER2, true).apply { gravity = Gravity.CENTER })
                if (r.key != null) addView(ctx.text(Music.roman(ch, r.key), 13f, C.TEAL, true).apply { gravity = Gravity.CENTER })
                setOnClickListener { select(ch, play = true) }
            }
            progChips.add(ch to chip)
            chips.addView(chip, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = ctx.dp(6) })
        }
        addView(chips, lp(MATCH, WRAP))

        // instrument switch
        val tabs = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; background = ctx.rounded(C.PANEL2, 12)
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        }
        pianoTab = tab("Piano") { setInst(Instrument.PIANO) }
        guitarTab = tab("Guitar") { setInst(Instrument.GUITAR) }
        tabs.addView(pianoTab, lp(0, WRAP, 1f)); tabs.addView(guitarTab, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(3) })
        addView(tabs, lp(MATCH, WRAP).apply { topMargin = ctx.dp(16) })

        addView(selName.apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(12), 0, 0) }, lp(MATCH, WRAP))
        addView(selNotes.apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }, lp(MATCH, WRAP))
        addView(piano, lp(MATCH, WRAP))
        addView(guitar, lp(MATCH, WRAP))

        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        playProg = ctx.button("▶  Progression", true) { toggle(playProgButton = true) }
        playHeard = ctx.button("▶  As heard", false) { toggle(playProgButton = false) }
        row.addView(playProg, lp(0, WRAP, 1f)); row.addView(playHeard, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(8) })
        addView(row, lp(MATCH, WRAP).apply { topMargin = ctx.dp(12) })

        addView(header("EVERYTHING IT HEARD"))
        val heard = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        r.timeline.filter { it.duration >= 0.4 && it.chord != null }.forEachIndexed { i, s ->
            val ch = s.chord!!
            val v = ctx.text(Music.chordName(ch, r.key), 15f, C.INK, true).apply {
                setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
                setOnClickListener { select(ch, play = true) }
            }
            heardChips.add(ch to v)
            heard.addView(v, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = ctx.dp(5) })
        }
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(heard) }, lp(MATCH, WRAP))

        setInst(inst)
    }

    private fun header(s: String) = context.text(s, 11f, C.MUTE, true).apply {
        letterSpacing = 0.15f; setPadding(0, context.dp(16), 0, context.dp(8))
    }

    private fun tab(label: String, onClick: () -> Unit) = context.text(label, 14f, C.INK, true).apply {
        gravity = Gravity.CENTER; setPadding(context.dp(8), context.dp(9), context.dp(8), context.dp(9))
        setOnClickListener { onClick() }
    }

    private fun setInst(i: Instrument) {
        inst = i
        Prefs.setGuitar(context, i == Instrument.GUITAR)
        for ((v, on) in listOf(pianoTab to (i == Instrument.PIANO), guitarTab to (i == Instrument.GUITAR))) {
            v.background = if (on) context.rounded(C.AMBER, 10) else null
            v.setTextColor(if (on) Color.parseColor("#1B1208") else C.MUTE)
        }
        piano.visibility = if (i == Instrument.PIANO) View.VISIBLE else View.GONE
        guitar.visibility = if (i == Instrument.GUITAR) View.VISIBLE else View.GONE
        selected?.let { select(it, play = false) }
    }

    private fun select(ch: Chord, play: Boolean) {
        selected = ch
        val notes = Synth.notesFor(ch, inst)
        piano.set(Voicing.piano(ch), flats)
        guitar.set(Voicing.guitar(ch), flats)
        val names = if (flats) Music.FLAT else Music.SHARP
        selName.text = Music.chordName(ch, r.key) + (if (r.key != null) "   " + Music.roman(ch, r.key) else "")
        val inv = when {
            !ch.isInversion -> "root position"
            Music.INTERVALS[ch.quality].indexOf((ch.bass - ch.root + 12) % 12) == 1 -> "1st inversion"
            Music.INTERVALS[ch.quality].indexOf((ch.bass - ch.root + 12) % 12) == 2 -> "2nd inversion"
            else -> "3rd inversion"
        }
        selNotes.text = notes.joinToString("  ") { names[it % 12] } + "   ·   bass " + names[ch.bass] + ", " + inv
        for ((c, v) in progChips) v.background = if (c == ch) context.rounded(C.PANEL2, 14, C.AMBER) else context.rounded(C.PANEL2, 14, C.LINE)
        for ((c, v) in heardChips) v.background = if (c == ch) context.rounded(Color.parseColor("#3A2A17"), 10, C.AMBER) else context.rounded(C.PANEL2, 10)
        if (play) { resetButtons(); Synth.play(listOf(ch), listOf(1.8), inst) }
    }

    private fun toggle(playProgButton: Boolean) {
        val btn = if (playProgButton) playProg else playHeard
        if (playingButton === btn) { Synth.stop(); resetButtons(); return }
        resetButtons()
        val chords: List<Chord>; val secs: List<Double>
        if (playProgButton) {
            chords = r.progression + r.progression
            secs = chords.map { 1.6 }
        } else {
            val segs = r.timeline.filter { it.chord != null && it.duration >= 0.4 }
            chords = segs.map { it.chord!! }
            secs = segs.map { it.duration.coerceIn(0.6, 4.0) }
        }
        if (chords.isEmpty()) return
        playingButton = btn
        btn.text = "■  Stop"
        Synth.play(chords, secs, inst, onChord = { i -> select(chords[i], play = false) }, onDone = {
            if (playingButton === btn) resetButtons()
        })
    }

    private fun resetButtons() {
        playingButton = null
        playProg.text = "▶  Progression"; playHeard.text = "▶  As heard"
    }

    override fun onDetachedFromWindow() {
        if (playingButton != null) Synth.stop()
        super.onDetachedFromWindow()
    }
}

/** Shazam-style big round button with expanding rings while listening. */
class PulseView(ctx: Context) : View(ctx) {
    var label = "Tap to\nlisten"; set(v) { field = v; invalidate() }
    var big = false; set(v) { field = v; invalidate() }
    var level = 0f; set(v) { field = v; invalidate() }
    var listening = false
        set(v) {
            field = v
            if (v) anim.start() else { anim.cancel(); phase = 0f }
            invalidate()
        }

    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1208"); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f
        val baseR = maxR * 0.62f
        if (listening) {
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                ring.strokeWidth = maxR * 0.03f
                ring.color = C.AMBER
                ring.alpha = ((1f - p) * 170).toInt()
                canvas.drawCircle(cx, cy, baseR + (maxR - baseR) * p, ring)
            }
        }
        val r = baseR * (1f + if (listening) min(level * 3f, 0.08f) else 0f)
        disc.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, C.AMBER2, C.AMBER, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, disc)
        val lines = label.split("\n")
        txt.textSize = if (big) r * 0.55f else r * 0.2f
        val lh = txt.textSize * 1.15f
        var y = cy - (lines.size - 1) * lh / 2f - (txt.descent() + txt.ascent()) / 2f
        for (l in lines) { canvas.drawText(l, cx, y, txt); y += lh }
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}
