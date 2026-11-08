package com.stm.chordear

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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

    private val controller = ListenController(this)
    private lateinit var pulse: PulseView
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var bubbleBtn: TextView
    private var pendingAction: (() -> Unit)? = null
    private var waitingForOverlay = false

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
        root.addView(text("Shazam for chords: tap and play the song", 15f, C.MUTE).apply {
            setPadding(0, dp(2), 0, dp(10))
        })

        pulse = PulseView(this).apply { setOnClickListener { onPulseTap() } }
        root.addView(FrameLayout(this).apply { addView(pulse, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER)) },
            lp(MATCH, dp(300)))

        status = text("Hold your phone near the music", 15f, C.MUTE).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14))
        }
        root.addView(status, lp(MATCH, WRAP))

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox, lp(MATCH, WRAP))

        bubbleBtn = button("Turn on pop-up bubble", false) { toggleBubble() }
        root.addView(bubbleBtn, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        root.addView(text("The pop-up bubble floats over YouTube, Spotify, TikTok and other apps. Tap it any time to find the chords without leaving the app.", 13f, C.MUTE).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        root.addView(text("HISTORY", 11f, C.MUTE, true).apply {
            letterSpacing = 0.15f; setPadding(0, dp(28), 0, dp(8))
        })
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox, lp(MATCH, WRAP))

        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(this)) startBubble()
            else toast("Pop-up permission wasn't turned on")
        }
        updateBubbleButton()
    }

    override fun onPause() {
        super.onPause()
        if (controller.listening) { controller.cancel(); resetIdle() }
    }

    // ---------- listening ----------
    private fun onPulseTap() {
        if (controller.listening) {
            if (controller.elapsed >= ListenController.MIN_SEC) {
                controller.finish(); status.text = "Working it out…"
            } else status.text = "Give it a few more seconds…"
            return
        }
        withMic {
            if (BubbleService.running) BubbleService.instance?.pauseForApp()
            resultBox.removeAllViews()
            controller.start()
            pulse.listening = true
            pulse.big = false
            pulse.label = "Listening"
            status.text = "Listening… play the song"
        }
    }

    override fun onLive(chord: Chord?, level: Float, elapsed: Double) {
        pulse.level = level
        if (chord != null) { pulse.big = true; pulse.label = Music.chordName(chord, null) }
        val left = (ListenController.MAX_SEC - elapsed).toInt().coerceAtLeast(0)
        status.text = if (level < 0.004f) "Can't hear anything — turn it up or move closer"
        else if (elapsed >= ListenController.MIN_SEC) "Listening… ${left}s (tap to finish now)"
        else "Listening… ${left}s"
    }

    override fun onResult(result: ChordResult?) {
        resetIdle()
        if (result == null) {
            status.text = "Couldn't make out the chords. Try again closer to the speaker."
            return
        }
        status.text = "Here's what's playing"
        History.add(this, result)
        showResult(result)
        renderHistory()
    }

    override fun onError(message: String) {
        resetIdle(); status.text = message
    }

    private fun resetIdle() {
        pulse.listening = false; pulse.big = false; pulse.label = "Tap to\nlisten"
    }

    private fun showResult(r: ChordResult) {
        resultBox.removeAllViews()
        resultBox.addView(resultView(r), lp(MATCH, WRAP))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Listen again", true) { onPulseTap() }, lp(0, WRAP, 1f))
        row.addView(button("Copy", false) { copy(r.shareText()) }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        row.addView(button("Share", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, r.shareText())
            }, "Share chords"))
        }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        resultBox.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val items = History.list(this)
        if (items.isEmpty()) {
            historyBox.addView(text("Songs you check will show up here.", 14f, C.MUTE)); return
        }
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

    // ---------- pop-up bubble ----------
    private fun toggleBubble() {
        if (BubbleService.running) {
            stopService(Intent(this, BubbleService::class.java))
            bubbleBtn.postDelayed({ updateBubbleButton() }, 300)
            return
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
        if (controller.listening) { controller.cancel(); resetIdle() }
        startForegroundService(Intent(this, BubbleService::class.java))
        bubbleBtn.postDelayed({ updateBubbleButton() }, 300)
        toast("Bubble is on — open your music app and tap it")
        moveTaskToBack(true)
    }

    private fun updateBubbleButton() {
        bubbleBtn.text = if (BubbleService.running) "Turn off pop-up bubble" else "Turn on pop-up bubble"
    }

    // ---------- permissions & utils ----------
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
        else status.text = "ChordEar needs the microphone to hear the song."
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chords", s))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
