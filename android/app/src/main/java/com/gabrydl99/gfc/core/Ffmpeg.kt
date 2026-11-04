package com.gabrydl99.gfc.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class MediaInfo(
    var duration: Double = 0.0,
    var bitrateKbps: Double = 0.0,
    var hasVideo: Boolean = false,
    var width: Int = 0,
    var height: Int = 0,
    var fps: Double = 0.0,
    var rotation: Int = 0,
    var videoCodec: String = "",
    var hasAudio: Boolean = false,
    var audioCodec: String = "",
    var audioRate: Int = 0,
    var audioChannels: Int = 0,
    var audioKbps: Double = 0.0,
) {
    /** Width and height as the video is shown (phone videos are often stored rotated). */
    val displaySize: Pair<Int, Int>
        get() = if (Math.abs(rotation) % 180 == 90) height to width else width to height
}

/** Runs the FFmpeg program that ships inside the app. */
object Ffmpeg {
    private const val STALL_MS = 240_000L
    private var exe: File? = null
    private var tmp: File? = null

    @Volatile var encoders: Set<String> = emptySet()
        private set

    /** Same as [encoders], for the screen to react when they're known. */
    val encodersFlow = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) {
        val f = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        exe = if (f.exists()) f else null
        tmp = File(context.cacheDir, "ffmpeg").apply { mkdirs() }
    }

    val available: Boolean get() = exe != null

    private fun exePath(): String = exe?.absolutePath
        ?: throw CompressionError("The video engine (FFmpeg) is missing from this build, so videos and audio can't be processed.")

    /** Reads which encoders this FFmpeg has (x264, VP9, MP3, Opus, Vorbis…). Call off the main thread. */
    fun loadEncoders() {
        if (!available) return
        try {
            val p = start(listOf(exePath(), "-hide_banner", "-encoders"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            encoders = Regex("""^\s*[VAS][.FSXBD]{5}\s+(\S+)""", RegexOption.MULTILINE)
                .findAll(out).map { it.groupValues[1] }.toSet()
        } catch (e: Exception) {
            encoders = emptySet()
        }
        encodersFlow.value = encoders
    }

    fun has(encoder: String) = encoder in encoders

    private fun start(cmd: List<String>): Process =
        ProcessBuilder(cmd).directory(tmp).redirectErrorStream(true).also {
            it.environment()["TMPDIR"] = tmp?.absolutePath ?: "/data/local/tmp"
        }.start()

    fun probe(path: String, job: Job? = null): MediaInfo {
        val pb = ProcessBuilder(exePath(), "-hide_banner", "-nostdin", "-i", path).directory(tmp)
        pb.redirectErrorStream(true)
        val p = pb.start()
        job?.register(p)
        val err: String
        try {
            err = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw CompressionError("Reading the file took too long.")
            }
        } finally {
            job?.unregister(p)
        }
        job?.check()
        return parseProbe(err)
    }

    private val streamRe = Regex("""^\s*Stream #\d+:\d+\S*:\s*(Video|Audio):\s*(.*)$""", RegexOption.MULTILINE)

    fun parseProbe(err: String): MediaInfo {
        val info = MediaInfo()
        Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(err)?.let {
            info.duration = it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 +
                it.groupValues[3].toDouble()
        }
        Regex("""Duration:.*?bitrate:\s*(\d+)\s*kb/s""").find(err)?.let {
            info.bitrateKbps = it.groupValues[1].toDouble()
        }
        for (m in streamRe.findAll(err)) {
            val kind = m.groupValues[1]
            val rest = m.groupValues[2]
            if (kind == "Video" && !info.hasVideo && "attached pic" !in rest) {
                info.hasVideo = true
                info.videoCodec = rest.trim().split(" ").first().trim(',')
                Regex("""(?<![0-9a-fA-Fx])(\d{2,5})x(\d{2,5})(?![0-9])""").find(rest)?.let {
                    info.width = it.groupValues[1].toInt(); info.height = it.groupValues[2].toInt()
                }
                (Regex("""(\d+(?:\.\d+)?)\s*fps""").find(rest) ?: Regex("""(\d+(?:\.\d+)?)\s*tbr""").find(rest))?.let {
                    info.fps = it.groupValues[1].toDouble()
                }
            } else if (kind == "Audio" && !info.hasAudio) {
                info.hasAudio = true
                info.audioCodec = rest.trim().split(" ").first().trim(',')
                Regex("""(\d+)\s*Hz""").find(rest)?.let { info.audioRate = it.groupValues[1].toInt() }
                info.audioChannels = when {
                    "mono" in rest -> 1
                    "stereo" in rest -> 2
                    else -> {
                        val c1 = Regex("""(\d+)\s*channels""").find(rest)
                        val c2 = Regex("""\b(\d)\.(\d)(?:\(|,|\s)""").find(rest)
                        when {
                            c1 != null -> c1.groupValues[1].toInt()
                            c2 != null -> c2.groupValues[1].toInt() + c2.groupValues[2].toInt()
                            else -> 2
                        }
                    }
                }
                Regex("""(\d+)\s*kb/s""").find(rest)?.let { info.audioKbps = it.groupValues[1].toDouble() }
            }
        }
        (Regex("""rotation of\s*(-?\d+(?:\.\d+)?)\s*degrees""").find(err)
            ?: Regex("""rotate\s*:\s*(-?\d+)""").find(err))?.let {
            info.rotation = ((it.groupValues[1].toDouble().roundToInt() % 360) + 360) % 360
        }
        if (!info.hasVideo && !info.hasAudio) throw CompressionError("This file has no readable video or audio.")
        if (info.fps <= 0 || info.fps > 240) info.fps = 30.0
        return info
    }

    /**
     * Runs FFmpeg with [args], turning its -progress output into progress between [lo] and [hi].
     */
    fun run(args: List<String>, job: Job, duration: Double, lo: Float, hi: Float, label: String) {
        val cmd = listOf(exePath(), "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
            "-progress", "pipe:1", "-nostats") + args
        val pb = ProcessBuilder(cmd).directory(tmp)
        pb.environment()["TMPDIR"] = tmp?.absolutePath ?: "/data/local/tmp"
        val p = pb.start()
        job.register(p)
        val errors = ArrayDeque<String>()
        val errThread = thread(isDaemon = true) {
            try {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errors) {
                        errors.addLast(line)
                        while (errors.size > 12) errors.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }
        val last = AtomicLong(System.currentTimeMillis())
        val stalled = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true) {
            try {
                while (p.isAlive) {
                    if (System.currentTimeMillis() - last.get() > STALL_MS) {
                        stalled.set(true)
                        p.destroyForcibly()
                        return@thread
                    }
                    Thread.sleep(2000)
                }
            } catch (_: InterruptedException) {
            }
        }
        job.progress(lo, label)
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                last.set(System.currentTimeMillis())
                if (job.cancelled) {
                    p.destroy()
                    return@forEachLine
                }
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val key = line.substring(0, eq).trim()
                    if ((key == "out_time_us" || key == "out_time_ms") && duration > 0) {
                        val v = line.substring(eq + 1).trim().toLongOrNull() ?: return@forEachLine
                        val secs = v / 1_000_000.0
                        job.progress(lo + (hi - lo) * (secs / duration).coerceIn(0.0, 1.0).toFloat(), label)
                    }
                }
            }
        } catch (_: Exception) {
        }
        p.waitFor()
        errThread.join(5000)
        watchdog.interrupt()
        job.unregister(p)
        if (job.cancelled) throw CancelledException()
        if (stalled.get()) throw CompressionError("FFmpeg stopped responding while working on this file.")
        if (p.exitValue() != 0) {
            val lines = synchronized(errors) { errors.map { it.trim() }.filter { it.isNotEmpty() } }
            val key = lines.firstOrNull { Regex("(?i)error|failed|invalid|unsupported|not found").containsMatchIn(it) }
            val msg = ((key?.let { listOf(it) } ?: emptyList()) + lines.takeLast(3)).distinct().joinToString(" ").take(400)
            if ("No space left" in msg) throw CompressionError("The phone storage is full.")
            if ("Permission denied" in msg) throw CompressionError("Android didn't allow writing in the output folder. $msg".trim())
            throw CompressionError("FFmpeg could not process the file. $msg".trim())
        }
        job.progress(hi, label)
    }

    /** Runs FFmpeg for a quick job without progress (used for photo palettes). */
    fun runSimple(args: List<String>, job: Job?, timeoutSec: Long = 300): Boolean {
        val p = ProcessBuilder(listOf(exePath(), "-hide_banner", "-nostdin", "-y", "-loglevel", "error") + args)
            .directory(tmp).redirectErrorStream(true).start()
        job?.register(p)
        try {
            val drain = thread(isDaemon = true) { runCatching { p.inputStream.readBytes() } }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return false
            }
            drain.join(2000)
            return p.exitValue() == 0
        } finally {
            job?.unregister(p)
        }
    }
}
