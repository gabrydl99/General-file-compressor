package com.gabrydl99.gfc.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** PDF: clean-up first, then re-encode the pictures inside (port of pdf.py). */
object PdfEngine {
    // (dpi, jpeg quality) from gentle to aggressive
    private val LADDER = listOf(600 to 92, 450 to 90, 300 to 86, 300 to 80, 220 to 78, 180 to 75, 150 to 70, 150 to 60,
        120 to 55, 110 to 50, 96 to 45, 85 to 40, 72 to 35, 72 to 28, 60 to 22, 50 to 16)
    private const val MEMORY = 64L * 1024 * 1024

    private fun open(file: File): PDDocument {
        val doc = try {
            PDDocument.load(file, MemoryUsageSetting.setupMixed(MEMORY))
        } catch (e: InvalidPasswordException) {
            throw CompressionError("This PDF is password-protected.")
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        } catch (e: Exception) {
            throw CompressionError("Couldn't open this PDF (${e.javaClass.simpleName}).")
        }
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    /** Finds how large each picture is drawn, to know its real resolution (DPI). */
    private class DpiFinder : PDFStreamEngine() {
        val dpi = IdentityHashMap<COSStream, Double>()

        init {
            addOperator(Concatenate())
            addOperator(DrawObject())
            addOperator(SetGraphicsStateParameters())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetMatrix())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName
                val xo = name?.let { n -> runCatching { resources?.getXObject(n) }.getOrNull() }
                if (xo is PDImageXObject) {
                    val ctm = graphicsState.currentTransformationMatrix
                    val wPt = abs(ctm.scalingFactorX.toDouble())
                    val hPt = abs(ctm.scalingFactorY.toDouble())
                    if (wPt > 0.5 && hPt > 0.5) {
                        val d = max(xo.width / (wPt / 72.0), xo.height / (hPt / 72.0))
                        val prev = dpi[xo.cosObject] ?: 0.0
                        if (d > prev) dpi[xo.cosObject] = d
                    }
                    return
                }
            }
            super.processOperator(operator, operands)
        }
    }

    private class ImageRef(val res: PDResources, val name: COSName, val img: PDImageXObject)

    private fun collect(res: PDResources?, out: MutableList<ImageRef>, seenForms: MutableSet<COSStream>, depth: Int = 0) {
        if (res == null || depth > 8) return
        for (name in res.xObjectNames) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (xo) {
                is PDImageXObject -> out += ImageRef(res, name, xo)
                is PDFormXObject -> if (seenForms.add(xo.cosObject)) collect(xo.resources, out, seenForms, depth + 1)
            }
        }
    }

    private fun skippable(img: PDImageXObject): Boolean {
        if (img.isStencil || img.bitsPerComponent == 1) return true
        if (img.width < 32 || img.height < 32) return true
        val filter = img.cosObject.getDictionaryObject(COSName.FILTER)?.toString() ?: ""
        if ("JBIG2" in filter || "JPX" in filter || "CCITT" in filter) return true
        val cs = runCatching { img.colorSpace?.numberOfComponents }.getOrNull() ?: 3
        if (cs == 4) return true   // CMYK pictures don't decode reliably here
        return img.cosObject.length < 8 * 1024
    }

    /** Re-encodes the pictures for one ladder level; null level = only clean up. Returns the PDF size. */
    private fun render(src: File, level: Int?, out: File, job: Job): Long {
        val doc = open(src)
        try {
            if (level != null) {
                val (targetDpi, q) = LADDER[level]
                val finder = DpiFinder()
                for (page in doc.pages) {
                    job.check()
                    runCatching { finder.processPage(page) }
                }
                val refs = mutableListOf<ImageRef>()
                val forms = HashSet<COSStream>()
                for (page in doc.pages) collect(page.resources, refs, forms)
                val done = IdentityHashMap<COSStream, PDImageXObject?>()
                val firstPageWidth = doc.pages.firstOrNull()?.mediaBox?.width?.toDouble() ?: 612.0
                for (ref in refs) {
                    job.check()
                    val key = ref.img.cosObject
                    if (!done.containsKey(key)) done[key] = reencode(doc, ref.img, finder.dpi[key]
                        ?: (ref.img.width / (firstPageWidth / 72.0)), targetDpi, q)
                    done[key]?.let { ref.res.put(ref.name, it) }
                }
            }
            doc.save(out)
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        } finally {
            doc.close()
        }
        return out.length()
    }

    private fun reencode(doc: PDDocument, img: PDImageXObject, dpi: Double, targetDpi: Int, q: Int): PDImageXObject? {
        if (skippable(img)) return null
        val bmp = try {
            img.image ?: return null
        } catch (e: Throwable) {
            return null
        }
        try {
            val scale = if (dpi > targetDpi + 10) targetDpi / dpi else 1.0
            val sized = if (scale < 0.98) ImageEngine.scaled(bmp, scale) else bmp
            val flat = if (sized.hasAlpha()) ImageEngine.onWhite(sized) else sized
            val made = JPEGFactory.createFromImage(doc, flat, q / 100f)
            img.cosObject.getItem(COSName.SMASK)?.let { made.cosObject.setItem(COSName.SMASK, it) }
            img.cosObject.getItem(COSName.DECODE)?.let { if (img.colorSpace?.numberOfComponents == 1) made.cosObject.setItem(COSName.DECODE, it) }
            if (flat !== sized) flat.recycle()
            if (sized !== bmp) sized.recycle()
            return if (made.cosObject.length < img.cosObject.length) made else null
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            bmp.recycle()
        }
    }

    /** "Rebuild pages as pictures": every page becomes one JPEG (text no longer selectable). */
    private fun rasterize(src: File, level: Int, out: File, job: Job): Long {
        val (dpi, q) = LADDER[level]
        val pfd = try {
            ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw CompressionError("Couldn't open this PDF (${e.javaClass.simpleName}).")
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close(); throw CompressionError("This PDF is password-protected.")
        } catch (e: Exception) {
            pfd.close(); throw CompressionError("Couldn't open this PDF (${e.javaClass.simpleName}).")
        }
        val doc = PDDocument(MemoryUsageSetting.setupMixed(MEMORY))
        try {
            for (i in 0 until renderer.pageCount) {
                job.check()
                renderer.openPage(i).use { page ->
                    val wPt = page.width.toFloat()
                    val hPt = page.height.toFloat()
                    val w = max(1, (wPt / 72f * dpi).roundToInt())
                    val h = max(1, (hPt / 72f * dpi).roundToInt())
                    val px = w.toLong() * h
                    val f = if (px > 40_000_000L) Math.sqrt(40_000_000.0 / px) else 1.0
                    val bmp = Bitmap.createBitmap(max(1, (w * f).toInt()), max(1, (h * f).toInt()), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val pdPage = PDPage(PDRectangle(wPt, hPt))
                    doc.addPage(pdPage)
                    val pic = JPEGFactory.createFromImage(doc, bmp, q / 100f)
                    PDPageContentStream(doc, pdPage).use { cs -> cs.drawImage(pic, 0f, 0f, wPt, hPt) }
                    bmp.recycle()
                }
            }
            doc.save(out)
        } catch (e: OutOfMemoryError) {
            throw CompressionError("Not enough memory for this file.")
        } finally {
            doc.close(); renderer.close(); pfd.close()
        }
        return out.length()
    }

    fun compress(src: Source, ws: Workspace, job: Job): Result {
        val s = ws.settings
        val orig = src.size
        val target = s.targetBytes(orig)
        val notes = mutableListOf<String>()
        val tmp = ArrayList<File>()
        fun attempt(level: Int?): File {
            val f = ws.tempFile("pdf", "pdf")
            tmp += f
            if (s.pdfAsPictures && level != null) rasterize(src.file, level, f, job) else render(src.file, level, f, job)
            job.check()
            return f
        }
        try {
            job.progress(0.05f, "Cleaning up")
            var best = if (s.pdfAsPictures) null else attempt(null)
            if (best == null || best.length() > target) {
                var lo = 0
                var hi = LADDER.size - 1
                var fit: File? = null
                val results = HashMap<Int, File>()
                var tries = 0
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    tries++
                    job.progress(minOf(0.9f, 0.1f + tries * 0.18f), if (s.pdfAsPictures) "Rebuilding pages" else "Compressing pictures")
                    val r = attempt(mid)
                    results[mid] = r
                    if (r.length() <= target) { fit = r; hi = mid - 1 } else lo = mid + 1
                }
                if (fit != null) best = fit
                else {
                    val strongest = results[LADDER.size - 1] ?: attempt(LADDER.size - 1)
                    if (best == null || strongest.length() < best.length()) best = strongest
                    notes += if (s.pdfAsPictures) "Couldn't fully reach the target." else
                        "Couldn't fully reach the target: this PDF is mostly text or drawings."
                }
            }
            if (s.pdfAsPictures) notes += "Pages rebuilt as pictures (text can't be selected anymore)"
            job.progress(0.95f, "Saving")
            val b = best!!
            if (b.length() >= orig) {
                return Result(false, null, orig, 0, target, "Already well compressed — couldn't make it smaller.", notes)
            }
            val out = ws.outputFile(src)
            b.copyTo(out, overwrite = true)
            return Result(true, out, orig, out.length(), target, "", notes)
        } finally {
            tmp.forEach { it.delete() }
        }
    }
}
