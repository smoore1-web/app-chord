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
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlin.math.abs

/**
 * Does all the listening (so it keeps working while you're in Spotify/YouTube),
 * holds the phone-audio capture permission, and shows the optional floating bubble.
 */
class ListenService : Service(), ListenController.Callback {

    companion object {
        const val ACTION_STOP = "com.stm.chordear.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_BUBBLE = "bubble"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_FROM_BUBBLE = "fromBubble"
        private const val CHANNEL = "listen"
        private const val NOTIF_ID = 7

        @Volatile var instance: ListenService? = null; private set
        /** The open app screen, if any (main thread only). */
        var ui: ListenController.Callback? = null
        var lastResult: ChordResult? = null; private set

        val running get() = instance != null
        val bubbleOn get() = instance?.bubble != null
        val hasProjection get() = instance?.projection != null
        val listening get() = instance?.controller?.listening == true
        val elapsed get() = instance?.controller?.elapsed ?: 0.0
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var pulse: PulseView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private val controller = ListenController(this)
    private var projection: MediaProjection? = null
    private var fromBubble = false
    private var usingPhone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            goForeground(false, "Turning off…")
            stopSelf(); return START_NOT_STICKY
        }
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        goForeground(data != null || projection != null, null)

        if (data != null && Build.VERSION.SDK_INT >= 29) {
            try {
                projection?.stop()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)?.also { mp ->
                    mp.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            main.post {
                                if (projection === mp) projection = null
                                if (controller.listening && usingPhone) {
                                    controller.cancel(); onError("Phone-audio capture was stopped.")
                                }
                            }
                        }
                    }, main)
                }
            } catch (e: Exception) {
                projection = null
                toast("Couldn't capture phone audio — using the microphone")
            }
        }

        if (intent?.hasExtra(EXTRA_BUBBLE) == true) {
            if (intent.getBooleanExtra(EXTRA_BUBBLE, false)) addBubble() else removeBubble()
        }
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            startListening(intent.getBooleanExtra(EXTRA_FROM_BUBBLE, false))
        }
        maybeStop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.cancel()
        removePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        super.onDestroy()
    }

    // ---------- public controls ----------
    fun finishListening() { if (controller.elapsed >= ListenController.MIN_SEC) controller.finish() }

    fun startListening(fromBubble: Boolean) {
        if (controller.listening) return
        val wantPhone = Prefs.phoneAudio(this) && AudioSources.phoneCaptureSupported
        val mp = projection
        if (wantPhone && mp == null && fromBubble) {
            // Need the capture permission again: the app screen asks, then hands back.
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CAPTURE_FOR_BUBBLE, true))
            return
        }
        usingPhone = wantPhone && mp != null && Build.VERSION.SDK_INT >= 29
        val maker: () -> AudioRecord =
            if (usingPhone && mp != null && Build.VERSION.SDK_INT >= 29) { { AudioSources.phone(mp) } }
            else { { AudioSources.mic(this) } }
        this.fromBubble = fromBubble
        removePanel()
        controller.start(maker, usingPhone, Prefs.extended(this))
        pulse?.apply { listening = true; big = false; label = "…" }
        updateNotification("Listening…")
    }

    fun turnOffBubble() { removeBubble(); maybeStop() }

    private fun maybeStop() {
        if (bubble == null && ui == null && !controller.listening) stopSelf()
    }

    // ---------- listening callbacks ----------
    override fun onLive(chord: Chord?, level: Float, elapsed: Double) {
        pulse?.let { p ->
            p.level = level
            if (chord != null) { p.big = true; p.label = Music.chordName(chord, null) }
        }
        ui?.onLive(chord, level, elapsed)
    }

    override fun onAnalyzing() {
        pulse?.apply { big = false; label = "…" }
        ui?.onAnalyzing()
    }

    override fun onResult(result: ChordResult?) {
        idleBubble()
        if (result != null) { lastResult = result; History.add(this, result) }
        val screen = ui
        if (bubble != null && (fromBubble || screen == null)) {
            if (result == null) showPanel(null, "Couldn't make out the chords. Make sure the song is playing and tap the bubble again.")
            else showPanel(result, null)
        }
        screen?.onResult(result)
        updateNotification(if (result != null) "${result.progressionText()}  ·  ${result.keyText()}" else "Tap the bubble to find a song's chords")
        if (bubble == null && screen == null && result == null) maybeStop()
    }

    override fun onError(message: String) {
        idleBubble()
        if (bubble != null && (fromBubble || ui == null)) showPanel(null, message)
        ui?.onError(message)
        updateNotification(message)
    }

    // ---------- notification ----------
    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ChordEar", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("ChordEar")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopPi).build())
            .build()
    }

    private fun goForeground(withProjection: Boolean, text: String?) {
        val n = buildNotification(text ?: if (bubble != null) "Tap the bubble to find a song's chords" else "Ready to listen")
        var type = 0
        if (Build.VERSION.SDK_INT >= 29 && withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(NOTIF_ID, n, type)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ---------- bubble ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null) return
        val size = dp(76)
        val pv = PulseView(this).apply { label = "Tap to\nlisten" }
        val frame = FrameLayout(this).apply { addView(pv, FrameLayout.LayoutParams(size, size)) }
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
                MotionEvent.ACTION_UP -> { if (moved) snapToEdge(frame) else onBubbleTap(); true }
                else -> false
            }
        }
        try {
            wm.addView(frame, bubbleParams)
            bubble = frame; pulse = pv
            if (controller.listening) pv.listening = true
            updateNotification("Tap the bubble to find a song's chords")
        } catch (e: Exception) {
            toast("ChordEar needs \"Display over other apps\" for the bubble")
        }
    }

    private fun removeBubble() {
        removePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null; pulse = null
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
            controller.listening ->
                if (controller.elapsed >= ListenController.MIN_SEC) controller.finish()
                else toast("Listening… give it a few more seconds")
            panel != null -> removePanel()
            else -> startListening(true)
        }
    }

    private fun idleBubble() {
        pulse?.apply { listening = false; big = false; label = "Tap to\nlisten" }
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
        if (message != null) box.addView(text(message, 15f, C.INK).apply { setPadding(0, dp(10), 0, dp(6)) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Listen again", true) { startListening(true) }, lp(0, WRAP, 1.3f))
        if (r != null) row.addView(button("Copy", false) { copy(r.shareText()) }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        box.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(text("Turn off bubble", 13f, C.MUTE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2))
            setOnClickListener { turnOffBubble() }
        }, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply { addView(box) }
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(24) }
        try { wm.addView(scroll, params); panel = scroll } catch (_: Exception) {}
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chords", s))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
