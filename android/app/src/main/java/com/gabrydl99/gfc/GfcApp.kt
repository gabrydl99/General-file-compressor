package com.gabrydl99.gfc

import android.app.Application
import com.gabrydl99.gfc.core.Ffmpeg
import com.gabrydl99.gfc.work.CompressService
import com.gabrydl99.gfc.work.WorkRepo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.concurrent.thread

class GfcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkRepo.init(this)
        Ffmpeg.init(this)
        CompressService.createChannels(this)
        thread(isDaemon = true, name = "gfc-init") {
            Ffmpeg.loadEncoders()
            runCatching { PDFBoxResourceLoader.init(applicationContext) }
        }
    }
}
