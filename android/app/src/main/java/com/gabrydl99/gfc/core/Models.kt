package com.gabrydl99.gfc.core

import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val MB = 1024L * 1024L

enum class Kind { VIDEO, AUDIO, IMAGE, PDF, OFFICE }

val VIDEO_EXT = setOf("mp4", "mov", "m4v", "mkv", "avi", "wmv", "flv", "webm", "mts", "m2ts", "ts",
    "3gp", "3g2", "mpg", "mpeg", "vob", "ogv")
val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "aiff", "aif",
    "amr", "ac3", "mka", "m4b")
val IMAGE_EXT = setOf("jpg", "jpeg", "jfif", "png", "webp", "bmp", "heic", "heif", "gif", "tif", "tiff")
val PDF_EXT = setOf("pdf")
val OFFICE_EXT = setOf("docx", "docm", "pptx", "pptm", "xlsx", "xlsm")

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
fun stemOf(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name

fun kindOf(name: String): Kind? = when (extOf(name)) {
    in VIDEO_EXT -> Kind.VIDEO
    in AUDIO_EXT -> Kind.AUDIO
    in IMAGE_EXT -> Kind.IMAGE
    in PDF_EXT -> Kind.PDF
    in OFFICE_EXT -> Kind.OFFICE
    else -> null
}

enum class Mode { PERCENT, MB, CONVERT }

/** Same choices as the Windows app, plus a speed setting for phones. */
data class Settings(
    val mode: Mode = Mode.PERCENT,
    val percent: Int = 30,
    val targetMb: Double = 10.0,
    val resolution: String = "auto",   // auto | original | 2160 | 1440 | 1080 | 720 | 480 | 360
    val fps: String = "auto",          // auto | original | 60 | 30 | 25 | 24 | 15
    val speed: String = "balanced",    // fast | balanced | best
    val videoFormat: String = "keep",  // keep | mp4 | mkv | mov | webm | mp3 | m4a
    val imageFormat: String = "keep",  // keep | jpg | png | webp
    val audioFormat: String = "keep",  // keep | mp3 | m4a | ogg | opus
    val pdfAsPictures: Boolean = false,
    val outputDir: String? = null,     // null = Download/General File Compressor
    val moveOriginals: Boolean = true,
    val preset: String? = null,
) {
    val convertOnly get() = mode == Mode.CONVERT

    fun targetBytes(original: Long): Long = when (mode) {
        Mode.MB -> max(1L, (targetMb * MB).toLong())
        Mode.CONVERT -> original
        Mode.PERCENT -> max(1L, (original * percent / 100.0).toLong())
    }

    fun toJson(): String = JSONObject().apply {
        put("mode", mode.name); put("percent", percent); put("targetMb", targetMb)
        put("resolution", resolution); put("fps", fps); put("speed", speed)
        put("videoFormat", videoFormat); put("imageFormat", imageFormat); put("audioFormat", audioFormat)
        put("pdfAsPictures", pdfAsPictures); put("outputDir", outputDir ?: JSONObject.NULL)
        put("moveOriginals", moveOriginals); put("preset", preset ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(s: String?): Settings {
            if (s.isNullOrBlank()) return Settings()
            return try {
                val o = JSONObject(s)
                val d = Settings()
                Settings(
                    mode = runCatching { Mode.valueOf(o.optString("mode", d.mode.name)) }.getOrDefault(d.mode),
                    percent = o.optInt("percent", d.percent).coerceIn(1, 99),
                    targetMb = o.optDouble("targetMb", d.targetMb).let { if (it.isNaN() || it <= 0) d.targetMb else it },
                    resolution = o.optString("resolution", d.resolution),
                    fps = o.optString("fps", d.fps),
                    speed = o.optString("speed", d.speed),
                    videoFormat = o.optString("videoFormat", d.videoFormat),
                    imageFormat = o.optString("imageFormat", d.imageFormat),
                    audioFormat = o.optString("audioFormat", d.audioFormat),
                    pdfAsPictures = o.optBoolean("pdfAsPictures", d.pdfAsPictures),
                    outputDir = if (o.isNull("outputDir")) null else o.optString("outputDir").ifBlank { null },
                    moveOriginals = o.optBoolean("moveOriginals", d.moveOriginals),
                    preset = if (o.isNull("preset")) null else o.optString("preset").ifBlank { null },
                )
            } catch (e: Exception) {
                Settings()
            }
        }
    }
}

/** Ready-made settings for where the files are going. */
enum class Preset(val label: String, val description: String) {
    WHATSAPP("WhatsApp", "About 15 MB per file, MP4 videos and JPG photos: fits WhatsApp's 16 MB limit."),
    TELEGRAM("Telegram", "MP4 at up to 1080p, about 40% of the original size."),
    INSTAGRAM("Instagram", "1080p MP4 at 30 fps and JPG photos, about half the size."),
    EMAIL("Email", "About 18 MB per file, so it fits a 25 MB email attachment.");

    fun apply(s: Settings): Settings = when (this) {
        WHATSAPP -> s.copy(mode = Mode.MB, targetMb = 15.0, videoFormat = "mp4", imageFormat = "jpg",
            resolution = "auto", fps = "auto", preset = name)
        TELEGRAM -> s.copy(mode = Mode.PERCENT, percent = 40, videoFormat = "mp4", imageFormat = "keep",
            resolution = "1080", fps = "auto", preset = name)
        INSTAGRAM -> s.copy(mode = Mode.PERCENT, percent = 50, videoFormat = "mp4", imageFormat = "jpg",
            resolution = "1080", fps = "30", preset = name)
        EMAIL -> s.copy(mode = Mode.MB, targetMb = 18.0, videoFormat = "mp4", imageFormat = "jpg",
            resolution = "auto", fps = "auto", preset = name)
    }
}

data class Result(
    val ok: Boolean,
    val output: File? = null,
    val originalSize: Long = 0,
    val newSize: Long = 0,
    val targetSize: Long = 0,
    val message: String = "",
    val notes: List<String> = emptyList(),
    val originalMovedTo: String? = null,
) {
    val savedRatio: Double
        get() = if (originalSize <= 0 || newSize <= 0) 0.0 else 1.0 - newSize.toDouble() / originalSize
}

class CancelledException : Exception("Stopped")

/** A readable error to show to the user. */
class CompressionError(message: String) : Exception(message)

/** Carries progress reporting and cancellation into an engine. */
class Job(private val onProgress: (Float, String) -> Unit) {
    @Volatile var cancelled = false
        private set
    private val processes = mutableListOf<Process>()

    fun progress(fraction: Float, text: String = "") = onProgress(fraction.coerceIn(0f, 1f), text)

    fun check() {
        if (cancelled) throw CancelledException()
    }

    @Synchronized fun register(p: Process) {
        processes += p
        if (cancelled) p.destroy()
    }

    @Synchronized fun unregister(p: Process) {
        processes -= p
    }

    @Synchronized fun cancel() {
        cancelled = true
        processes.forEach { runCatching { it.destroy() } }
    }

    @Synchronized fun killAll() {
        processes.forEach { runCatching { it.destroyForcibly() } }
    }
}

fun humanSize(bytes: Double): String {
    var n = bytes
    for (unit in listOf("B", "KB", "MB", "GB", "TB")) {
        if (abs(n) < 1024 || unit == "TB") {
            return when {
                unit == "B" || unit == "KB" -> String.format(Locale.US, "%.0f %s", n, unit)
                n < 100 -> String.format(Locale.US, "%.1f %s", n, unit)
                else -> String.format(Locale.US, "%.0f %s", n, unit)
            }
        }
        n /= 1024
    }
    return String.format(Locale.US, "%.1f TB", n)
}

fun humanSize(bytes: Long) = humanSize(bytes.toDouble())

fun clampF(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
