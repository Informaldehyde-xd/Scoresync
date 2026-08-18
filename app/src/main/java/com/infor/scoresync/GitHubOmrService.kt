package com.infor.scoresync

import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.json.JSONArray

object GitHubOmrService {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun post(action: () -> Unit) = mainHandler.post(action)

    fun convertPdf(
        owner: String,
        repo: String,
        token: String,
        pdfBytes: ByteArray,
        filename: String,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val path = "omr-input/$filename"
                onProgressMain(onProgress, "Uploading PDF to GitHub...")

                val existingSha = getExistingFileSha(owner, repo, path, token)
                uploadFile(owner, repo, path, token, pdfBytes, existingSha)

                onProgressMain(onProgress, "Waiting for GitHub Actions to start...")
                Thread.sleep(6000)

                val runId = findLatestRunId(owner, repo, token)
                    ?: throw Exception("Could not find workflow run")

                onProgressMain(onProgress, "Running OMR conversion (this can take a few minutes)...")
                val conclusion = pollUntilComplete(owner, repo, runId, token)

                if (conclusion != "success") {
                    throw Exception("Workflow finished with status: $conclusion")
                }

                onProgressMain(onProgress, "Downloading result...")
                val xml = downloadAndExtractMusicXml(owner, repo, runId, token)

                post { onSuccess(xml) }
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

    private fun getExistingFileSha(owner: String, repo: String, path: String, token: String): String? {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val conn = connection(url, token, "GET")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).getString("sha")
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadFile(owner: String, repo: String, path: String, token: String, bytes: ByteArray, existingSha: String?) {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val conn = connection(url, token, "PUT")
        conn.doOutput = true

        val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("message", "Add PDF for OMR conversion")
            put("content", base64Content)
            if (existingSha != null) put("sha", existingSha)
        }

        conn.outputStream.use { it.write(json.toString().toByteArray()) }

        val code = conn.responseCode
        if (code != 200 && code != 201) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            conn.disconnect()
            throw Exception("Upload failed: $err")
        }
        conn.disconnect()
    }

    private fun findLatestRunId(owner: String, repo: String, token: String): Long? {
        val url = "https://api.github.com/repos/$owner/$repo/actions/workflows/omr.yml/runs?per_page=1"
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
        while (attempts < 60) {
            val conn = connection(url, token, "GET")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val status = json.getString("status")
            if (status == "completed") {
                return json.getString("conclusion")
            }
            attempts++
            Thread.sleep(10000)
        }
        throw Exception("Timed out waiting for workflow to finish")
    }

    private fun downloadAndExtractMusicXml(owner: String, repo: String, runId: Long, token: String): String {
        val listUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts"
        val listConn = connection(listUrl, token, "GET")
        val listBody = listConn.inputStream.bufferedReader().readText()
        listConn.disconnect()

        val artifacts: JSONArray = JSONObject(listBody).getJSONArray("artifacts")
        if (artifacts.length() == 0) throw Exception("No artifacts found")
        val downloadUrl = artifacts.getJSONObject(0).getString("archive_download_url")

        var conn = connection(downloadUrl, token, "GET")
        val location = conn.getHeaderField("Location")
        conn.disconnect()

        if (location == null) throw Exception("No redirect from GitHub artifact download")

        val fileConn = URL(location).openConnection() as HttpURLConnection
        fileConn.instanceFollowRedirects = true
        val zipBytes = fileConn.inputStream.use { it.readBytes() }
        fileConn.disconnect()

        val zis = ZipInputStream(zipBytes.inputStream())
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".musicxml")) {
                val out = ByteArrayOutputStream()
                zis.copyTo(out)
                return out.toString("UTF-8")
            }
            entry = zis.nextEntry
        }
        throw Exception("No .musicxml file found in artifact")
    }
}
