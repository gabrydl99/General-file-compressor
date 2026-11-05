package com.gabrydl99.gfc.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * At start-up, asks GitHub for the latest release. When it's newer than this app and
 * has an APK, the screen offers to download and install it. Silent when offline.
 */
object Updates {
    const val REPO = "gabrydl99/General-file-compressor"
    private const val LOG = "GfcUpdate"

    data class Info(val version: String, val apkUrl: String, val size: Long, val pageUrl: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val progress: Float) : State
        data class Ready(val info: Info, val file: File) : State
        data class NeedsPermission(val info: Info, val file: File) : State
        data class Failed(val info: Info, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var download: CoJob? = null
    @Volatile private var checked = false

    fun parseVersion(text: String): List<Int> {
        val n = Regex("""\d+""").findAll(text).map { it.value.toIntOrNull() ?: 0 }.take(3).toList()
        return n + List(3 - n.size) { 0 }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate)
        val b = parseVersion(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Reads the latest release (or a test file); returns it when it's newer and has an APK. */
    fun fetchLatest(current: String, source: String? = null): Info? {
        val text = if (source != null) File(source).readText() else {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "GeneralFileCompressor-Android")
            try {
                if (c.responseCode != 200) return null
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        val o = JSONObject(text)
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
        }
        val newer = isNewer(tag, current)
        Log.i(LOG, "latest=$tag current=$current newer=$newer apk=${apk?.optString("name")}")
        if (!newer || apk == null) return null
        val notes = o.optString("body").replace(Regex("""[*#`>]"""), "").replace(Regex("""\n{3,}"""), "\n\n").trim()
        return Info(parseVersion(tag).joinToString("."), apk.optString("browser_download_url"),
            apk.optLong("size"), o.optString("html_url"), notes.take(600))
    }

    /** Checks once per app start (or again when [force] is set, for tests). */
    suspend fun check(current: String, source: String? = null, force: Boolean = false) {
        if (checked && !force) return
        checked = true
        val info = withContext(Dispatchers.IO) {
            try {
                fetchLatest(current, source)
            } catch (e: Exception) {
                Log.i(LOG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
        if (info != null && (_state.value is State.Idle || force)) _state.value = State.Available(info)
    }

    fun dismiss() {
        download?.cancel()
        _state.value = State.Idle
    }

    fun start(cacheDir: File, info: Info) {
        download?.cancel()
        _state.value = State.Downloading(info, 0f)
        download = scope.launch {
            val dir = File(cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "General-File-Compressor-${info.version}.apk")
            try {
                val c = URL(info.apkUrl).openConnection()
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "GeneralFileCompressor-Android")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                c.getInputStream().use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = 0L
                        while (isActive) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > 512 * 1024) {
                                last = done
                                _state.value = State.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (!isActive) { out.delete(); return@launch }
                if (out.length() < 1024 * 1024) throw java.io.IOException("the downloaded file is incomplete")
                Log.i(LOG, "downloaded ${out.length()} bytes")
                _state.value = State.Ready(info, out)
            } catch (e: Exception) {
                out.delete()
                if (isActive) {
                    Log.i(LOG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
                    _state.value = State.Failed(info, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun needsPermission(info: Info, file: File) {
        _state.value = State.NeedsPermission(info, file)
    }

    fun installed() {
        _state.value = State.Idle
    }
}
