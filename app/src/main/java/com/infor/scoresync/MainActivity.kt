package com.infor.scoresync

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Toast.makeText(
                    this@MainActivity,
                    "JS: ${consoleMessage.message()}",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/osmd/index.html")

        findViewById<Button>(R.id.btnPlay).setOnClickListener { playSequence() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSequence() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetSequence() }
    }

    private fun playSequence() {
        if (isPlaying) return
        isPlaying = true

        val events = MidiParser.parse(this, "osmd/test.mid")
        val noteOnEvents = events.filter { it.isNoteOn }.sortedBy { it.timeMs }

        for (event in noteOnEvents) {
            handler.postDelayed({
                webView.evaluateJavascript("cursorNext();", null)
            }, event.timeMs)
        }

        val totalDuration = noteOnEvents.maxOfOrNull { it.timeMs } ?: 0L
        handler.postDelayed({ isPlaying = false }, totalDuration + 200)
    }

    private fun stopSequence() {
        handler.removeCallbacksAndMessages(null)
        isPlaying = false
    }

    private fun resetSequence() {
        stopSequence()
        webView.evaluateJavascript("resetCursor();", null)
    }
}
