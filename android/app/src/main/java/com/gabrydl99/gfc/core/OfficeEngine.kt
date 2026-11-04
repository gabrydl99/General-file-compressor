package com.gabrydl99.gfc.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max

/** Word / PowerPoint / Excel (.docx .pptx .xlsx): shrink the pictures inside (port of office.py). */
object OfficeEngine {
    // (jpeg quality, longest side in pixels) from gentle to aggressive
    private val LEVELS = listOf(92 to 4096, 90 to 3200, 87 to 2560, 83 to 2560, 80 to 2048, 75 to 1920, 70 to 1600,
        65 to 1400, 60 to 1200, 55 to 1024, 50 to 900, 45 to 800, 40 to 640, 32 to 512, 25 to 400)
    private val PICTURE_EXT = setOf("jpg", "jpeg", "png", "bmp")
    private val OTHER_MEDIA_EXT = setOf("mp4", "mov", "wmv", "avi", "m4v", "mp3", "wav", "m4a", "wma")
    private val STORED_EXT = setOf("jpg", "jpeg", "png", "gif") + OTHER_MEDIA_EXT
    private val REL_TAG = Regex("""<Relationship\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val TARGET = Regex("""(\bTarget=")([^"]*)(")""")

    private class Entry(val name: String, val time: Long, val data: ByteArray)

    private fun isMedia(name: String) = "/media/" in name || name.startsWith("media/")

    private class Picture(val name: String, val data: ByteArray) {
        val ext = extOf(name)
        var alpha = false
        val img: Bitmap? = try {
            BitmapFactory.decodeByteArray(data, 0, data.size)?.also { alpha = ImageEngine.hasAlpha(it) }
        } catch (e: Throwable) {
            null
        }
        private val cache = HashMap<Int, Pair<String, ByteArray>>()

        /** Returns (format, bytes); format is "keep", "jpeg", "same-jpeg" or "png". */
        fun variant(level: Int): Pair<String, ByteArray> {
            val im = img ?: return "keep" to data
            cache[level]?.let { return it }
            val (q, maxSide) = LEVELS[level]
            val longest = max(im.width, im.height)
            val sized = if (longest > maxSide) ImageEngine.scaled(im, maxSide.toDouble() / longest) else im
            val options = mutableListOf<Pair<String, ByteArray>>()
            if (!alpha) options += "jpeg" to ImageEngine.encode(sized, ImageEngine.Fmt.JPEG, q)
            if (ext == "png") options += "png" to ImageEngine.encode(sized, ImageEngine.Fmt.PNG, 100)
            if (sized !== im) sized.recycle()
            var best = options.minByOrNull { it.second.size } ?: ("keep" to data)
            if (best.second.size >= data.size) best = "keep" to data
            if (best.first == "jpeg" && ext in listOf("jpg", "jpeg")) best = "same-jpeg" to best.second
            cache[level] = best
            return best
        }
    }

    private fun newName(name: String, existing: Set<String>): String {
        val stem = name.substringBeforeLast('.')
        var cand = "$stem.jpeg"
        var i = 2
        while (cand in existing) { cand = "${stem}_$i.jpeg"; i++ }
        return cand
    }

    private fun normPath(p: String): String {
        val out = ArrayList<String>()
        for (part in p.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        return out.joinToString("/")
    }

    private fun dirName(p: String) = if ('/' in p) p.substringBeforeLast('/') else ""
    private fun baseName(p: String) = p.substringAfterLast('/')

    private fun fixRels(xml: String, relsName: String, renames: Map<String, String>): String {
        // word/_rels/document.xml.rels -> base folder "word"
        val base = dirName(dirName(relsName))
        return REL_TAG.replace(xml) { m ->
            val tag = m.value
            if ("TargetMode=\"External\"" in tag) return@replace tag
            val tm = TARGET.find(tag) ?: return@replace tag
            val target = tm.groupValues[2]
            val resolved = normPath(if (target.startsWith("/")) target.trimStart('/') else (if (base.isEmpty()) target else "$base/$target"))
            val newFull = renames[resolved] ?: return@replace tag
            val newBase = baseName(newFull)
            val newTarget = if ('/' in target) "${dirName(target)}/$newBase" else newBase
            val g = tm.groups[2]!!.range
            tag.substring(0, g.first) + newTarget + tag.substring(g.last + 1)
        }
    }

    private fun fixContentTypes(xml0: String, renames: Map<String, String>): String {
        var xml = xml0
        if (renames.isNotEmpty() && !Regex("""<Default\b[^>]*Extension="jpeg"""", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            xml = xml.replaceFirst("</Types>", "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/></Types>")
        }
        for ((old, new) in renames) {
            val re = Regex("""(<Override\b[^>]*PartName=")/""" + Regex.escape(old) + """("[^>]*ContentType=")[^"]*(")""")
            xml = re.replace(xml) { m -> m.groupValues[1] + "/" + new + m.groupValues[2] + "image/jpeg" + m.groupValues[3] }
        }
        return xml
    }

    private fun build(entries: List<Entry>, pics: Map<String, Picture>, level: Int?, job: Job): ByteArray {
        val names = entries.map { it.name }.toMutableSet()
        val replaced = HashMap<String, ByteArray>()
        val renames = LinkedHashMap<String, String>()
        if (level != null) {
            for ((name, pic) in pics) {
                job.check()
                val (fmt, data) = pic.variant(level)
                if (fmt == "keep") continue
                if (fmt == "jpeg") {
                    val nn = newName(name, names)
                    names += nn
                    renames[name] = nn
                }
                replaced[name] = data
            }
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            for (e in entries) {
                var data = e.data
                if (e.name.endsWith(".rels") && renames.isNotEmpty()) {
                    data = fixRels(String(data, Charsets.UTF_8), e.name, renames).toByteArray(Charsets.UTF_8)
                } else if (e.name == "[Content_Types].xml" && renames.isNotEmpty()) {
                    data = fixContentTypes(String(data, Charsets.UTF_8), renames).toByteArray(Charsets.UTF_8)
                }
                replaced[e.name]?.let { data = it }
                val name = renames[e.name] ?: e.name
                val ze = ZipEntry(name)
                if (e.time > 0) ze.time = e.time
                if (extOf(name) in STORED_EXT) {
                    ze.method = ZipEntry.STORED
                    ze.size = data.size.toLong()
                    ze.compressedSize = data.size.toLong()
                    ze.crc = CRC32().apply { update(data) }.value
                } else {
                    ze.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(ze)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun compress(src: Source, ws: Workspace, job: Job): Result {
        val s = ws.settings
        val orig = src.size
        val target = s.targetBytes(orig)
        val notes = mutableListOf<String>()
        job.progress(0.05f, "Reading")
        val entries = try {
            ZipFile(src.file).use { zf ->
                zf.entries().asSequence().filter { !it.isDirectory }.map { ze ->
                    Entry(ze.name, ze.time, zf.getInputStream(ze).use { it.readBytes() })
                }.toList()
            }
        } catch (e: ZipException) {
            throw CompressionError("This file isn't a valid Office document (or it's password-protected).")
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        }
        if (entries.none { it.name == "[Content_Types].xml" }) throw CompressionError("This file isn't a valid Office document.")

        val pics = LinkedHashMap<String, Picture>()
        var otherMedia = 0L
        for (e in entries) {
            val ext = extOf(e.name)
            if (isMedia(e.name) && ext in PICTURE_EXT) pics[e.name] = Picture(e.name, e.data)
            else if (isMedia(e.name) && ext in OTHER_MEDIA_EXT) otherMedia += e.data.size
        }
        job.check()
        var best = build(entries, pics, null, job)
        if (best.size > target && pics.isNotEmpty()) {
            var lo = 0
            var hi = LEVELS.size - 1
            var fit: Int? = null
            val results = HashMap<Int, ByteArray>()
            var tries = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                tries++
                job.progress(minOf(0.9f, 0.1f + tries * 0.18f), "Compressing pictures")
                results[mid] = build(entries, pics, mid, job)
                if (results[mid]!!.size <= target) { fit = mid; hi = mid - 1 } else lo = mid + 1
            }
            if (fit != null) best = results[fit]!!
            else {
                val strongest = results[LEVELS.size - 1] ?: build(entries, pics, LEVELS.size - 1, job)
                if (strongest.size < best.size) best = strongest
                notes += "Couldn't fully reach the target without ruining the pictures."
            }
        } else if (best.size > target) {
            notes += "There are no pictures inside to compress."
        }
        if (otherMedia > orig * 0.2) notes += "Videos/audio embedded in the file were left as they are."
        pics.values.forEach { it.img?.recycle() }
        job.progress(0.95f, "Saving")
        if (best.size >= orig) {
            return Result(false, null, orig, 0, target, "Already well compressed — couldn't make it smaller.", notes)
        }
        val out = ws.outputFile(src)
        out.writeBytes(best)
        return Result(true, out, orig, best.size.toLong(), target, "", notes)
    }
}
