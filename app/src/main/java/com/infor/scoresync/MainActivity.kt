package com.infor.scoresync

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var onsetTimestamps: List<Long> = emptyList()
    private var cursorIndex = 0
    private var offsetMs = 0L

    private var mp3Uri: Uri? = null
    private var lastMidiUri: Uri? = null
    private var lastXmlUri: Uri? = null

    private var pageReady = false
    private var pendingXmlToLoad: String? = null

    private val prefs by lazy { getSharedPreferences("scoresync_state", Context.MODE_PRIVATE) }

    private val syncRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) return

            val playbackTime = mp.currentPosition.toLong()
            val targetIndex = onsetTimestamps.count { it <= (playbackTime - offsetMs) }

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
        lastXmlUri = uri
        prefs.edit().putString("xmlUri", uri.toString()).apply()
        loadXmlFromUri(uri)
    }

    private val pickMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lastMidiUri = uri
        prefs.edit().putString("midiUri", uri.toString()).apply()
        loadMidiFromUri(uri)
    }

    private val pickMp3 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mp3Uri = uri
        prefs.edit().putString("mp3Uri", uri.toString()).apply()
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
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingXmlToLoad?.let { xml ->
                    webView.evaluateJavascript("loadMusicXml(${JSONObject.quote(xml)});", null)
                    pendingXmlToLoad = null
                }
                restoreSavedState()
            }
        }

        webView.loadUrl("file:///android_asset/osmd/index.html")

        val seekOffset = findViewById<SeekBar>(R.id.seekOffset)
        val savedOffset = prefs.getInt("offsetProgress", 2000)
        seekOffset.progress = savedOffset
        offsetMs = (savedOffset - 2000).toLong()
        tvOffset.text = "Offset: $offsetMs ms"

        seekOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                offsetMs = (progress - 2000).toLong()
                tvOffset.text = "Offset: $offsetMs ms"
                prefs.edit().putInt("offsetProgress", progress).apply()
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

    private fun restoreSavedState() {
        prefs.getString("xmlUri", null)?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            lastXmlUri = uri
            loadXmlFromUri(uri)
        }
        prefs.getString("midiUri", null)?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            lastMidiUri = uri
            loadMidiFromUri(uri)
        }
        prefs.getString("mp3Uri", null)?.let { uriStr ->
            mp3Uri = Uri.parse(uriStr)
        }
    }

    private fun loadXmlFromUri(uri: Uri) {
        val xmlText = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (xmlText != null) {
            pushXmlToWebView(xmlText)
        }
    }

    private fun loadMidiFromUri(uri: Uri) {
        val stream = try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
        if (stream != null) {
            noteOnEvents = MidiParser.parse(stream).filter { it.isNoteOn }.sortedBy { it.timeMs }
            onsetTimestamps = noteOnEvents.map { it.timeMs }.distinct().sorted()
            cursorIndex = 0
        }
    }

    private fun pushXmlToWebView(xml: String) {
        if (pageReady) {
            webView.evaluateJavascript("loadMusicXml(${JSONObject.quote(xml)});", null)
        } else {
            pendingXmlToLoad = xml
        }
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
