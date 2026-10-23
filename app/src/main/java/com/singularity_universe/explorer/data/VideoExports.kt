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
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.singularity_universe.explorer.R
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Saving edited videos, independent of the editor: the export keeps going (in [VideoExportService],
 * with a progress notification) when the editor is left. Runs on the main thread, as Media3
 * Transformer requires.
 */
@OptIn(UnstableApi::class)
object VideoExports {
    /** [crop] is the kept frame in normalized coordinates (left, right, bottom, top), or null. */
    class Request(val source: File, val target: File, val startMs: Long, val endMs: Long, val crop: FloatArray?, val hdr: Boolean)

    class Result(val request: Request, val saved: File?, val error: String?, val cancelled: Boolean)

    /** 0..1 while a video is being saved. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var current: Request? = null
        private set

    private val _finished = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val finished: SharedFlow<Result> = _finished

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var temp: File? = null

    fun start(context: Context, request: Request) {
        if (current != null) return
        val app = context.applicationContext
        current = request
        progress = 0f
        VideoExportService.start(app)
        begin(app, request, toneMap = false)
    }

    fun cancel() {
        val request = current ?: return
        transformer?.cancel()
        finish(request, Result(request, null, null, cancelled = true))
    }

    private fun begin(app: Context, request: Request, toneMap: Boolean) {
        // Written beside the target and renamed over it at the end, so a failure leaves the original intact.
        val partial = File(request.target.parentFile, ".${request.target.nameWithoutExtension}.saving.mp4")
        partial.delete()
        temp = partial
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(request.startMs).setEndPositionMs(request.endMs).build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(request.source)).setClippingConfiguration(clipping).build()
        val videoEffects = request.crop?.let { (l, r, b, t) -> listOf(Crop(l, r, b, t)) }.orEmpty()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), videoEffects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited)))
            .apply { if (toneMap) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val target = request.target
                val saved = partial.renameTo(target) || runCatching {
                    partial.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    partial.delete()
                }.isSuccess
                if (saved) {
                    MediaScannerConnection.scanFile(app, arrayOf(target.path), null, null)
                    finish(request, Result(request, target, null, cancelled = false))
                } else {
                    partial.delete()
                    finish(request, Result(request, null, "Couldn't write the video", cancelled = false))
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                partial.delete()
                Log.w("Explorer", "Video export failed", exportException)
                // Not every phone can encode HDR: try again as standard dynamic range.
                if (request.hdr && !toneMap) begin(app, request, toneMap = true)
                else finish(request, Result(request, null, exportException.message ?: "Couldn't save the video", cancelled = false))
            }
        }

        val created = Transformer.Builder(app)
            // Trimming alone copies most of the video as it is instead of re-encoding all of it.
            .experimentalSetTrimOptimizationEnabled(videoEffects.isEmpty())
            .addListener(listener)
            .build()
        transformer = created
        created.start(composition, partial.path)

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (transformer !== created || current !== request) return
                if (created.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress = holder.progress / 100f
                    VideoExportService.update(app)
                }
                main.postDelayed(this, 300)
            }
        }
        main.post(poll)
    }

    private fun finish(request: Request, result: Result) {
        if (current !== request) return
        if (result.cancelled) temp?.delete()
        transformer = null
        temp = null
        current = null
        progress = null
        _finished.tryEmit(result)
        val app = appContext ?: return
        VideoExportService.finished(app, result)
        VideoExportService.stop(app)
    }

    internal var appContext: Context? = null
}

/** Keeps the app running while a video is saved, with a progress notification and Cancel. */
class VideoExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            VideoExports.cancel()
            return START_NOT_STICKY
        }
        startForeground(PROGRESS_ID, progressNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "video_exports"
        private const val PROGRESS_ID = 4501
        private const val DONE_ID = 4502
        private const val ACTION_CANCEL = "com.singularity_universe.explorer.action.CANCEL_VIDEO_EXPORT"

        fun start(context: Context) {
            VideoExports.appContext = context.applicationContext
            runCatching { context.startForegroundService(Intent(context, VideoExportService::class.java)) }
                .onFailure { Log.w("Explorer", "Couldn't start the video export service", it) }
        }

        fun stop(context: Context) = context.stopService(Intent(context, VideoExportService::class.java))

        fun update(context: Context) =
            context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(context))

        fun finished(context: Context, result: VideoExports.Result) {
            if (result.cancelled) return
            channel(context)
            context.getSystemService(NotificationManager::class.java).notify(
                DONE_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
                    .setContentTitle(if (result.saved != null) "Video saved" else "Couldn't save the video")
                    .setContentText(result.saved?.name ?: result.error)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Saving videos", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }

        private fun progressNotification(context: Context): Notification {
            channel(context)
            val cancel = PendingIntent.getService(
                context, 2, Intent(context, VideoExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val progress = VideoExports.progress ?: 0f
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
                .setContentTitle("Saving ${VideoExports.current?.target?.name ?: "video"}")
                .setContentText("${(progress * 100).toInt()}%")
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_launcher_folder_foreground), "Cancel", cancel).build())
                .build()
        }
    }
}
