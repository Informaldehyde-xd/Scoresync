package com.infor.scoresync

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvOffset: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var noteOnEvents: List<MidiNoteEvent> = emptyList()
    private var cursorIndex = 0
    private var offsetMs = 0L

    private var mp3Uri: Uri? = null

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

    private val pickMusicXml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val xmlText = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        if (xmlText != null) {
            webView.evaluateJavascript("loadMusicXml(${JSONObject.quote(xmlText)});", null)
            resetSequence()
            Toast.makeText(this, "MusicXML loaded", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val stream = contentResolver.openInputStream(uri)
        if (stream != null) {
            noteOnEvents = MidiParser.parse(stream).filter { it.isNoteOn }.sortedBy { it.timeMs }
            cursorIndex = 0
            Toast.makeText(this, "MIDI loaded (${noteOnEvents.size} notes)", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickMp3 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mp3Uri = uri
        mediaPlayer?.release()
        mediaPlayer = null
        Toast.makeText(this, "MP3 loaded", Toast.LENGTH_SHORT).show()
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

        val seekOffset = findViewById<SeekBar>(R.id.seekOffset)
        seekOffset.progress = 2000
        seekOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                offsetMs = (progress - 2000).toLong()
                tvOffset.text = "Offset: $offsetMs ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnImportXml).setOnClickListener {
            pickMusicXml.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnImportMidi).setOnClickListener {
            pickMidi.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnImportMp3).setOnClickListener {
            pickMp3.launch(arrayOf("audio/mpeg", "audio/mp3", "*/*"))
        }

        findViewById<Button>(R.id.btnPlay).setOnClickListener { playSequence() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSequence() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetSequence() }
    }

    private fun playSequence() {
        val uri = mp3Uri
        if (uri == null) {
            Toast.makeText(this, "Import an MP3 first", Toast.LENGTH_SHORT).show()
            return
        }
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
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
