package com.stm.chordear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

private val WHITE_PCS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/** Three-octave keyboard (C3–B5) highlighting the chord's notes; the bass note is shown in teal. */
class PianoView(ctx: Context) : View(ctx) {
    private var notes = IntArray(0)
    private var bass = -1
    private var names = Music.SHARP
    private val lo = 48; private val hi = 83
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun set(notes: IntArray, flats: Boolean) {
        this.notes = notes; bass = notes.firstOrNull() ?: -1
        names = if (flats) Music.FLAT else Music.SHARP
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.30f).toInt().coerceAtLeast(context.dp(80)))
    }

    override fun onDraw(c: Canvas) {
        val whites = (lo..hi).filter { (it % 12) in WHITE_PCS }
        val kw = width / whites.size.toFloat()
        val h = height.toFloat()
        val r = context.dp(4).toFloat()
        label.textSize = kw * 0.42f
        // white keys
        whites.forEachIndexed { i, m ->
            val on = m in notes
            p.style = Paint.Style.FILL
            p.color = when { m == bass -> C.TEAL; on -> C.AMBER; else -> Color.parseColor("#EFE8DF") }
            c.drawRoundRect(RectF(i * kw + 1, 0f, (i + 1) * kw - 1, h), r, r, p)
            if (on) { label.color = Color.parseColor("#1B1208"); c.drawText(names[m % 12], i * kw + kw / 2, h - kw * 0.35f, label) }
            if (m % 12 == 0 && !on) {
                label.color = Color.parseColor("#9A8F85"); c.drawText("C${m / 12 - 1}", i * kw + kw / 2, h - kw * 0.35f, label)
            }
        }
        // black keys
        var wi = 0
        for (m in lo..hi) {
            val pc = m % 12
            if (pc in WHITE_PCS) { wi++; continue }
            val x = wi * kw - kw * 0.32f
            val on = m in notes
            p.color = when { m == bass -> C.TEAL; on -> C.AMBER; else -> Color.parseColor("#1A1614") }
            c.drawRoundRect(RectF(x, 0f, x + kw * 0.64f, h * 0.6f), r, r, p)
            if (on) {
                label.color = Color.parseColor("#1B1208")
                label.textSize = kw * 0.34f
                c.drawText(names[pc], x + kw * 0.32f, h * 0.6f - kw * 0.25f, label)
                label.textSize = kw * 0.42f
            }
        }
    }
}

/** Standard chord-chart diagram: strings vertical, frets horizontal, X/O markers, barre, base fret. */
class GuitarView(ctx: Context) : View(ctx) {
    private var shape: GuitarShape? = null
    private var names = Music.SHARP
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun set(shape: GuitarShape?, flats: Boolean) {
        this.shape = shape; names = if (flats) Music.FLAT else Music.SHARP; invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(230))
    }

    override fun onDraw(c: Canvas) {
        val s = shape
        if (s == null) {
            txt.color = C.MUTE; txt.textSize = context.dp(14).toFloat()
            c.drawText("No easy guitar shape for this one", width / 2f, height / 2f, txt); return
        }
        val frets = 5
        val gw = context.dp(170).toFloat()
        val left = (width - gw) / 2f
        val top = context.dp(34).toFloat()
        val fh = context.dp(30).toFloat()
        val sw = gw / 5f
        val base = s.baseFret
        val sx = { i: Int -> left + i * sw }
        val fy = { f: Int -> top + (f - base + 0.5f) * fh }   // centre of fret f

        // nut or base-fret label
        p.color = C.INK; p.strokeWidth = context.dp(1.5f).toFloat()
        if (base == 1) { p.strokeWidth = context.dp(5).toFloat(); c.drawLine(sx(0), top, sx(5), top, p) }
        else {
            txt.color = C.MUTE; txt.textSize = context.dp(13).toFloat(); txt.textAlign = Paint.Align.RIGHT
            c.drawText("${base}fr", left - context.dp(10), top + fh * 0.65f, txt); txt.textAlign = Paint.Align.CENTER
        }
        p.strokeWidth = context.dp(1.5f).toFloat(); p.color = C.LINE
        for (f in 0..frets) c.drawLine(sx(0), top + f * fh, sx(5), top + f * fh, p)
        p.color = C.MUTE
        for (i in 0..5) { p.strokeWidth = context.dp(1f + (5 - i) * 0.25f).toFloat(); c.drawLine(sx(i), top, sx(i), top + frets * fh, p) }

        // X / O above the nut
        txt.textSize = context.dp(14).toFloat()
        val bassString = s.frets.indexOfFirst { it >= 0 }
        for (i in 0..5) {
            val f = s.frets[i]
            if (f == -1) { txt.color = C.MUTE; c.drawText("×", sx(i), top - context.dp(10), txt) }
            if (f == 0) {
                p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(2).toFloat()
                p.color = if (i == bassString) C.TEAL else C.INK
                c.drawCircle(sx(i), top - context.dp(15), context.dp(6).toFloat(), p); p.style = Paint.Style.FILL
            }
        }
        // barre
        if (s.barreFret > 0) {
            val from = s.frets.indexOfFirst { it == s.barreFret }
            p.color = C.AMBER; p.style = Paint.Style.FILL
            val y = fy(s.barreFret); val rr = context.dp(11).toFloat()
            c.drawRoundRect(RectF(sx(from) - rr, y - rr, sx(5) + rr, y + rr), rr, rr, p)
        }
        // dots
        val r = context.dp(11).toFloat()
        for (i in 0..5) {
            val f = s.frets[i]
            if (f <= 0) continue
            if (f == s.barreFret && i != s.frets.indexOfFirst { it == s.barreFret } ) continue
            p.color = if (i == bassString) C.TEAL else C.AMBER
            c.drawCircle(sx(i), fy(f), r, p)
        }
        // note names under each string
        txt.textSize = context.dp(12).toFloat()
        for (i in 0..5) {
            val f = s.frets[i]; if (f < 0) continue
            txt.color = if (i == bassString) C.TEAL else C.MUTE
            c.drawText(names[(Voicing.OPEN[i] + f) % 12], sx(i), top + frets * fh + context.dp(18), txt)
        }
    }
}
