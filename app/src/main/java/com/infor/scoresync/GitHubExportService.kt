package com.infor.scoresync

import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject

object GitHubExportService {

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun post(action: () -> Unit) = mainHandler.post(action)

    fun exportVideo(
        owner: String,
        repo: String,
        token: String,
        musicXml: String,
        midiBytes: ByteArray,
        mp3Bytes: ByteArray,
        offsetMs: Long,
        onProgress: (String) -> Unit,
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                onProgressMain(onProgress, "Uploading files...")

                uploadFile(owner, repo, "export-input/song.musicxml", token, musicXml.toByteArray())
                uploadFile(owner, repo, "export-input/song.mid", token, midiBytes)
                uploadFile(owner, repo, "export-input/song.mp3", token, mp3Bytes)

                val config = JSONObject().apply {
                    put("offsetMs", offsetMs)
                    put("fps", 24)
                }
                // config.json uploaded LAST — it's the workflow trigger path
                uploadFile(owner, repo, "export-input/config.json", token, config.toString().toByteArray())

                onProgressMain(onProgress, "Waiting for GitHub Actions to start...")
                Thread.sleep(6000)

                val runId = findLatestRunId(owner, repo, token)
                    ?: throw Exception("Could not find workflow run")

                onProgressMain(onProgress, "Rendering video (this can take a while)...")
                val conclusion = pollUntilComplete(owner, repo, runId, token)

                if (conclusion != "success") {
                    throw Exception("Workflow finished with status: $conclusion")
                }

                onProgressMain(onProgress, "Downloading video...")
                val mp4Bytes = downloadAndExtractMp4(owner, repo, runId, token)

                post { onSuccess(mp4Bytes) }
            } catch (e: Exception) {
                post { onError(e.message ?: "Unknown error") }
            }
        }.start()
    }

    private fun onProgressMain(onProgress: (String) -> Unit, msg: String) {
        post { onProgress(msg) }
    }

    private fun connection(urlStr: String, token: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        return conn
    }

    private fun uploadFile(owner: String, repo: String, path: String, token: String, bytes: ByteArray) {
        val getUrl = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val getConn = connection(getUrl, token, "GET")
        val existingSha = try {
            if (getConn.responseCode == 200) {
                JSONObject(getConn.inputStream.bufferedReader().readText()).getString("sha")
            } else null
        } catch (e: Exception) { null } finally { getConn.disconnect() }

        val putConn = connection(getUrl, token, "PUT")
        putConn.doOutput = true
        val json = JSONObject().apply {
            put("message", "Add $path for video export")
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (existingSha != null) put("sha", existingSha)
        }
        putConn.outputStream.use { it.write(json.toString().toByteArray()) }

        val code = putConn.responseCode
        if (code != 200 && code != 201) {
            val err = putConn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            putConn.disconnect()
            throw Exception("Upload of $path failed: $err")
        }
        putConn.disconnect()
    }

    private fun findLatestRunId(owner: String, repo: String, token: String): Long? {
        val url = "https://api.github.com/repos/$owner/$repo/actions/workflows/export.yml/runs?per_page=1"
        val conn = connection(url, token, "GET")
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val runs = JSONObject(body).getJSONArray("workflow_runs")
            if (runs.length() == 0) null else runs.getJSONObject(0).getLong("id")
        } finally {
            conn.disconnect()
        }
    }

    private fun pollUntilComplete(owner: String, repo: String, runId: Long, token: String): String {
        val url = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId"
        var attempts = 0
        while (attempts < 120) {
            val conn = connection(url, token, "GET")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            if (json.getString("status") == "completed") {
                return json.getString("conclusion")
            }
            attempts++
            Thread.sleep(10000)
        }
        throw Exception("Timed out waiting for video export")
    }

    private fun downloadAndExtractMp4(owner: String, repo: String, runId: Long, token: String): ByteArray {
        val listUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts"
        val listConn = connection(listUrl, token, "GET")
        val listBody = listConn.inputStream.bufferedReader().readText()
        listConn.disconnect()

        val artifacts = JSONObject(listBody).getJSONArray("artifacts")
        if (artifacts.length() == 0) throw Exception("No artifacts found")
        val downloadUrl = artifacts.getJSONObject(0).getString("archive_download_url")

        val conn = connection(downloadUrl, token, "GET")
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        if (location == null) throw Exception("No redirect from GitHub")

        val fileConn = URL(location).openConnection() as HttpURLConnection
        fileConn.instanceFollowRedirects = true
        val zipBytes = fileConn.inputStream.use { it.readBytes() }
        fileConn.disconnect()

        val zis = ZipInputStream(zipBytes.inputStream())
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".mp4")) {
                val out = ByteArrayOutputStream()
                zis.copyTo(out)
                return out.toByteArray()
            }
            entry = zis.nextEntry
        }
        throw Exception("No .mp4 found in artifact")
    }
}
