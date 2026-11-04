package com.gabrydl99.gfc.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Photos: quality search first, then gentle downscaling (port of image.py). */
object ImageEngine {
    private const val QUALITY_FLOOR = 50   // below this JPEG/WebP gets ugly; shrink the picture instead
    private const val QUALITY_MIN = 15     // last resort
    private const val MAX_PIXELS = 60_000_000L

    enum class Fmt(val ext: String) { JPEG("jpg"), PNG("png"), WEBP("webp") }

    fun decode(file: File): Bitmap {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(file)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val px = info.size.width.toLong() * info.size.height
                    if (px > MAX_PIXELS) {
                        val f = sqrt(MAX_PIXELS.toDouble() / px)
                        decoder.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        } catch (e: Exception) {
            // fall back to BitmapFactory below
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw CompressionError("Couldn't open this picture (unsupported or damaged).")
            return rotateByExif(bmp, file)
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        }
    }

    private fun rotateByExif(bmp: Bitmap, file: File): Bitmap {
        val o = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun hasAlpha(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val w = bmp.width
        val row = IntArray(w)
        val step = max(1, bmp.height / 400)
        var y = 0
        while (y < bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (c in row) if (c ushr 24 != 0xFF) return true
            y += step
        }
        return false
    }

    fun onWhite(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Good-quality downscale: halve while far from the target, then one filtered step. */
    fun scaled(bmp: Bitmap, scale: Double): Bitmap {
        if (scale >= 0.9999) return bmp
        val tw = max(16, (bmp.width * scale).roundToInt())
        val th = max(16, (bmp.height * scale).roundToInt())
        var cur = bmp
        while (cur.width / 2 >= tw * 1.5 && cur.height / 2 >= th * 1.5) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== bmp) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== bmp && cur !== out) cur.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap, fmt: Fmt, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(max(64 * 1024, bmp.byteCount / 8))
        val cf = when (fmt) {
            Fmt.JPEG -> Bitmap.CompressFormat.JPEG
            Fmt.PNG -> Bitmap.CompressFormat.PNG
            Fmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        }
        if (!bmp.compress(cf, quality.coerceIn(0, if (fmt == Fmt.WEBP) 99 else 100), bos)) {
            throw CompressionError("Couldn't save the picture.")
        }
        return bos.toByteArray()
    }

    private class Encoder(val img: Bitmap, val fmt: Fmt) {
        private var cacheScale = 1.0
        private var cacheBmp: Bitmap? = null
        fun at(scale: Double): Bitmap {
            if (scale >= 0.9999) return img
            if (cacheBmp != null && cacheScale == scale) return cacheBmp!!
            cacheBmp?.let { if (it !== img) it.recycle() }
            cacheBmp = scaled(img, scale)
            cacheScale = scale
            return cacheBmp!!
        }
        fun encode(q: Int, scale: Double) = encode(at(scale), fmt, q)
    }

    /** Highest quality in [lo, hi] that fits the target at this scale. */
    private fun searchQuality(enc: Encoder, target: Long, scale: Double, lo: Int, hi: Int, job: Job): ByteArray? {
        var data = enc.encode(lo, scale)
        if (data.size > target) return null
        var best = data
        var a = lo + 1
        var b = hi
        while (a <= b) {
            job.check()
            val mid = (a + b) / 2
            data = enc.encode(mid, scale)
            if (data.size <= target) { best = data; a = mid + 1 } else b = mid - 1
        }
        return best
    }

    fun compress(src: Source, ws: Workspace, job: Job): Result {
        val s = ws.settings
        val orig = src.size
        val target = s.targetBytes(orig)
        val notes = mutableListOf<String>()
        job.progress(0.05f, "Reading")
        var img = try {
            decode(src.file)
        } catch (e: CompressionError) {
            throw e
        } catch (e: Exception) {
            throw CompressionError("Couldn't open this picture (${e.javaClass.simpleName}).")
        }
        if (src.ext == "gif" || src.ext == "tif" || src.ext == "tiff") notes += "Only the first page/frame was kept"
        val ext = src.ext
        val isJpeg = ext in listOf("jpg", "jpeg", "jfif")
        val alpha = hasAlpha(img)
        val (fmt, outExt) = when {
            s.imageFormat == "jpg" -> Fmt.JPEG to (if (isJpeg) ext else "jpg")
            s.imageFormat == "png" -> Fmt.PNG to "png"
            s.imageFormat == "webp" -> Fmt.WEBP to "webp"
            s.convertOnly -> throw CompressionError("Pick a photo format to convert to.")
            isJpeg -> Fmt.JPEG to ext
            ext == "webp" -> Fmt.WEBP to "webp"
            alpha -> Fmt.PNG to "png"
            else -> Fmt.JPEG to "jpg"
        }
        if (s.convertOnly && (outExt == ext || (isJpeg && fmt == Fmt.JPEG))) {
            throw CompressionError("It's already ${outExt.uppercase()}.")
        }
        if (outExt != ext && !(isJpeg && fmt == Fmt.JPEG)) notes += "Saved as ${outExt.uppercase()} (from ${ext.uppercase()})"
        if (fmt == Fmt.JPEG && alpha) {
            img = onWhite(img)
            notes += "Transparent areas filled with white"
        }
        job.check()
        val enc = Encoder(img, fmt)

        if (s.convertOnly) {
            job.progress(0.5f, "Converting")
            val data = enc.encode(when (fmt) { Fmt.JPEG -> 92; Fmt.WEBP -> 90; Fmt.PNG -> 100 }, 1.0)
            val out = ws.outputFile(src, outExt)
            out.writeBytes(data)
            copyExif(src.file, out, fmt)
            return Result(true, out, orig, data.size.toLong(), orig, "", notes)
        }

        var data: ByteArray? = null
        var scale = 1.0
        if (fmt == Fmt.PNG) {
            val r = compressPng(enc, ws, target, job, notes)
            data = r
        } else {
            for (step in 0 until 8) {
                job.check()
                job.progress(0.15f + step * 0.1f, "Compressing")
                val found = searchQuality(enc, target, scale, QUALITY_FLOOR, 92, job)
                if (found != null) { data = found; break }
                val probe = enc.encode(QUALITY_FLOOR + 10, scale).size
                scale *= max(0.35, min(0.92, sqrt(target.toDouble() / probe) * 0.97))
                if (min(img.width, img.height) * scale < 48) break
            }
            if (data == null) {
                data = searchQuality(enc, target, scale, QUALITY_MIN, QUALITY_FLOOR, job) ?: enc.encode(QUALITY_MIN, scale)
                notes += "Target is very small, quality is reduced a lot"
            }
            if (scale < 0.9999) {
                val b = enc.at(scale)
                notes += "Resized ${img.width}×${img.height} → ${b.width}×${b.height}"
            }
        }
        job.progress(0.95f, "Saving")
        val bytes = data!!
        if (bytes.size >= orig) {
            return Result(false, null, orig, 0, target, "Already well compressed — couldn't make it smaller.", notes)
        }
        val out = ws.outputFile(src, outExt)
        out.writeBytes(bytes)
        copyExif(src.file, out, fmt)
        if (bytes.size > target * 1.06) notes += "Couldn't fully reach the target."
        return Result(true, out, orig, out.length(), target, "", notes)
    }

    /** Lossless first, then 256 colours (FFmpeg palette), then smaller. */
    private fun compressPng(enc: Encoder, ws: Workspace, target: Long, job: Job, notes: MutableList<String>): ByteArray {
        var data = enc.encode(100, 1.0)
        if (data.size <= target) return data
        val pal = palette(data, ws, job)
        if (pal != null && pal.size <= target) {
            notes += "Reduced to 256 colors"
            return pal
        }
        var last = pal ?: data
        var scale = 1.0
        for (i in 0 until 8) {
            job.check()
            job.progress(0.2f + i * 0.09f, "Compressing")
            scale *= max(0.35, min(0.92, sqrt(target.toDouble() / last.size) * 0.97))
            val png = enc.encode(100, scale)
            last = palette(png, ws, job) ?: png
            if (last.size <= target) break
        }
        val b = enc.at(scale)
        notes += if (Ffmpeg.available) "Reduced to 256 colors, resized to ${b.width}×${b.height}" else "Resized to ${b.width}×${b.height}"
        data = last
        return data
    }

    private fun palette(png: ByteArray, ws: Workspace, job: Job): ByteArray? {
        if (!Ffmpeg.available) return null
        val inF = ws.tempFile("pal_in", "png")
        val outF = ws.tempFile("pal_out", "png")
        try {
            inF.writeBytes(png)
            val ok = Ffmpeg.runSimple(listOf("-i", inF.absolutePath, "-vf",
                "split[a][b];[a]palettegen=max_colors=256:reserve_transparent=1:stats_mode=full[p];[b][p]paletteuse=dither=floyd_steinberg:alpha_threshold=128",
                "-frames:v", "1", outF.absolutePath), job)
            return if (ok && outF.length() > 0) outF.readBytes() else null
        } finally {
            inF.delete(); outF.delete()
        }
    }

    /** Keeps date, camera and GPS details; orientation is already applied to the pixels. */
    fun copyExif(from: File, to: File, fmt: Fmt) {
        if (fmt == Fmt.PNG) return
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (f in ExifInterface::class.java.fields) {
                if (!f.name.startsWith("TAG_") || f.type != String::class.java) continue
                val tag = f.get(null) as? String ?: continue
                if (tag == ExifInterface.TAG_ORIENTATION || tag.startsWith("Thumbnail") ||
                    tag == ExifInterface.TAG_IMAGE_WIDTH || tag == ExifInterface.TAG_IMAGE_LENGTH ||
                    tag == ExifInterface.TAG_PIXEL_X_DIMENSION || tag == ExifInterface.TAG_PIXEL_Y_DIMENSION) continue
                src.getAttribute(tag)?.let { runCatching { dst.setAttribute(tag, it) } }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        } catch (_: Exception) {
        }
    }
}
