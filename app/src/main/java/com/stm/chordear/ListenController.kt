package com.stm.chordear

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper

/** User settings. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** true = capture this phone's audio, false = microphone */
    fun phoneAudio(ctx: Context) = p(ctx).getBoolean("phone", AudioSources.phoneCaptureSupported)
    fun setPhoneAudio(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("phone", v).apply()
    fun extended(ctx: Context) = p(ctx).getBoolean("ext", false)
    fun setExtended(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ext", v).apply()
}

/** Runs one listening session (start -> live updates -> whole-clip analysis -> result) on the main thread. */
class ListenController(private val cb: Callback) : ChordEngine.Listener {

    interface Callback {
        fun onLive(chord: Chord?, level: Float, elapsed: Double)
        fun onAnalyzing() {}
        fun onResult(result: ChordResult?)
        fun onError(message: String)
    }

    companion object {
        const val MAX_SEC = 20.0
        const val MIN_SEC = 6.0
        /** Audio from the most recent listen (16-bit mono PCM at ChordEngine.SR). */
        @Volatile var lastClip: ByteArray? = null
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: ChordEngine? = null
    @Volatile var elapsed = 0.0; private set
    private var maxLevel = 0f
    private var phoneMode = false
    private var extended = false

    val listening: Boolean get() = engine != null

    fun start(makeRecord: () -> AudioRecord, phoneMode: Boolean, extended: Boolean) {
        if (engine != null) return
        elapsed = 0.0; maxLevel = 0f
        this.phoneMode = phoneMode; this.extended = extended
        engine = ChordEngine(this, makeRecord).also { it.start() }
    }

    /** Stop listening and analyse what was heard. */
    fun finish() {
        val e = engine ?: return
        engine = null
        cb.onAnalyzing()
        Thread {
            e.stop()
            val (spec, lev) = synchronized(e.spectra) { ArrayList(e.spectra) to ArrayList(e.levels) }
            lastClip = synchronized(e.pcm) { e.pcm.toByteArray() }
            val r = try {
                Analyzer.analyze(spec, lev, ChordEngine.SR, ChordEngine.N, ChordEngine.HOP, extended)
            } catch (ex: Exception) { null }
            main.post { cb.onResult(r) }
        }.start()
    }

    fun cancel() {
        val e = engine ?: return
        engine = null
        Thread { e.stop() }.start()
    }

    override fun onFrame(chord: Chord?, level: Float, timeSec: Double) {
        elapsed = timeSec
        if (level > maxLevel) maxLevel = level
        main.post {
            if (engine == null) return@post
            cb.onLive(chord, level, timeSec)
            if (timeSec >= MAX_SEC) finish()
            else if (timeSec > 4.0 && maxLevel < 0.0006f) {
                cancel()
                cb.onError(
                    if (phoneMode) "No sound is coming through. Make sure the song is playing — some apps block capture; if so, switch to Microphone."
                    else "Can't hear anything. Turn the music up or move closer."
                )
            }
        }
    }

    override fun onError(message: String) {
        main.post { engine = null; cb.onError(message) }
    }
}

/** Writes a clip to the phone's Downloads folder as a .wav file. */
object ClipSaver {
    fun save(ctx: Context, pcm: ByteArray): String {
        val name = "ChordEar-clip-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".wav"
        val wav = wav(pcm)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Couldn't create the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(wav) } ?: throw java.io.IOException("Couldn't write the file")
            return "Downloads/$name"
        } else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = java.io.File(dir, name); f.writeBytes(wav)
            return f.absolutePath
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val sr = ChordEngine.SR
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sr); b.putInt(sr * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }
}
