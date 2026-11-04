package com.gabrydl99.gfc.core

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Phone storage helpers: real paths, copies, moving originals, gallery refresh. */
object Storage {
    fun allFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    /** File name for a picked or shared item, with an extension guessed from its type when missing. */
    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "file") name = uri.lastPathSegment
        else runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        var n = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file").replace('/', '_')
        if (extOf(n).isEmpty()) {
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrEmpty()) n = "$n.$ext"
        }
        return n
    }

    fun size(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: return 0).length()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        return runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
    }

    private fun volumePath(docId: String): String? {
        val type = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base = when (type) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home" -> File(Environment.getExternalStorageDirectory(), "Documents").absolutePath
            "raw" -> return rel
            else -> if (type.isNotEmpty()) "/storage/$type" else return null
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    private fun queryData(ctx: Context, base: Uri, id: String?): String? = runCatching {
        val (sel, args) = if (id != null) "_id=?" to arrayOf(id) else null to null
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns.DATA), sel, args, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    /** The real file behind a URI, when the app may read it directly; else null (the file gets copied). */
    fun pathFor(ctx: Context, uri: Uri): String? {
        val path: String? = try {
            when {
                uri.scheme == "file" -> uri.path
                DocumentsContract.isDocumentUri(ctx, uri) -> {
                    val id = DocumentsContract.getDocumentId(uri)
                    when (uri.authority) {
                        "com.android.externalstorage.documents" -> volumePath(id)
                        "com.android.providers.downloads.documents" -> when {
                            id.startsWith("raw:") -> id.removePrefix("raw:")
                            id.startsWith("msf:") -> queryData(ctx, MediaStore.Files.getContentUri("external"), id.removePrefix("msf:"))
                            else -> id.toLongOrNull()?.let {
                                queryData(ctx, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), it), null)
                            }
                        }
                        "com.android.providers.media.documents" -> {
                            val type = id.substringBefore(':')
                            val num = id.substringAfter(':')
                            val base = when (type) {
                                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                else -> MediaStore.Files.getContentUri("external")
                            }
                            queryData(ctx, base, num)
                        }
                        else -> queryData(ctx, uri, null)
                    }
                }
                else -> queryData(ctx, uri, null)
            }
        } catch (e: Exception) {
            null
        }
        if (path.isNullOrEmpty()) return null
        val f = File(path)
        return if (f.isFile && f.canRead()) f.absolutePath else null
    }

    /** A folder picked with the system picker, as a real path (needs all-files access on Android 11+). */
    fun treeToPath(treeUri: Uri): String? = runCatching {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        volumePath(DocumentsContract.getTreeDocumentId(treeUri))
    }.getOrNull()

    fun copyToCache(ctx: Context, uri: Uri, name: String, job: Job): File {
        val dir = File(ctx.cacheDir, "in/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
        val total = size(ctx, uri).coerceAtLeast(1)
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw CompressionError("Couldn't read the file (Android didn't allow it). Add it again.")
            input.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        job.check()
                        job.progress((done.toFloat() / total).coerceAtMost(1f) * 0.999f, "Reading file")
                    }
                }
            }
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            throw CompressionError("Couldn't read the file (Android didn't allow it). Add it again.")
        } catch (e: java.io.IOException) {
            dir.deleteRecursively()
            if ("ENOSPC" in (e.message ?: "")) throw CompressionError("The phone storage is full.")
            throw CompressionError("Couldn't read the file (${e.message ?: e.javaClass.simpleName}).")
        }
        return out
    }

    /**
     * Moves the original into <root>/Original. Returns (new path or null, note or null).
     */
    fun moveOriginal(ctx: Context, path: String?, uri: Uri?, name: String, shared: Boolean, ws: Workspace): Pair<String?, String?> {
        val destDir = ws.originalDir()
        if (path != null) {
            val f = File(path)
            if (f.parentFile?.absolutePath == destDir.absolutePath) return path to null
            val dst = Workspace.uniqueFile(destDir, stemOf(name), extOf(name))
            val moved = f.renameTo(dst) || runCatching {
                f.copyTo(dst); if (!f.delete()) { dst.delete(); false } else true
            }.getOrDefault(false)
            if (moved) {
                scan(ctx, f.absolutePath, dst.absolutePath)
                return dst.absolutePath to null
            }
            return null to "Couldn't move the original into the Original folder."
        }
        if (uri != null && !shared && DocumentsContract.isDocumentUri(ctx, uri)) {
            val dst = Workspace.uniqueFile(destDir, stemOf(name), extOf(name))
            try {
                ctx.contentResolver.openInputStream(uri)?.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                if (dst.length() > 0 && DocumentsContract.deleteDocument(ctx.contentResolver, uri)) {
                    scan(ctx, dst.absolutePath)
                    return dst.absolutePath to null
                }
            } catch (_: Exception) {
            }
            dst.delete()
        }
        return null to if (shared) "The original was kept where it is, because it was shared from another app."
        else "The original was kept where it is: allow 'All files access' so the app can move it."
    }

    fun scan(ctx: Context, vararg paths: String) {
        runCatching { MediaScannerConnection.scanFile(ctx.applicationContext, paths, null, null) }
    }
}
