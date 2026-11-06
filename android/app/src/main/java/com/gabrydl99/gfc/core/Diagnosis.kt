package com.gabrydl99.gfc.core

/** Turns an error into likely causes and things to try (port of errors.py). */
data class Diagnosis(val title: String, val causes: List<String>, val tips: List<String>)

object Diagnoses {
    private val RULES: List<Pair<List<String>, Diagnosis>> = listOf(
        listOf("password") to Diagnosis(
            "The PDF is locked with a password",
            listOf("The file is protected, so its content can't be read or rewritten."),
            listOf("Open it with the password and save or print it to a new PDF without protection, then try again.")),
        listOf("isn't a valid office") to Diagnosis(
            "The Office file couldn't be opened",
            listOf("It may be an old .doc/.ppt/.xls file renamed to .docx/.pptx/.xlsx.",
                "It may be protected with a password.",
                "The file may be damaged or only partly downloaded."),
            listOf("Open it in Word, PowerPoint or Excel and save a fresh .docx/.pptx/.xlsx copy.",
                "Remove the password, then try again.")),
        listOf("already well compressed") to Diagnosis(
            "The result wasn't smaller than the original",
            listOf("The file is already stored efficiently, so re-compressing it doesn't save space.",
                "The size you picked is very close to the current size."),
            listOf("Pick a lower percentage or a smaller size in MB.",
                "For videos, lower the resolution or the frame rate.",
                "For photos, try converting to WebP, which is usually much smaller.")),
        listOf("already smaller than the size") to Diagnosis(
            "The file is already below the size you picked",
            listOf("In 'Size in MB' mode, files that are already smaller are left alone."),
            listOf("Type a smaller size, or switch to Percentage.")),
        listOf("can only be compressed, not converted") to Diagnosis(
            "This type can't be converted",
            listOf("'Convert only' works for videos, photos and audio. PDFs and Office files only support compression."),
            listOf("Switch to Percentage or Size in MB to compress it.")),
        listOf("pick a", "it's already") to Diagnosis(
            "Nothing to convert",
            listOf("The format chosen for this type is 'Keep original', or it's the same format the file already has."),
            listOf("In the Formats block, pick a different format for this kind of file.")),
        listOf("stopped responding") to Diagnosis(
            "The video engine got stuck",
            listOf("The file may have broken timestamps or an unusual structure.",
                "The phone may have run out of memory or closed the app in the background."),
            listOf("Try again with fewer apps open.",
                "Use 'Convert only' to MP4 first, then compress the converted file.")),
        listOf("ffmpeg is missing") to Diagnosis(
            "The video engine is missing",
            listOf("This build of the app doesn't contain FFmpeg for your phone's processor."),
            listOf("Install the latest APK of FileCompressor.")),
        listOf("ffmpeg could not process", "no readable video or audio", "couldn't read the length") to Diagnosis(
            "The video/audio engine couldn't read or write this file",
            listOf("The file may be damaged or incomplete (for example an interrupted download or copy).",
                "It may use an unusual codec, or be copy-protected (DRM, like purchased movies).",
                "The phone storage may be full, or the output folder may not allow writing."),
            listOf("Check that the file plays correctly in a player app.",
                "Try 'Convert only' to MP4 first.",
                "Free up some space or choose another output folder.")),
        listOf("no audio track") to Diagnosis(
            "There's no sound in this file",
            listOf("The file has no audio track to compress or extract."),
            listOf("If it's a video, choose a video format instead of MP3/M4A.")),
        listOf("couldn't open this picture") to Diagnosis(
            "The picture couldn't be opened",
            listOf("The file may be damaged, or not really an image despite its extension.",
                "Some rare variants (unusual TIFF or HEIC flavours, or HEIC on Android 8) aren't supported."),
            listOf("Open it in the Gallery or another photo app, save it again as JPG or PNG, then retry.")),
        listOf("didn't allow writing", "permission", "couldn't read the file") to Diagnosis(
            "Android blocked reading or writing the file",
            listOf("The app may not have access to all files.",
                "The output folder may be on an SD card or in a protected place.",
                "A file shared from another app may only be readable for a short time."),
            listOf("Allow 'All files access' for FileCompressor in the phone settings.",
                "Choose another output folder with 'Change folder', or use the default one.",
                "Add the file again with 'Add files' instead of sharing it.")),
        listOf("storage is full", "no space") to Diagnosis(
            "The phone storage is full",
            listOf("There isn't enough free space to write the compressed copy."),
            listOf("Free up some space, or choose an output folder on an SD card.")),
        listOf("not enough memory") to Diagnosis(
            "Not enough memory",
            listOf("The file is very large (for example a huge photo or PDF) and the phone ran out of RAM."),
            listOf("Close other apps and try again, or process this file on its own.")),
        listOf("file not found") to Diagnosis(
            "The file isn't there anymore",
            listOf("It was moved, renamed or deleted after it was added to the list."),
            listOf("Remove it from the list and add it again.")),
        listOf("couldn't move the original", "original was kept") to Diagnosis(
            "The original couldn't be moved",
            listOf("The app doesn't have access to all files, or the file came from another app."),
            listOf("Allow 'All files access' in the phone settings, or move the original yourself.")),
    )

    private val FALLBACK = Diagnosis(
        "Something unexpected went wrong",
        listOf("The file may be damaged or in a variant the app doesn't handle.",
            "A temporary problem (storage, memory) may have interrupted the work."),
        listOf("Try the file again on its own.",
            "If it's a video, try 'Convert only' to MP4 first.",
            "Check that the file opens correctly in its usual app."))

    fun diagnose(message: String): Diagnosis {
        val low = message.lowercase()
        for ((keys, d) in RULES) if (keys.any { it in low }) return d
        return FALLBACK
    }
}
