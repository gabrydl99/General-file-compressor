package com.gabrydl99.gfc.core

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Video and audio with FFmpeg: bitrate targeting with 2-pass H.264 (port of media.py). */
object MediaEngine {
    private val COPY_VIDEO: Map<String, Set<String>?> = mapOf(
        "mp4" to setOf("h264", "hevc", "mpeg4", "av1"), "mov" to setOf("h264", "hevc", "mpeg4", "prores"),
        "mkv" to null, "webm" to setOf("vp8", "vp9", "av1"))
    private val COPY_AUDIO: Map<String, Set<String>?> = mapOf(
        "mp4" to setOf("aac", "mp3", "ac3", "eac3", "alac"), "mov" to setOf("aac", "mp3", "ac3", "alac", "pcm_s16le"),
        "mkv" to null, "webm" to setOf("opus", "vorbis"))
    private const val BPP_MIN = 0.04
    private val RES_STEPS = listOf(2160, 1440, 1080, 720, 576, 480, 360, 240)
    private const val CONTAINER_MARGIN = 0.97

    private fun x264Preset(s: Settings) = when (s.speed) { "fast" -> "veryfast"; "best" -> "medium"; else -> "faster" }
    private fun vp9Speed(s: Settings) = when (s.speed) { "fast" -> "6"; "best" -> "4"; else -> "5" }

    private fun even(x: Double) = max(2, (x / 2.0).roundToInt() * 2)
    private fun g(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else String.format(Locale.US, "%.3f", x).trimEnd('0').trimEnd('.')

    private fun audioKbpsForVideo(totalKbps: Double, info: MediaInfo): Int {
        if (!info.hasAudio) return 0
        var a = 32
        for ((limit, k) in listOf(4000 to 160, 1500 to 128, 700 to 96, 300 to 64, 150 to 48)) {
            if (totalKbps >= limit) { a = k; break }
        }
        a = min(a, max(24, (totalKbps / 4).toInt()))
        if (info.audioKbps > 0) a = min(a, max(32, info.audioKbps.toInt()))
        return a
    }

    private fun videoFormat(src: Source, s: Settings): String {
        val f = s.videoFormat
        if (f in listOf("mp4", "mkv", "mov", "webm")) return if (f == "webm" && !Ffmpeg.has("libvpx-vp9")) "mp4" else f
        return if (src.ext in listOf("mp4", "mkv", "mov", "webm") && !(src.ext == "webm" && !Ffmpeg.has("libvpx-vp9"))) src.ext else "mp4"
    }

    /** Builds the -vf chain. With videoKbps=null (convert only) "auto" means "keep". */
    private fun filters(info: MediaInfo, s: Settings, videoKbps: Double?, notes: MutableList<String>): String {
        var (dispW, dispH) = info.displaySize
        if (dispW <= 0 || dispH <= 0) { dispW = 1280; dispH = 720 }
        val short = min(dispW, dispH)
        fun bpp(shortSide: Int, fps: Double): Double {
            val f = shortSide.toDouble() / short
            return (videoKbps ?: 0.0) * 1000 / max(1.0, dispW * f * dispH * f * fps)
        }
        var outFps = info.fps
        if (s.fps != "auto" && s.fps != "original") {
            val want = s.fps.toDouble()
            if (info.fps > want + 0.5) outFps = want
        } else if (s.fps == "auto" && videoKbps != null && info.fps > 31 && bpp(short, info.fps) < BPP_MIN) {
            outFps = 30.0
        }
        var newShort = short
        if (s.resolution == "auto" && videoKbps != null) {
            val cands = listOf(short) + RES_STEPS.filter { it < short }
            newShort = cands.last()
            for (c in cands) if (bpp(c, outFps) >= BPP_MIN) { newShort = c; break }
        } else if (s.resolution != "auto" && s.resolution != "original") {
            newShort = min(short, s.resolution.toInt())
        }
        val out = mutableListOf<String>()
        if (outFps != info.fps) {
            out += "fps=${g(outFps)}"
            notes += "Frame rate ${g(info.fps)} → ${g(outFps)} fps"
        }
        val f = newShort.toDouble() / short
        val outW = even(dispW * f)
        val outH = even(dispH * f)
        if (outW != dispW || outH != dispH) out += "scale=$outW:$outH:flags=lanczos"
        if (newShort < short) notes += "Resolution ${dispW}×$dispH → ${outW}×$outH"
        out += "format=yuv420p"
        return out.joinToString(",")
    }

    private fun containerArgs(fmt: String, info: MediaInfo, copyingVideo: Boolean): List<String> {
        val a = mutableListOf("-map_metadata", "0")
        if (fmt == "mp4" || fmt == "mov") {
            a += listOf("-movflags", "+faststart")
            if (!copyingVideo) a += listOf("-tag:v", "avc1")
            else if (info.videoCodec == "hevc") a += listOf("-tag:v", "hvc1")
        }
        return a
    }

    fun compressVideo(src: Source, ws: Workspace, job: Job): Result {
        val s = ws.settings
        val info = Ffmpeg.probe(src.file.absolutePath, job)
        if (s.videoFormat == "mp3" || s.videoFormat == "m4a" || !info.hasVideo) {
            val forced = if (s.videoFormat == "mp3" || s.videoFormat == "m4a") s.videoFormat else null
            return compressAudio(src, ws, job, info, forced)
        }
        if (info.duration <= 0.1) throw CompressionError("Couldn't read the length of this video.")
        val fmt = videoFormat(src, s)
        if (s.convertOnly) return convertVideo(src, ws, job, info, fmt)

        val orig = src.size
        val target = s.targetBytes(orig)
        val notes = mutableListOf<String>()
        val totalKbps = target * 8 / 1000.0 / info.duration * CONTAINER_MARGIN
        val webm = fmt == "webm"
        var audioCopy = false
        var audioKbps = audioKbpsForVideo(totalKbps, info)
        val copyOk = if (webm) setOf("opus", "vorbis") else setOf("aac")
        if (info.hasAudio && info.audioCodec in copyOk && info.audioKbps > 0 &&
            info.audioKbps <= audioKbps * 1.1 && info.audioChannels <= 2) {
            audioCopy = true
            audioKbps = info.audioKbps.toInt()
        }
        var videoKbps = totalKbps - audioKbps
        if (videoKbps < 40) {
            videoKbps = 40.0
            notes += "Target is very small for this length, so the file ends up a bit bigger."
        }
        val vf = filters(info, s, videoKbps, notes)
        if (src.ext != fmt) notes += "Saved as ${fmt.uppercase()}"

        val out = ws.outputFile(src, fmt)
        val passDir = File(ws.temp, "pass_${System.nanoTime()}").apply { mkdirs() }
        val passlog = File(passDir, "pass").absolutePath

        fun encode(vkbps: Double, firstPass: Boolean, lo: Float, hi: Float, label: String) {
            val codec = if (webm) listOf("-c:v", "libvpx-vp9", "-b:v", "${vkbps.toInt()}k", "-row-mt", "1",
                "-deadline", "good", "-cpu-used", vp9Speed(s))
            else listOf("-c:v", "libx264", "-preset", x264Preset(s), "-b:v", "${vkbps.toInt()}k")
            val base = listOf("-i", src.file.absolutePath, "-map", "0:v:0", "-vf", vf, "-fps_mode", "passthrough") +
                codec + listOf("-passlogfile", passlog)
            if (firstPass) {
                Ffmpeg.run(base + listOf("-pass", "1", "-an", "-f", "null", "/dev/null"), job, info.duration, lo, hi, label)
                return
            }
            val audio = mutableListOf<String>()
            if (info.hasAudio) {
                audio += listOf("-map", "0:a:0")
                if (audioCopy) audio += listOf("-c:a", "copy")
                else {
                    val ch = if (audioKbps <= 40) 1 else min(2, if (info.audioChannels > 0) info.audioChannels else 2)
                    val enc = if (webm) (if (Ffmpeg.has("libopus")) "libopus" else "libvorbis") else "aac"
                    audio += listOf("-c:a", enc, "-b:a", "${audioKbps}k", "-ac", "$ch")
                }
            }
            Ffmpeg.run(base + listOf("-pass", "2") + audio + containerArgs(fmt, info, false) + listOf(out.absolutePath),
                job, info.duration, lo, hi, label)
        }

        val newSize: Long
        try {
            encode(videoKbps, true, 0f, 0.35f, "Analyzing")
            encode(videoKbps, false, 0.35f, 1f, "Compressing")
            var n = out.length()
            if (n > target * 1.04 && videoKbps > 40) {
                val audioBytes = audioKbps * 1000 / 8.0 * info.duration
                val factor = (target - audioBytes) / max(1.0, n - audioBytes)
                videoKbps = max(40.0, videoKbps * factor * 0.97)
                encode(videoKbps, false, 0.35f, 1f, "Fine-tuning size")
                n = out.length()
            }
            newSize = n
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            passDir.deleteRecursively()
        }
        return finish(out, orig, newSize, target, notes)
    }

    private fun convertVideo(src: Source, ws: Workspace, job: Job, info: MediaInfo, fmt: String): Result {
        val s = ws.settings
        val orig = src.size
        val notes = mutableListOf<String>()
        val resize = (s.resolution != "auto" && s.resolution != "original") || (s.fps != "auto" && s.fps != "original")
        if (src.ext == fmt && !resize) throw CompressionError("It's already ${fmt.uppercase()}. Pick another format, or a size, to change it.")
        val out = ws.outputFile(src, fmt)
        val webm = fmt == "webm"
        val vcopy = !resize && (COPY_VIDEO[fmt] == null || info.videoCodec in COPY_VIDEO[fmt]!!)
        val acopy = info.hasAudio && (COPY_AUDIO[fmt] == null || info.audioCodec in COPY_AUDIO[fmt]!!)
        val aEnc = if (webm) listOf("-c:a", if (Ffmpeg.has("libopus")) "libopus" else "libvorbis", "-b:a", "128k")
        else listOf("-c:a", "aac", "-b:a", "192k")
        val audio = if (info.hasAudio) listOf("-map", "0:a:0") + (if (acopy) listOf("-c:a", "copy") else aEnc) else emptyList()
        try {
            if (vcopy) {
                try {
                    Ffmpeg.run(listOf("-i", src.file.absolutePath, "-map", "0:v:0", "-c:v", "copy") + audio +
                        containerArgs(fmt, info, true) + listOf(out.absolutePath), job, info.duration, 0f, 1f, "Converting")
                    notes += "Converted without re-encoding (no quality lost)"
                    return finish(out, orig, out.length(), orig, notes, convert = true)
                } catch (e: CompressionError) {
                    out.delete()  // the container didn't accept it: re-encode below
                }
            }
            notes.clear()
            val vf = filters(info, s, null, notes)
            val vcodec = if (webm) listOf("-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-row-mt", "1",
                "-deadline", "good", "-cpu-used", vp9Speed(s))
            else listOf("-c:v", "libx264", "-preset", x264Preset(s), "-crf", "20")
            Ffmpeg.run(listOf("-i", src.file.absolutePath, "-map", "0:v:0", "-vf", vf) + vcodec + audio +
                containerArgs(fmt, info, false) + listOf(out.absolutePath), job, info.duration, 0f, 1f, "Converting")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return finish(out, orig, out.length(), orig, notes, convert = true)
    }

    // ------------------------------------------------------------ audio

    private val MP3_MPEG1 = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_MPEG2 = listOf(8, 16, 24, 32, 40, 48, 56, 64)
    private val CONVERT_KBPS = mapOf("mp3" to 192.0, "aac" to 192.0, "opus" to 128.0, "vorbis" to 160.0)

    /** Returns (codec, extension, notes) for the requested output format. */
    private fun audioCodec(src: Source, info: MediaInfo, fmt: String, kbps: Double): Triple<String, String, MutableList<String>> {
        val ext = src.ext
        val notes = mutableListOf<String>()
        val vorbisOk = Ffmpeg.has("libvorbis")
        fun ogg(preferVorbis: Boolean) = if (preferVorbis && vorbisOk) "vorbis" else "opus"
        return when {
            fmt == "mp3" -> Triple("mp3", "mp3", notes)
            fmt == "m4a" -> Triple("aac", "m4a", notes)
            fmt == "opus" -> Triple("opus", "opus", notes)
            fmt == "ogg" -> Triple(ogg(kbps >= 64), "ogg", notes)
            ext == "mp3" -> Triple("mp3", "mp3", notes)
            ext in listOf("m4a", "aac", "m4b") -> Triple("aac", if (ext == "m4b") "m4b" else "m4a", notes)
            ext == "opus" -> Triple("opus", "opus", notes)
            ext in listOf("ogg", "oga") -> Triple(ogg(kbps >= 64 && info.audioCodec != "opus"), "ogg", notes)
            ext in listOf("mp4", "mov", "m4v", "3gp", "mkv", "webm") -> {
                notes += "No video inside, saved as M4A audio"
                Triple("aac", "m4a", notes)
            }
            else -> {
                notes += "Saved as MP3 (from ${ext.uppercase()})"
                Triple("mp3", "mp3", notes)
            }
        }
    }

    private fun audioArgs(codec: String, info: MediaInfo, kbps: Double): List<String> {
        val chIn = if (info.audioChannels > 0) info.audioChannels else 2
        when (codec) {
            "mp3" -> {
                val k: Int
                val rate: Int
                val abr = mutableListOf<String>()
                if (kbps >= 32) {
                    k = min(320.0, kbps).toInt()
                    if (k !in MP3_MPEG1) abr += listOf("-abr", "1")
                    var r = min(if (info.audioRate > 0) info.audioRate else 44100, 48000)
                    if (r !in listOf(32000, 44100, 48000)) r = 44100
                    rate = r
                } else {
                    k = MP3_MPEG2.filter { it <= max(8.0, kbps) }.max()
                    rate = if (k == 8) 8000 else if (k >= 16) 22050 else 11025
                }
                val ch = if (k < 64) 1 else min(2, chIn)
                return listOf("-c:a", "libmp3lame", "-b:a", "${k}k") + abr +
                    listOf("-ar", "$rate", "-ac", "$ch", "-id3v2_version", "3")
            }
            "aac" -> {
                val k = min(320.0, max(12.0, kbps)).toInt()
                val ch = if (k < 48) 1 else min(2, chIn)
                return listOf("-c:a", "aac", "-b:a", "${k}k", "-ac", "$ch") + (if (k < 24) listOf("-ar", "22050") else emptyList())
            }
            "opus" -> {
                val k = min(510.0, max(6.0, kbps)).toInt()
                val ch = if (k < 24) 1 else min(2, chIn)
                val a = mutableListOf("-c:a", "libopus", "-b:a", "${k}k", "-ac", "$ch")
                if (info.audioRate > 0 && info.audioRate !in listOf(8000, 12000, 16000, 24000, 48000)) a += listOf("-ar", "48000")
                return a
            }
            else -> {
                val k = min(500.0, max(48.0, kbps)).toInt()
                return listOf("-c:a", "libvorbis", "-b:a", "${k}k", "-ac", "${min(2, chIn)}")
            }
        }
    }

    fun compressAudio(src: Source, ws: Workspace, job: Job, known: MediaInfo? = null, forcedFormat: String? = null): Result {
        val s = ws.settings
        val info = known ?: Ffmpeg.probe(src.file.absolutePath, job)
        if (!info.hasAudio) throw CompressionError("No audio track found in this file.")
        if (info.duration <= 0.1) throw CompressionError("Couldn't read the length of this audio.")
        val orig = src.size
        val fmt = forcedFormat ?: s.audioFormat
        val convert = s.convertOnly
        val target: Long
        var kbps: Double
        val codec: String
        val outExt: String
        var notes: MutableList<String>
        if (convert) {
            if (fmt == "keep" && forcedFormat == null) throw CompressionError("Pick an audio format to convert to.")
            val t = audioCodec(src, info, fmt, 160.0)
            codec = t.first; outExt = t.second; notes = t.third
            if (outExt == src.ext) throw CompressionError("It's already ${outExt.uppercase()}.")
            kbps = CONVERT_KBPS[codec] ?: 160.0
            target = orig
        } else {
            target = s.targetBytes(orig)
            kbps = target * 8 / 1000.0 / info.duration * 0.98
            val t = audioCodec(src, info, fmt, kbps)
            codec = t.first; outExt = t.second; notes = t.third
        }
        if (forcedFormat != null) notes = mutableListOf("Audio only, extracted from the video")
        val args = audioArgs(codec, info, kbps)
        val out = ws.outputFile(src, outExt)
        val label = if (convert) "Converting" else "Compressing"
        fun encode(a: List<String>, lbl: String) = Ffmpeg.run(
            listOf("-i", src.file.absolutePath, "-map", "0:a:0", "-vn", "-map_metadata", "0") + a + listOf(out.absolutePath),
            job, info.duration, 0f, 1f, lbl)
        var n: Long
        try {
            encode(args, label)
            n = out.length()
            if (!convert && n > target * 1.04) {
                val args2 = audioArgs(codec, info, kbps * target / n * 0.97)
                if (args2 != args) {
                    encode(args2, "Fine-tuning size")
                    n = out.length()
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return finish(out, orig, n, target, notes, convert = convert || forcedFormat != null)
    }

    fun finish(out: File, orig: Long, new: Long, target: Long, notes: MutableList<String>, convert: Boolean = false): Result {
        if (convert) return Result(true, out, orig, new, target, "", notes)
        if (new >= orig) {
            out.delete()
            return Result(false, null, orig, 0, target, "Already well compressed — couldn't make it smaller.", notes)
        }
        if (new > target * 1.06) notes += "Couldn't fully reach the target without ruining quality."
        return Result(true, out, orig, new, target, "", notes)
    }
}
