package com.singularity_universe.explorer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.singularity_universe.explorer.MainActivity
import com.singularity_universe.explorer.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Copies and moves that keep going when Explorer is left: a queue worked through one item at a
 * time, shown in a notification by [TransferService] (which keeps the app running meanwhile).
 */
object Transfers {
    class Request(val paths: List<String>, val destDir: String, val destName: String, val move: Boolean)

    /**
     * The request being worked on: [done] of [total] items finished, [current] being copied now,
     * [bytes] copied of [totalBytes] (0 when unknown: some sources are remote), and [queued] more
     * requests waiting.
     */
    data class Progress(
        val request: Request,
        val done: Int,
        val total: Int,
        val current: String,
        val bytes: Long = 0,
        val totalBytes: Long = 0,
        val queued: Int = 0,
    )

    /** A request finished: all of it, or [error] after [done] items, or [cancelled] by the user. */
    class Finished(val request: Request, val done: Int, val error: String?, val cancelled: Boolean)

    var progress by mutableStateOf<Progress?>(null)
        private set

    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 16)
    val finished: SharedFlow<Finished> = _finished

    private val queue = ArrayDeque<Pair<Request, FileRepository>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var cancelled = false
    @Volatile private var copying: CopyProgress? = null

    val busy: Boolean get() = synchronized(queue) { queue.isNotEmpty() } || worker?.isActive == true

    /** Runs once the queue is empty (MainActivity closing connections it would otherwise close itself). */
    @Volatile var whenIdle: (() -> Unit)? = null

    fun enqueue(context: Context, repo: FileRepository, request: Request) {
        val app = context.applicationContext
        synchronized(queue) { queue.addLast(request to repo) }
        TransferService.start(app)
        if (worker?.isActive != true) worker = scope.launch { work(app) }
    }

    /** Stops now: in the middle of the file being copied (its partial copy is removed). */
    fun cancel() {
        cancelled = true
        copying?.cancelled = true
        synchronized(queue) { queue.clear() }
    }

    private suspend fun work(app: Context) {
        while (currentCoroutineContext().isActive) {
            val (request, repo) = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            cancelled = false
            var done = 0
            var error: String? = null
            val bytes = CopyProgress()
            copying = bytes
            // A size to show progress against, when every source is on this phone.
            val totalBytes = if (request.paths.none(repo::needsCopyToOpen)) request.paths.sumOf { FileCopier.sizeOf(File(it)) } else 0L
            fun publish(current: String) {
                progress = Progress(request, done, request.paths.size, current, bytes.bytes, totalBytes, synchronized(queue) { queue.size })
                TransferService.update(app)
            }
            for (path in request.paths) {
                if (cancelled) break
                val name = path.substringAfterLast('/')
                publish(name)
                // Byte counts arrive from the copy thread; refresh the bar and notification meanwhile.
                val ticker = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(500)
                        publish(name)
                    }
                }
                try {
                    repo.transfer(listOf(path), request.destDir, request.move, bytes)
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CopyCancelledException) {
                    break
                } catch (e: Exception) {
                    Log.w("Explorer", "Transfer failed", e)
                    error = e.message ?: "Something went wrong"
                    break
                } finally {
                    ticker.cancel()
                }
            }
            copying = null
            val result = Finished(request, done, error, cancelled && error == null && done < request.paths.size)
            _finished.tryEmit(result)
            TransferService.finished(app, result)
        }
        progress = null
        TransferService.stop(app)
        whenIdle?.let {
            whenIdle = null
            it()
        }
    }

    /** "Copying 2 of 5 to Mac · 1 more queued". */
    fun title(progress: Progress): String {
        val verb = if (progress.request.move) "Moving" else "Copying"
        val queued = if (progress.queued > 0) " · ${progress.queued} more queued" else ""
        return "$verb ${progress.done + 1} of ${progress.total} to ${progress.request.destName}$queued"
    }

    /** "350 MB of 1.2 GB", or "350 MB" when the total isn't known. */
    fun amount(context: Context, progress: Progress): String {
        val done = Formatter.formatShortFileSize(context, progress.bytes)
        return if (progress.totalBytes > 0) "$done of ${Formatter.formatShortFileSize(context, progress.totalBytes)}" else done
    }

    /** 0..1 by bytes when the total is known, otherwise by items. */
    fun fraction(progress: Progress): Float =
        if (progress.totalBytes > 0) (progress.bytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
        else (progress.done + 0.5f) / progress.total

    fun summary(result: Finished): String {
        val verb = if (result.request.move) "Moved" else "Copied"
        val items = if (result.done == 1) "1 item" else "${result.done} items"
        return when {
            result.error != null -> "${if (result.request.move) "Move" else "Copy"} stopped after $items: ${result.error}"
            result.cancelled -> "Cancelled after $items"
            else -> "$verb $items to ${result.request.destName}"
        }
    }
}

/** The notification (with progress and Cancel) that keeps transfers running in the background. */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Transfers.cancel()
            return START_NOT_STICKY
        }
        startForeground(PROGRESS_ID, progressNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val PROGRESS_ID = 4401
        private const val DONE_ID = 4402
        private const val ACTION_CANCEL = "com.singularity_universe.explorer.action.CANCEL_TRANSFERS"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }
                .onFailure { Log.w("Explorer", "Couldn't start the transfer service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }

        fun update(context: Context) {
            context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(context))
        }

        fun finished(context: Context, result: Transfers.Finished) {
            channel(context)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
                .setContentTitle(if (result.error != null) "Transfer stopped" else if (result.request.move) "Move finished" else "Copy finished")
                .setContentText(Transfers.summary(result))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, notification)
        }

        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Copying and moving", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun progressNotification(context: Context): Notification {
            channel(context)
            val progress = Transfers.progress
            val cancel = PendingIntent.getService(
                context, 1, Intent(context, TransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openApp(context))
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_launcher_folder_foreground), "Cancel", cancel).build())
            if (progress == null) {
                return builder.setContentTitle("Preparing…").setProgress(0, 0, true).build()
            }
            return builder
                .setContentTitle(Transfers.title(progress))
                .setContentText("${File(progress.current).name} · ${Transfers.amount(context, progress)}")
                .apply {
                    if (progress.totalBytes > 0) setProgress(1000, (progress.bytes * 1000 / progress.totalBytes).toInt().coerceIn(0, 1000), false)
                    else setProgress(progress.total, progress.done, false)
                }
                .build()
        }
    }
}
