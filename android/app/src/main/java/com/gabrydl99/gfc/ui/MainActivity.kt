package com.gabrydl99.gfc.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.gabrydl99.gfc.BuildConfig
import com.gabrydl99.gfc.core.Settings
import com.gabrydl99.gfc.core.Storage
import com.gabrydl99.gfc.core.Updates
import com.gabrydl99.gfc.core.Workspace
import com.gabrydl99.gfc.core.extOf
import com.gabrydl99.gfc.core.kindOf
import com.gabrydl99.gfc.work.CompressService
import com.gabrydl99.gfc.work.WorkRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    /** True when Android 11+ "All files access" is granted (or not needed). */
    val allFiles = MutableStateFlow(true)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris, shared = false)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }

    private val pickOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = Storage.treeToPath(uri)
        when {
            path == null -> toast("Choose a folder on the phone storage or the SD card.")
            !Storage.allFilesAccess() -> toast("Allow 'All files access' first, then choose the folder again.")
            else -> WorkRepo.updateSettings { it.copy(outputDir = path) }
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        CompressService.start(this)
    }

    private val askLegacyStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT <= 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            askLegacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        setContent {
            GfcTheme {
                MainScreen(
                    activity = this,
                    onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onAddFolder = { pickFolder.launch(null) },
                    onCompress = ::compress,
                    onAbort = { WorkRepo.abort() },
                    onChangeFolder = { pickOutput.launch(null) },
                    onOpenFolder = ::openOutputFolder,
                    onOpenFile = ::openFile,
                    onShareFile = ::shareFile,
                    onAllowFiles = ::askAllFiles,
                )
            }
        }
        handleIntent(intent)
        if (savedInstanceState == null) lifecycleScope.launch { Updates.check(BuildConfig.VERSION_NAME) }
    }

    override fun onResume() {
        super.onResume()
        allFiles.value = Storage.allFilesAccess()
        // back from "Allow from this source": install the update that was waiting
        (Updates.state.value as? Updates.State.NeedsPermission)?.let { st ->
            if (canInstall()) installUpdate(st.info, st.file)
        }
    }

    // ------------------------------------------------------------ updates

    fun startUpdate(info: Updates.Info) = Updates.start(cacheDir, info)

    private fun canInstall(): Boolean = packageManager.canRequestPackageInstalls()

    /** Opens Android's installer on the downloaded APK (asks for the install permission first if needed). */
    fun installUpdate(info: Updates.Info, file: File) {
        if (!canInstall()) {
            Updates.needsPermission(info, file)
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Allow General File Compressor to install apps in the phone settings, then come back.")
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Updates.installed()
        } catch (e: Exception) {
            toast("Couldn't open the installer: ${e.message}")
            openPage(info.pageUrl)
        }
    }

    fun openPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.ifEmpty { "https://github.com/${Updates.REPO}/releases/latest" })))
        } catch (_: Exception) {
            toast("No browser found.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) addUris(listOf(u), shared = true)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val l = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!l.isNullOrEmpty()) addUris(l, shared = true)
            }
            Intent.ACTION_VIEW -> intent.data?.let { addUris(listOf(it), shared = true) }
        }
        if (BuildConfig.DEBUG) handleTestHooks(intent)
    }

    /** Automated tests on the emulator drive the app with these extras (debug builds only). */
    private fun handleTestHooks(intent: Intent) {
        if (intent.getBooleanExtra("gfc_test_clear", false)) WorkRepo.clear()
        intent.getStringExtra("gfc_test_settings")?.let { json -> WorkRepo.updateSettings { Settings.fromJson(json) } }
        intent.getStringExtra("gfc_test_report")?.let { WorkRepo.reportFile = File(it).also { f -> f.delete() } }
        intent.getStringExtra("gfc_test_dir")?.let { dir ->
            val files = File(dir).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            WorkRepo.addPaths(files)
        }
        if (intent.getBooleanExtra("gfc_test_start", false)) compress()
        intent.getStringExtra("gfc_test_update_json")?.let { src ->
            val now = intent.getBooleanExtra("gfc_test_update_now", false)
            lifecycleScope.launch {
                Updates.check(BuildConfig.VERSION_NAME, src, force = true)
                if (now) (Updates.state.value as? Updates.State.Available)?.let { startUpdate(it.info) }
            }
        }
    }

    private fun addUris(uris: List<Uri>, shared: Boolean) {
        lifecycleScope.launch {
            val skipped = withContext(Dispatchers.IO) { WorkRepo.addUris(applicationContext, uris, shared) }
            if (skipped > 0) toast("$skipped file(s) skipped: type not supported.")
        }
    }

    private fun addFolder(tree: Uri) {
        lifecycleScope.launch {
            val skipped = withContext(Dispatchers.IO) {
                val path = Storage.treeToPath(tree)
                val dir = path?.let { File(it) }
                if (dir != null && dir.canRead() && dir.listFiles() != null) {
                    val files = dir.walkTopDown().maxDepth(8)
                        .filter { it.isFile && !it.name.startsWith(".") && kindOf(it.name) != null }
                        .filter { !it.absolutePath.contains("/${Workspace.FOLDER_NAME}/") }.toList()
                    WorkRepo.addPaths(files)
                } else {
                    val root = DocumentFile.fromTreeUri(applicationContext, tree)
                    val found = ArrayList<Uri>()
                    fun walk(d: DocumentFile?, depth: Int) {
                        if (d == null || depth > 8) return
                        for (c in d.listFiles()) {
                            if (c.isDirectory) walk(c, depth + 1)
                            else if (c.name?.let { kindOf(it) } != null) found += c.uri
                        }
                    }
                    walk(root, 0)
                    WorkRepo.addUris(applicationContext, found, shared = false)
                }
            }
            if (skipped > 0) toast("$skipped file(s) skipped: type not supported.")
        }
    }

    private fun compress() {
        if (WorkRepo.pending().isEmpty()) {
            toast("Add some files to get started.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            CompressService.start(this)
        }
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openOutputFolder() {
        val root = WorkRepo.settings.value.outputDir?.let { File(it) } ?: Workspace.defaultRoot()
        val dir = File(root, "Compressed")
        val base = Environment.getExternalStorageDirectory().absolutePath
        if (dir.absolutePath.startsWith(base)) {
            val rel = dir.absolutePath.removePrefix(base).trimStart('/')
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            toast("Results are in ${dir.absolutePath}")
        }
    }

    private fun fileUri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(f.name)) ?: "*/*"

    fun openFile(f: File) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(f), mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("No app can open this file.")
        }
    }

    fun shareFile(f: File) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, fileUri(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, f.name))
        } catch (e: Exception) {
            toast("Couldn't share this file.")
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
