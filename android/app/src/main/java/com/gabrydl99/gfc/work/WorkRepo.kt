package com.gabrydl99.gfc.work

import android.content.Context
import android.net.Uri
import com.gabrydl99.gfc.core.Job
import com.gabrydl99.gfc.core.Kind
import com.gabrydl99.gfc.core.Result
import com.gabrydl99.gfc.core.Settings
import com.gabrydl99.gfc.core.Storage
import com.gabrydl99.gfc.core.kindOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Status { WAITING, WORKING, DONE, FAILED, STOPPED }

data class FileItem(
    val id: Long,
    val name: String,
    val size: Long,
    val kind: Kind,
    val path: String?,          // real file, when the app can read it directly
    val uri: Uri?,              // otherwise the content to copy
    val shared: Boolean,        // came from another app's Share
    val status: Status = Status.WAITING,
    val progress: Float = 0f,
    val stage: String = "",
    val result: Result? = null,
)

data class RunState(
    val running: Boolean = false,
    val aborting: Boolean = false,
    val total: Int = 0,
    val index: Int = 0,
    val summary: String = "",
)

/** Everything the screen and the background service share. */
object WorkRepo {
    private const val PREFS = "gfc"
    private val ids = AtomicLong(1)
    private lateinit var appContext: Context

    private val _items = MutableStateFlow<List<FileItem>>(emptyList())
    val items: StateFlow<List<FileItem>> = _items.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run.asStateFlow()

    @Volatile var currentJob: Job? = null

    /** Set by test runs (debug builds) to write a report when the batch ends. */
    @Volatile var reportFile: File? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        _settings.value = Settings.fromJson(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("settings", null))
    }

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("settings", _settings.value.toJson()).apply()
    }

    /** Adds picked/shared files; returns how many were skipped as unsupported. */
    fun addUris(ctx: Context, uris: List<Uri>, shared: Boolean): Int {
        var skipped = 0
        val existing = _items.value
        val added = ArrayList<FileItem>()
        for (u in uris) {
            val name = Storage.displayName(ctx, u)
            val kind = kindOf(name)
            if (kind == null) { skipped++; continue }
            val path = Storage.pathFor(ctx, u)
            if (path != null && (existing.any { it.path == path } || added.any { it.path == path })) continue
            if (path == null && (existing.any { it.uri == u } || added.any { it.uri == u })) continue
            val size = path?.let { File(it).length() } ?: Storage.size(ctx, u)
            added += FileItem(ids.getAndIncrement(), name, size, kind, path, if (path == null) u else null, shared)
        }
        _items.update { it + added }
        return skipped
    }

    fun addPaths(paths: List<File>): Int {
        var skipped = 0
        val added = ArrayList<FileItem>()
        for (f in paths) {
            val kind = kindOf(f.name)
            if (kind == null || !f.isFile) { skipped++; continue }
            if (_items.value.any { it.path == f.absolutePath }) continue
            added += FileItem(ids.getAndIncrement(), f.name, f.length(), kind, f.absolutePath, null, false)
        }
        _items.update { it + added }
        return skipped
    }

    fun remove(id: Long) = _items.update { l -> l.filterNot { it.id == id && it.status != Status.WORKING } }

    fun clear() = _items.update { l -> l.filter { it.status == Status.WORKING } }

    fun resetItem(id: Long) = edit(id) { it.copy(status = Status.WAITING, progress = 0f, stage = "", result = null) }

    fun edit(id: Long, f: (FileItem) -> FileItem) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun pending(): List<FileItem> = _items.value.filter { it.status == Status.WAITING || it.status == Status.STOPPED }

    fun setRun(f: (RunState) -> RunState) = _run.update(f)

    fun abort() {
        _run.update { it.copy(aborting = true) }
        currentJob?.cancel()
    }
}
