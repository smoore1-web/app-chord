package com.stm.chordear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Shazam-style pop-up: a floating bubble over other apps. Tap it to listen;
 * after ~15 s a result panel slides up with the key and chord progression.
 */
class BubbleService : Service(), ListenController.Callback {

    companion object {
        const val ACTION_STOP = "com.stm.chordear.STOP"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 7
        @Volatile var running = false; private set
        @Volatile var instance: BubbleService? = null; private set
    }

    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private lateinit var pulse: PulseView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var panelStatus: TextView? = null
    private val controller = ListenController(this)
    private var lastResult: ChordResult? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true; instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.cancel()
        removePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        running = false; instance = null
        super.onDestroy()
    }

    /** Called when the main app wants the mic. */
    fun pauseForApp() {
        if (controller.listening) { controller.cancel(); idle() }
        removePanel()
    }

    // ---------- foreground notification ----------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pop-up bubble", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("ChordEar pop-up is on")
            .setContentText("Tap the floating bubble to find a song's chords")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    // ---------- bubble ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(76)
        pulse = PulseView(this).apply { label = "Tap to\nlisten" }
        val frame = FrameLayout(this).apply { addView(pulse, FrameLayout.LayoutParams(size, size)) }
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(4)
            y = resources.displayMetrics.heightPixels / 3
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        frame.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(frame, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapToEdge(frame) else onBubbleTap()
                    true
                }
                else -> false
            }
        }
        wm.addView(frame, bubbleParams)
        bubble = frame
    }

    private fun snapToEdge(v: View) {
        val w = resources.displayMetrics.widthPixels
        val size = bubbleParams.width
        bubbleParams.x = if (bubbleParams.x + size / 2 < w / 2) dp(4) else w - size - dp(4)
        bubbleParams.y = bubbleParams.y.coerceIn(dp(24), resources.displayMetrics.heightPixels - size - dp(24))
        wm.updateViewLayout(v, bubbleParams)
    }

    private fun onBubbleTap() {
        when {
            controller.listening -> {
                if (controller.elapsed >= ListenController.MIN_SEC) controller.finish()
                else toast("Listening… give it a few more seconds")
            }
            panel != null -> removePanel()
            else -> startListening()
        }
    }

    private fun startListening() {
        removePanel()
        controller.start()
        pulse.listening = true; pulse.big = false; pulse.label = "…"
    }

    private fun idle() {
        pulse.listening = false; pulse.big = false; pulse.label = "Tap to\nlisten"
    }

    // ---------- listening callbacks ----------
    override fun onLive(chord: Chord?, level: Float, elapsed: Double) {
        pulse.level = level
        if (chord != null) { pulse.big = true; pulse.label = Music.chordName(chord, null) }
    }

    override fun onResult(result: ChordResult?) {
        idle()
        if (result == null) {
            showPanel(null, "Couldn't make out the chords. Turn the volume up and tap the bubble again.")
            return
        }
        lastResult = result
        History.add(this, result)
        showPanel(result, null)
    }

    override fun onError(message: String) {
        idle(); showPanel(null, message)
    }

    // ---------- result panel ----------
    private fun showPanel(r: ChordResult?, message: String?) {
        removePanel()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.BG, 24, C.LINE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("ChordEar", 17f, C.AMBER, true), lp(0, WRAP, 1f))
        header.addView(text("✕", 20f, C.MUTE, true).apply {
            setPadding(dp(12), dp(4), dp(6), dp(4)); setOnClickListener { removePanel() }
        })
        box.addView(header)

        if (r != null) box.addView(resultView(r), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        if (message != null) box.addView(text(message, 15f, C.INK).apply { setPadding(0, dp(10), 0, dp(6)) }.also { panelStatus = it })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Listen again", true) { startListening() }, lp(0, WRAP, 1.3f))
        if (r != null) row.addView(button("Copy", false) { copy(r.shareText()) }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        box.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(text("Turn off bubble", 13f, C.MUTE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2))
            setOnClickListener { stopSelf() }
        }, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply { addView(box) }
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        wm.addView(scroll, params)
        panel = scroll
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; panelStatus = null
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chords", s))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
