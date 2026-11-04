package com.gabrydl99.gfc.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gabrydl99.gfc.R
import com.gabrydl99.gfc.core.CancelledException
import com.gabrydl99.gfc.core.Engine
import com.gabrydl99.gfc.core.Job
import com.gabrydl99.gfc.core.Result
import com.gabrydl99.gfc.core.humanSize
import com.gabrydl99.gfc.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Keeps compressing with the screen off, showing progress in a notification. */
class CompressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ABORT) {
            WorkRepo.abort()
            return START_NOT_STICKY
        }
        val n = buildProgress("Starting…", 0, 0, true)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gfc:compress").apply { acquire(6 * 60 * 60 * 1000L) }
        if (active) return START_NOT_STICKY
        active = true
        scope.launch {
            try {
                runQueue()
            } finally {
                active = false
            }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        val queue = WorkRepo.pending()
        val settings = WorkRepo.settings.value
        WorkRepo.setRun { RunState(running = true, total = queue.size, index = 0) }
        var done = 0; var failed = 0; var skipped = 0; var saved = 0L; var aborted = false
        val report = JSONArray()
        for ((i, item) in queue.withIndex()) {
            if (WorkRepo.run.value.aborting) { aborted = true; break }
            WorkRepo.setRun { it.copy(index = i + 1) }
            WorkRepo.edit(item.id) { it.copy(status = Status.WORKING, progress = 0f, stage = "Starting", result = null) }
            val job = Job { f, stage ->
                WorkRepo.edit(item.id) { it.copy(progress = f, stage = stage) }
                maybeNotify(item.name, i + 1, queue.size, f)
            }
            WorkRepo.currentJob = job
            val r: Result? = try {
                Engine.compress(applicationContext, item.name, item.path, item.uri, item.shared, settings, job)
            } catch (e: CancelledException) {
                null
            } catch (e: Throwable) {
                Result(false, message = "Unexpected error: ${e.javaClass.simpleName}: ${e.message}".take(300))
            }
            WorkRepo.currentJob = null
            if (r == null) {
                aborted = true
                WorkRepo.edit(item.id) { it.copy(status = Status.STOPPED, progress = 0f, stage = "Stopped — original untouched") }
                break
            }
            if (r.ok) { done++; saved += (r.originalSize - r.newSize).coerceAtLeast(0) }
            else if (isSkip(r.message)) skipped++ else failed++
            WorkRepo.edit(item.id) {
                it.copy(status = if (r.ok) Status.DONE else Status.FAILED, progress = if (r.ok) 1f else it.progress, stage = "", result = r)
            }
            report.put(JSONObject().apply {
                put("name", item.name); put("ok", r.ok); put("message", r.message)
                put("original", r.originalSize); put("new", r.newSize); put("target", r.targetSize)
                put("output", r.output?.absolutePath ?: JSONObject.NULL)
                put("movedTo", r.originalMovedTo ?: JSONObject.NULL)
                put("notes", JSONArray(r.notes))
            })
        }
        val parts = mutableListOf<String>()
        if (done > 0) parts += "$done done, ${humanSize(saved)} saved."
        if (skipped > 0) parts += "$skipped skipped."
        if (failed > 0) parts += "$failed failed."
        if (aborted) parts += "Stopped."
        val summary = parts.joinToString(" ").ifEmpty { "Nothing was processed." }
        WorkRepo.setRun { RunState(running = false, total = queue.size, index = queue.size, summary = summary) }
        WorkRepo.reportFile?.let { f ->
            runCatching {
                f.writeText(JSONObject().apply {
                    put("summary", summary); put("done", done); put("failed", failed); put("skipped", skipped); put("aborted", aborted)
                    put("items", report)
                }.toString(2))
            }
        }
        finishNotification(summary)
        stopSelfSafely()
    }

    private fun maybeNotify(name: String, index: Int, total: Int, f: Float) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700) return
        lastNotify = now
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_PROGRESS, buildProgress("$index of $total · $name", index, total, false, f)) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildProgress(text: String, index: Int, total: Int, indeterminate: Boolean, f: Float = 0f): Notification {
        val abort = PendingIntent.getService(this, 1, Intent(this, CompressService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val overall = if (total > 0) ((index - 1 + f) / total * 1000).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Compressing")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(1000, overall.coerceIn(0, 1000), indeterminate)
            .setContentIntent(openAppIntent())
            .addAction(0, "Abort", abort)
            .build()
    }

    private fun finishNotification(summary: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Compression finished")
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { nm.notify(NOTIF_DONE, n) }
    }

    private fun stopSelfSafely() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        WorkRepo.currentJob?.cancel()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        const val NOTIF_PROGRESS = 1
        const val NOTIF_DONE = 2
        const val ACTION_ABORT = "com.gabrydl99.gfc.ABORT"

        /** Not an error: the file was left alone on purpose. */
        fun isSkip(message: String): Boolean {
            val m = message.lowercase()
            return "already" in m || "can only be compressed" in m || "pick a" in m
        }
        @Volatile private var active = false

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun start(ctx: Context) {
            if (WorkRepo.pending().isEmpty() || active) return
            WorkRepo.setRun { it.copy(running = true, aborting = false, summary = "") }
            ContextCompat.startForegroundService(ctx, Intent(ctx, CompressService::class.java))
        }
    }
}
