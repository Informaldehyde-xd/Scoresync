package com.infor.scoresync

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvOffset: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var noteOnEvents: List<MidiNoteEvent> = emptyList()
    private var cursorIndex = 0
    private var offsetMs = 0L // -2000..2000

    private val syncRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) return

            val playbackTime = mp.currentPosition.toLong()
            val targetIndex = noteOnEvents.count { it.timeMs <= (playbackTime - offsetMs) }

            while (cursorIndex < targetIndex) {
                webView.evaluateJavascript("cursorNext();", null)
                cursorIndex++
            }

            handler.postDelayed(this, 30)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        tvOffset = findViewById(R.id.tvOffset)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Toast.makeText(this@MainActivity, "JS: ${consoleMessage.message()}", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/osmd/index.html")

        noteOnEvents = MidiParser.parse(this, "osmd/song.mid")
            .filter { it.isNoteOn }
            .sortedBy { it.timeMs }

        val seekOffset = findViewById<SeekBar>(R.id.seekOffset)
        seekOffset.progress = 2000 // midpoint = 0ms offset
        seekOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                offsetMs = (progress - 2000).toLong()
                tvOffset.text = "Offset: $offsetMs ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnPlay).setOnClickListener { playSequence() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSequence() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetSequence() }
    }

    private fun playSequence() {
        if (mediaPlayer == null) {
            val afd = assets.openFd("osmd/song.mp3")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
            }
        }
        mediaPlayer?.start()
        handler.post(syncRunnable)
    }

    private fun stopSequence() {
        mediaPlayer?.pause()
        handler.removeCallbacks(syncRunnable)
    }

    private fun resetSequence() {
        stopSequence()
        mediaPlayer?.seekTo(0)
        cursorIndex = 0
        webView.evaluateJavascript("resetCursor();", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
