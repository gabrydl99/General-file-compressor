package com.gabrydl99.gfc.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Picks the right engine for each file, then moves the original (port of engine.py). */
object Engine {
    fun compress(
        ctx: Context, name: String, path: String?, uri: Uri?, shared: Boolean,
        settings: Settings, job: Job,
    ): Result {
        val ws = Workspace(ctx, settings)
        val kind = kindOf(name)
        var copy: File? = null
        val size: Long
        try {
            val file = when {
                path != null -> File(path).also { if (!it.exists()) return Result(false, message = "File not found.") }
                uri != null -> {
                    val sz = Storage.size(ctx, uri)
                    if (sz == 0L) return Result(false, message = "The file is empty.")
                    if (!settings.convertOnly && kind !in listOf(Kind.PDF, Kind.OFFICE) && settings.targetBytes(sz) >= sz) {
                        return Result(false, originalSize = sz, targetSize = settings.targetBytes(sz),
                            message = "Already smaller than the size you picked.")
                    }
                    Storage.copyToCache(ctx, uri, name, job).also { copy = it }
                }
                else -> return Result(false, message = "File not found.")
            }
            size = file.length()
            if (size == 0L) return Result(false, message = "The file is empty.")
            if (settings.convertOnly && (kind == Kind.PDF || kind == Kind.OFFICE)) {
                return Result(false, originalSize = size, message = "PDF and Office files can only be compressed, not converted.")
            }
            if (!settings.convertOnly && settings.targetBytes(size) >= size) {
                return Result(false, originalSize = size, targetSize = settings.targetBytes(size),
                    message = "Already smaller than the size you picked.")
            }
            val src = Source(file, name)
            var r = try {
                when (kind) {
                    Kind.VIDEO -> MediaEngine.compressVideo(src, ws, job)
                    Kind.AUDIO -> MediaEngine.compressAudio(src, ws, job)
                    Kind.IMAGE -> ImageEngine.compress(src, ws, job)
                    Kind.PDF -> PdfEngine.compress(src, ws, job)
                    Kind.OFFICE -> OfficeEngine.compress(src, ws, job)
                    null -> Result(false, originalSize = size, message = "This file type isn't supported.")
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: CompressionError) {
                Result(false, originalSize = size, message = e.message ?: "Error")
            } catch (e: SecurityException) {
                Result(false, originalSize = size, message = "Android didn't allow writing in the output folder.")
            } catch (e: IOException) {
                val m = e.message ?: ""
                Result(false, originalSize = size, message = when {
                    "ENOSPC" in m || "No space" in m -> "The phone storage is full."
                    "EACCES" in m || "Permission denied" in m -> "Android didn't allow writing in the output folder."
                    else -> "File system error: $m".take(300)
                })
            } catch (e: OutOfMemoryError) {
                Result(false, originalSize = size, message = "Not enough memory for this file.")
            } catch (e: Exception) {
                Result(false, originalSize = size, message = "Unexpected error: ${e.javaClass.simpleName}: ${e.message}".take(300))
            }
            if (r.ok) {
                r.output?.let { Storage.scan(ctx, it.absolutePath) }
                if (settings.moveOriginals) {
                    val (moved, note) = Storage.moveOriginal(ctx, path, uri, name, shared, ws)
                    r = r.copy(originalMovedTo = moved, notes = if (note != null) r.notes + note else r.notes)
                }
            }
            return r
        } finally {
            copy?.parentFile?.deleteRecursively()
        }
    }
}
