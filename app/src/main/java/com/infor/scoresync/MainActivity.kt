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
    private lateinit var tvOmrStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var noteOnEvents: List<MidiNoteEvent> = emptyList()
    private var onsetTimestamps: List<Long> = emptyList()
    private var cursorIndex = 0
    private var offsetMs = 0L

    private var mp3Uri: Uri? = null
    private var lastMidiUri: Uri? = null
    private var lastXmlUri: Uri? = null
    private var currentXmlText: String? = null

    private var pageReady = false
    private var pendingXmlToLoad: String? = null
    private var cursorVisible = true

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

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            Toast.makeText(this, "Could not read PDF", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val owner = prefs.getString("githubOwner", "")!!
        val repo = prefs.getString("githubRepo", "")!!
        val token = prefs.getString("githubToken", "")!!

        GitHubOmrService.convertPdf(
            owner, repo, token, bytes, "score.pdf",
            onProgress = { msg -> tvOmrStatus.text = msg },
            onSuccess = { xml ->
                tvOmrStatus.text = "Done — score loaded"
                pushXmlToWebView(xml)
                resetSequence()
            },
            onError = { err ->
                tvOmrStatus.text = "Error: $err"
                Toast.makeText(this, "OMR failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        tvOffset = findViewById(R.id.tvOffset)
        tvOmrStatus = findViewById(R.id.tvOmrStatus)

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

        findViewById<Button>(R.id.btnToggleCursor).setOnClickListener { toggleCursor() }

        findViewById<Button>(R.id.btnPlay).setOnClickListener { playSequence() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSequence() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetSequence() }

        findViewById<Button>(R.id.btnGitHubSettings).setOnClickListener { showGitHubSettingsDialog() }

        findViewById<Button>(R.id.btnGenerateScore).setOnClickListener {
            val owner = prefs.getString("githubOwner", null)
            val repo = prefs.getString("githubRepo", null)
            val token = prefs.getString("githubToken", null)
            if (owner.isNullOrBlank() || repo.isNullOrBlank() || token.isNullOrBlank()) {
                Toast.makeText(this, "Set up GitHub Settings first", Toast.LENGTH_SHORT).show()
                showGitHubSettingsDialog()
                return@setOnClickListener
            }
            pickPdf.launch(arrayOf("application/pdf"))
        }

        findViewById<Button>(R.id.btnExportVideo).setOnClickListener { exportVideo() }
    }

    private fun toggleCursor() {
        cursorVisible = !cursorVisible
        val btn = findViewById<Button>(R.id.btnToggleCursor)
        if (cursorVisible) {
            webView.evaluateJavascript("showCursorEl();", null)
            btn.text = "Hide Cursor"
        } else {
            webView.evaluateJavascript("hideCursorEl();", null)
            btn.text = "Show Cursor"
        }
    }

    private fun exportVideo() {
        val xml = currentXmlText
        val midiUri = lastMidiUri
        val mp3 = mp3Uri
        val owner = prefs.getString("githubOwner", null)
        val repo = prefs.getString("githubRepo", null)
        val token = prefs.getString("githubToken", null)

        if (xml == null || midiUri == null || mp3 == null) {
            Toast.makeText(this, "Load MusicXML, MIDI, and MP3 first", Toast.LENGTH_SHORT).show()
            return
        }
        if (owner.isNullOrBlank() || repo.isNullOrBlank() || token.isNullOrBlank()) {
            Toast.makeText(this, "Set up GitHub Settings first", Toast.LENGTH_SHORT).show()
            showGitHubSettingsDialog()
            return
        }

        val midiBytes = contentResolver.openInputStream(midiUri)?.use { it.readBytes() }
        val mp3Bytes = contentResolver.openInputStream(mp3)?.use { it.readBytes() }
        if (midiBytes == null || mp3Bytes == null) {
            Toast.makeText(this, "Could not read MIDI/MP3 files", Toast.LENGTH_SHORT).show()
            return
        }

        GitHubExportService.exportVideo(
            owner, repo, token, xml, midiBytes, mp3Bytes, offsetMs,
            onProgress = { msg -> tvOmrStatus.text = msg },
            onSuccess = { mp4Bytes ->
                val outFile = java.io.File(getExternalFilesDir(null), "exported_video.mp4")
                outFile.writeBytes(mp4Bytes)
                tvOmrStatus.text = "Video saved: ${outFile.absolutePath}"
                Toast.makeText(this, "Video exported!", Toast.LENGTH_LONG).show()
            },
            onError = { err ->
                tvOmrStatus.text = "Export error: $err"
                Toast.makeText(this, "Export failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showGitHubSettingsDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val ownerInput = android.widget.EditText(this).apply {
            hint = "GitHub username/org"
            setText(prefs.getString("githubOwner", ""))
        }
        val repoInput = android.widget.EditText(this).apply {
            hint = "Repo name (e.g. ScoreSync)"
            setText(prefs.getString("githubRepo", ""))
        }
        val tokenInput = android.widget.EditText(this).apply {
            hint = "Personal Access Token"
            setText(prefs.getString("githubToken", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(ownerInput)
        layout.addView(repoInput)
        layout.addView(tokenInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("GitHub Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("githubOwner", ownerInput.text.toString().trim())
                    .putString("githubRepo", repoInput.text.toString().trim())
                    .putString("githubToken", tokenInput.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        currentXmlText = xml
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
