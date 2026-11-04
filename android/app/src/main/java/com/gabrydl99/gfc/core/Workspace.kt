package com.gabrydl99.gfc.core

import android.content.Context
import android.os.Environment
import java.io.File

/** The file being processed: a readable copy or the real file, and its display name. */
data class Source(val file: File, val name: String) {
    val ext get() = extOf(name)
    val stem get() = stemOf(name)
    val size get() = file.length()
}

/** Where results go: <root>/Compressed and <root>/Original. */
class Workspace(context: Context, val settings: Settings) {
    val root: File = settings.outputDir?.let { File(it) } ?: defaultRoot()
    val temp: File = File(context.cacheDir, "work").apply { mkdirs() }

    fun compressedDir(): File = ensure(File(root, "Compressed"))
    fun originalDir(): File = ensure(File(root, "Original"))

    private fun ensure(d: File): File {
        if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) {
            throw CompressionError("Android didn't allow writing in the output folder ($d).")
        }
        return d
    }

    /** `photo.jpg` -> `<root>/Compressed/photo_compressed.jpg`, never overwriting. */
    fun outputFile(src: Source, newExt: String? = null): File {
        val ext = newExt ?: src.ext
        return uniqueFile(compressedDir(), "${src.stem}_compressed", ext)
    }

    fun tempFile(prefix: String, ext: String): File = File.createTempFile(prefix, ".$ext", temp)

    companion object {
        const val FOLDER_NAME = "General File Compressor"

        fun defaultRoot(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)

        fun uniqueFile(folder: File, stem: String, ext: String): File {
            val dot = if (ext.isEmpty()) "" else ".$ext"
            var cand = File(folder, "$stem$dot")
            var i = 2
            while (cand.exists()) {
                cand = File(folder, "$stem ($i)$dot")
                i++
            }
            return cand
        }
    }
}
