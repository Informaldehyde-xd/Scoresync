package com.infor.scoresync

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Toast.makeText(
            this@MainActivity,
            "JS: ${consoleMessage.message()}",
            Toast.LENGTH_LONG
        ).show()
        return true
    }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                playSequence()
            }
        })
        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        webView.loadUrl("file:///android_asset/osmd/index.html")
    }

    private fun playSequence() {
        val events = MidiParser.parse(this, "osmd/test.mid")
        val noteOnEvents = events.filter { it.isNoteOn }.sortedBy { it.timeMs }

        for (event in noteOnEvents) {
            handler.postDelayed({
                webView.evaluateJavascript("cursorNext();", null)
            }, event.timeMs)
        }
    }
}
