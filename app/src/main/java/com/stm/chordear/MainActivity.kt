package com.stm.chordear

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), ListenController.Callback {

    companion object {
        const val EXTRA_CAPTURE_FOR_BUBBLE = "captureForBubble"
        private const val REQ_CAPTURE = 42
    }

    private lateinit var pulse: PulseView
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var bubbleBtn: TextView
    private lateinit var srcPhone: TextView
    private lateinit var srcMic: TextView
    private lateinit var extBtn: TextView
    private var pendingAction: (() -> Unit)? = null
    private var waitingForOverlay = false
    private var shownResult: ChordResult? = null
    // what to do once the capture permission dialog answers
    private var pendListen = false
    private var pendBubble: Boolean? = null
    private var pendFromBubble = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
            setBackgroundColor(C.BG)
        }
        root.addView(text("ChordEar", 30f, C.INK, true))
        root.addView(text("Shazam for chords: tap and play the song", 15f, C.MUTE).apply { setPadding(0, dp(2), 0, dp(12)) })

        // source selector
        val src = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.PANEL, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        srcPhone = segBtn("Song on this phone") { Prefs.setPhoneAudio(this, true); renderSource() }
        srcMic = segBtn("Microphone") { Prefs.setPhoneAudio(this, false); renderSource() }
        if (AudioSources.phoneCaptureSupported) src.addView(srcPhone, lp(0, WRAP, 1f))
        src.addView(srcMic, lp(0, WRAP, 1f).apply { leftMargin = dp(4) })
        root.addView(src, lp(MATCH, WRAP))

        pulse = PulseView(this).apply { setOnClickListener { onPulseTap() } }
        root.addView(FrameLayout(this).apply { addView(pulse, FrameLayout.LayoutParams(dp(270), dp(270), Gravity.CENTER)) },
            lp(MATCH, dp(290)))

        status = text("", 15f, C.MUTE).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) }
        root.addView(status, lp(MATCH, WRAP))

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox, lp(MATCH, WRAP))

        bubbleBtn = button("Turn on pop-up bubble", false) { toggleBubble() }
        root.addView(bubbleBtn, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        root.addView(text("The pop-up bubble floats over YouTube, Spotify, TikTok and other apps. Tap it while a song plays to get the chords without leaving the app.", 13f, C.MUTE).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        extBtn = button("", false) { Prefs.setExtended(this, !Prefs.extended(this)); renderExt() }
        root.addView(extBtn, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        root.addView(text("HISTORY", 11f, C.MUTE, true).apply { letterSpacing = 0.15f; setPadding(0, dp(28), 0, dp(8)) })
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox, lp(MATCH, WRAP))

        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
        renderSource(); renderExt(); renderHistory(); idleText()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_CAPTURE_FOR_BUBBLE, false) == true) {
            i.removeExtra(EXTRA_CAPTURE_FOR_BUBBLE)
            requestCapture(listen = true, bubble = null, fromBubble = true)
        }
    }

    override fun onResume() {
        super.onResume()
        ListenService.ui = this
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(this)) startBubble() else toast("Pop-up permission wasn't turned on")
        }
        if (ListenService.listening) { pulse.listening = true; pulse.label = "…"; status.text = "Listening…" }
        else {
            if (pulse.listening) resetIdle()
            val r = ListenService.lastResult
            if (r != null && r !== shownResult) { showResult(r); status.text = "Here's what was playing" }
        }
        renderHistory()
        updateBubbleButton()
    }

    override fun onPause() {
        super.onPause()
        if (ListenService.ui === this) ListenService.ui = null
    }

    override fun onDestroy() {
        if (isFinishing && ListenService.running && !ListenService.bubbleOn && !ListenService.listening)
            stopService(Intent(this, ListenService::class.java))
        super.onDestroy()
    }

    // ---------- listening ----------
    private fun onPulseTap() {
        if (ListenService.listening) {
            if (ListenService.elapsed >= ListenController.MIN_SEC) ListenService.instance?.finishListening()
            else status.text = "Give it a few more seconds…"
            return
        }
        withMic {
            resultBox.removeAllViews()
            if (Prefs.phoneAudio(this) && AudioSources.phoneCaptureSupported && !ListenService.hasProjection)
                requestCapture(listen = true, bubble = null, fromBubble = false)
            else sendService(listen = true)
        }
    }

    override fun onLive(chord: Chord?, level: Float, elapsed: Double) {
        pulse.listening = true
        pulse.level = level
        if (chord != null) { pulse.big = true; pulse.label = Music.chordName(chord, null) }
        val left = (ListenController.MAX_SEC - elapsed).toInt().coerceAtLeast(0)
        status.text = when {
            level < 0.002f -> if (Prefs.phoneAudio(this)) "Waiting for sound — start the song" else "Can't hear much — turn it up or move closer"
            elapsed >= ListenController.MIN_SEC -> "Listening… ${left}s  (tap to finish now)"
            else -> "Listening… ${left}s"
        }
    }

    override fun onAnalyzing() {
        pulse.big = false; pulse.label = "Working\nit out"
        status.text = "Working out the chords…"
    }

    override fun onResult(result: ChordResult?) {
        resetIdle()
        if (result == null) {
            status.text = "Couldn't make out the chords. Make sure the song is playing and try again."
            return
        }
        status.text = "Here's what was playing"
        showResult(result)
        renderHistory()
    }

    override fun onError(message: String) { resetIdle(); status.text = message }

    private fun resetIdle() {
        pulse.listening = false; pulse.big = false; pulse.label = "Tap to\nlisten"
    }

    private fun idleText() {
        status.text = if (Prefs.phoneAudio(this) && AudioSources.phoneCaptureSupported)
            "Start the song in Spotify/YouTube, come back and tap"
        else "Hold your phone near the music and tap"
    }

    private fun showResult(r: ChordResult) {
        shownResult = r
        resultBox.removeAllViews()
        resultBox.addView(resultView(r), lp(MATCH, WRAP))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Listen again", true) { onPulseTap() }, lp(0, WRAP, 1.3f))
        row.addView(button("Copy", false) { copy(r.shareText()) }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        row.addView(button("Share", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, r.shareText())
            }, "Share chords"))
        }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        resultBox.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    // ---------- service & capture permission ----------
    private fun sendService(listen: Boolean = false, bubble: Boolean? = null, fromBubble: Boolean = false,
                            code: Int? = null, data: Intent? = null) {
        val i = Intent(this, ListenService::class.java)
        if (listen) i.putExtra(ListenService.EXTRA_LISTEN, true)
        if (fromBubble) i.putExtra(ListenService.EXTRA_FROM_BUBBLE, true)
        if (bubble != null) i.putExtra(ListenService.EXTRA_BUBBLE, bubble)
        if (code != null && data != null) { i.putExtra(ListenService.EXTRA_CODE, code); i.putExtra(ListenService.EXTRA_DATA, data) }
        startForegroundService(i)
        if (listen && !fromBubble) { pulse.listening = true; pulse.label = "…"; status.text = "Listening…" }
    }

    private fun requestCapture(listen: Boolean, bubble: Boolean?, fromBubble: Boolean) {
        pendListen = listen; pendBubble = bubble; pendFromBubble = fromBubble
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        toast("Tap \"Start\" so ChordEar can hear what's playing")
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            sendService(pendListen, pendBubble, pendFromBubble, resultCode, data)
        } else {
            toast("Without that permission ChordEar will use the microphone")
            sendService(pendListen, pendBubble, pendFromBubble)
        }
        if (pendFromBubble || pendBubble == true) moveTaskToBack(true)
        updateBubbleButtonLater()
    }

    // ---------- pop-up bubble ----------
    private fun toggleBubble() {
        if (ListenService.bubbleOn) {
            ListenService.instance?.turnOffBubble()
            updateBubbleButtonLater(); return
        }
        withMic {
            if (!Settings.canDrawOverlays(this)) {
                toast("Allow ChordEar to display over other apps")
                waitingForOverlay = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else startBubble()
        }
    }

    private fun startBubble() {
        toast("Bubble is on — open your music app and tap it")
        if (Prefs.phoneAudio(this) && AudioSources.phoneCaptureSupported && !ListenService.hasProjection) {
            requestCapture(listen = false, bubble = true, fromBubble = false)
        } else {
            sendService(bubble = true)
            updateBubbleButtonLater()
            moveTaskToBack(true)
        }
    }

    private fun updateBubbleButtonLater() = bubbleBtn.postDelayed({ updateBubbleButton() }, 400)

    private fun updateBubbleButton() {
        bubbleBtn.text = if (ListenService.bubbleOn) "Turn off pop-up bubble" else "Turn on pop-up bubble"
    }

    // ---------- small UI bits ----------
    private fun segBtn(label: String, onClick: () -> Unit) = text(label, 14f, C.INK, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { if (!ListenService.listening) onClick() }
    }

    private fun renderSource() {
        val phone = Prefs.phoneAudio(this) && AudioSources.phoneCaptureSupported
        for ((v, on) in listOf(srcPhone to phone, srcMic to !phone)) {
            v.background = if (on) rounded(C.AMBER, 11) else null
            v.setTextColor(if (on) android.graphics.Color.parseColor("#1B1208") else C.MUTE)
        }
        if (!ListenService.listening) idleText()
    }

    private fun renderExt() {
        extBtn.text = if (Prefs.extended(this)) "7ths & sus chords: ON (less accurate)" else "7ths & sus chords: OFF (most accurate)"
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val items = History.list(this)
        if (items.isEmpty()) { historyBox.addView(text("Songs you check will show up here.", 14f, C.MUTE)); return }
        for (it in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.PANEL, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text(it[2], 17f, C.AMBER2, true))
                if (it[3].isNotBlank()) addView(text(it[3], 13f, C.TEAL))
                addView(text("${it[1]}  ·  ${it[0]}", 12f, C.MUTE))
                setOnLongClickListener { _ -> copy("${it[1]}: ${it[2]}"); true }
            }
            historyBox.addView(card, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        historyBox.addView(button("Clear history", false) { History.clear(this); renderHistory() },
            lp(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    private fun withMic(action: () -> Unit) {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) { action(); return }
        pendingAction = action
        requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val a = pendingAction; pendingAction = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) a?.invoke()
        else status.text = "ChordEar needs audio permission to hear the song."
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chords", s))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
