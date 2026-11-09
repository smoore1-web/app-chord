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

/** The result card: key, main progression chips with Roman numerals, and every chord heard. */
fun Context.resultView(r: ChordResult): LinearLayout {
    val ctx = this
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.PANEL, 20, C.LINE)
        setPadding(dp(18), dp(18), dp(18), dp(18))

        addView(text("KEY", 11f, C.MUTE, true).apply { letterSpacing = 0.15f })
        addView(text(r.keyText(), 26f, C.INK, true))
        if (r.key != null) addView(text(r.relativeText().replaceFirstChar { it.uppercase() }, 13f, C.MUTE))

        addView(text("CHORD PROGRESSION", 11f, C.MUTE, true).apply {
            letterSpacing = 0.15f; setPadding(0, dp(16), 0, dp(8))
        })
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        r.progression.forEachIndexed { i, ch ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(C.PANEL2, 14, C.LINE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(text(Music.chordName(ch, r.key), 22f, C.AMBER2, true).apply { gravity = Gravity.CENTER })
                if (r.key != null) addView(text(Music.roman(ch, r.key), 13f, C.TEAL, true).apply { gravity = Gravity.CENTER })
            }
            chips.addView(chip, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        addView(chips, lp(MATCH, WRAP))

        addView(text("EVERYTHING IT HEARD", 11f, C.MUTE, true).apply {
            letterSpacing = 0.15f; setPadding(0, dp(16), 0, dp(8))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        r.timeline.filter { it.duration >= 0.4 }.forEachIndexed { i, s ->
            row.addView(text(Music.chordName(s.chord, r.key), 15f, C.INK, true).apply {
                background = rounded(C.PANEL2, 10)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(5) })
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, lp(MATCH, WRAP))
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
