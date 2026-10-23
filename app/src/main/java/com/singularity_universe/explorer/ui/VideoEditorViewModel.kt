package com.singularity_universe.explorer.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the video editor: the video's size and length, the part to keep (trim), the frame to
 * keep (crop), a preview player that loops over the kept part, and the export.
 */
@OptIn(UnstableApi::class)
class VideoEditorViewModel(app: Application, path: String) : AndroidViewModel(app) {
    val file = File(path)

    sealed interface State {
        data object Loading : State
        data object Ready : State
        class Failed(val reason: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    /** Length, and the frame size as displayed (rotation applied). */
    var durationMs by mutableLongStateOf(0L)
        private set
    var width by mutableStateOf(0)
        private set
    var height by mutableStateOf(0)
        private set
    private var hdr = false

    /** Frames along the video, for the trim strip. */
    val thumbnails = mutableStateListOf<Bitmap>()

    var startMs by mutableLongStateOf(0L)
        private set
    var endMs by mutableLongStateOf(0L)
        private set

    /** The frame to keep, in video pixels; the whole frame when not cropped. */
    var crop by mutableStateOf(RectF())

    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        repeatMode = Player.REPEAT_MODE_OFF
        prepare()
    }

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 0..1 while saving, null otherwise. */
    var exportProgress by mutableStateOf<Float?>(null)
        private set
    private var transformer: Transformer? = null
    private var exportTemp: File? = null
    private var progressJob: Job? = null

    val trimmed: Boolean get() = startMs > 0 || endMs < durationMs
    val cropped: Boolean get() = crop.left > 0.5f || crop.top > 0.5f || crop.right < width - 0.5f || crop.bottom < height - 0.5f
    val edited: Boolean get() = trimmed || cropped

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        })
        viewModelScope.launch { load() }
        // Follows playback, and loops it over the kept part.
        viewModelScope.launch {
            while (isActive) {
                positionMs = player.currentPosition
                if (player.isPlaying && endMs > 0 && positionMs >= endMs) player.seekTo(startMs)
                delay(40)
            }
        }
    }

    private suspend fun load() {
        try {
            withContext(Dispatchers.IO) {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.path)
                    fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                    val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
                    var w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    var h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    if (rotation % 180 != 0) w = h.also { h = w }
                    if (duration <= 0 || w <= 0 || h <= 0) throw IllegalStateException("No video track")
                    // ST 2084 (PQ) and HLG: HDR video.
                    val transfer = number(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER).toInt()
                    withContext(Dispatchers.Main) {
                        durationMs = duration
                        width = w
                        height = h
                        hdr = transfer == 6 || transfer == 7
                        startMs = 0
                        endMs = duration
                        crop = RectF(0f, 0f, w.toFloat(), h.toFloat())
                        state = State.Ready
                    }
                    loadThumbnails(retriever, duration, w, h)
                }
            }
        } catch (e: Exception) {
            Log.w("Explorer", "Couldn't open ${file.path} for editing", e)
            state = State.Failed("Couldn't open this video")
        }
    }

    private suspend fun loadThumbnails(retriever: MediaMetadataRetriever, duration: Long, w: Int, h: Int) {
        val thumbHeight = 120
        val thumbWidth = (thumbHeight * w / h.toFloat()).toInt().coerceAtLeast(1)
        for (i in 0 until THUMBNAILS) {
            val timeUs = duration * 1000 * (2 * i + 1) / (2 * THUMBNAILS)
            val frame = runCatching {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, thumbWidth, thumbHeight)
            }.getOrNull() ?: continue
            withContext(Dispatchers.Main) { thumbnails += frame }
        }
    }

    // ---- Playback ----

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.currentPosition !in startMs until endMs) player.seekTo(startMs)
            player.play()
        }
    }

    /** Dragging a trim handle shows that frame. */
    fun setRange(start: Long, end: Long) {
        val startChanged = start != startMs
        startMs = start.coerceIn(0, durationMs)
        endMs = end.coerceIn(startMs, durationMs)
        player.pause()
        player.seekTo(if (startChanged) startMs else endMs)
    }

    fun resetCrop() {
        crop = RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    // ---- Export ----

    /**
     * Saves the kept part and frame as an MP4: a copy next to the original, or over the original
     * when [replace] (MP4 files only). Calls [done] with the saved file, or null and a reason.
     */
    fun export(replace: Boolean, done: (File?, String?) -> Unit) {
        if (exportProgress != null) return
        player.pause()
        val target = if (replace && canReplace()) file else copyTarget(file)
        start(target, toneMap = false, done)
    }

    private fun start(target: File, toneMap: Boolean, done: (File?, String?) -> Unit) {
        // Written beside the target and renamed over it at the end, so a failure leaves the original intact.
        val temp = File(target.parentFile, ".${target.nameWithoutExtension}.saving.mp4")
        temp.delete()
        exportTemp = temp
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setClippingConfiguration(clipping).build()
        val videoEffects = if (cropped) listOf(cropEffect()) else emptyList()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), videoEffects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited)))
            .apply { if (toneMap) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finishExport()
                val saved = temp.renameTo(target) || runCatching {
                    temp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    temp.delete()
                }.isSuccess
                if (saved) {
                    MediaScannerConnection.scanFile(getApplication(), arrayOf(target.path), null, null)
                    done(target, null)
                } else {
                    temp.delete()
                    done(null, "Couldn't write the video")
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finishExport()
                temp.delete()
                Log.w("Explorer", "Video export failed", exportException)
                // Not every phone can encode HDR: try again as standard dynamic range.
                if (hdr && !toneMap) start(target, toneMap = true, done)
                else done(null, exportException.message ?: "Couldn't save the video")
            }
        }

        val transformer = Transformer.Builder(getApplication())
            // Trimming alone copies most of the video as it is instead of re-encoding all of it.
            .experimentalSetTrimOptimizationEnabled(videoEffects.isEmpty())
            .addListener(listener)
            .build()
        this.transformer = transformer
        exportProgress = 0f
        transformer.start(composition, temp.path)

        val holder = ProgressHolder()
        progressJob = viewModelScope.launch {
            while (isActive && exportProgress != null) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    exportProgress = holder.progress / 100f
                }
                delay(200)
            }
        }
    }

    /** The kept frame as a Crop effect, in normalized coordinates (-1..1, y pointing up). */
    private fun cropEffect(): Crop {
        val left = 2 * crop.left / width - 1
        val right = 2 * crop.right / width - 1
        val top = 1 - 2 * crop.top / height
        val bottom = 1 - 2 * crop.bottom / height
        return Crop(left, right, bottom, top)
    }

    fun cancelExport() {
        transformer?.cancel()
        exportTemp?.delete()
        finishExport()
    }

    private fun finishExport() {
        transformer = null
        progressJob?.cancel()
        exportProgress = null
    }

    fun canReplace(): Boolean = file.extension.equals("mp4", ignoreCase = true)

    override fun onCleared() {
        transformer?.cancel()
        exportTemp?.takeIf { transformer != null }?.delete()
        player.release()
    }

    private companion object {
        const val THUMBNAILS = 10

        /** "clip.mp4" → "clip_edited.mp4" (or "_edited_2", ...); always MP4. */
        fun copyTarget(original: File): File {
            val base = original.nameWithoutExtension
            var candidate = File(original.parentFile, "${base}_edited.mp4")
            var n = 2
            while (candidate.exists()) candidate = File(original.parentFile, "${base}_edited_${n++}.mp4")
            return candidate
        }
    }
}
