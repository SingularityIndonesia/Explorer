package com.singularity_universe.explorer.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import androidx.media3.exoplayer.ExoPlayer
import com.singularity_universe.explorer.data.VideoExports
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    private var thumbWidth = 1
    private var thumbHeight = 1

    /** One frame's length, from the video's frame rate (falls back to 30fps if it isn't known). */
    var frameMs by mutableFloatStateOf(1000f / 30f)
        private set

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

    /** 0..1 while this video is being saved, null otherwise. */
    val exportProgress: Float? get() = if (VideoExports.current?.source == file) VideoExports.progress else null

    /** Told when the export started from this editor finishes (while the editor is open). */
    private var pendingDone: ((File?, String?) -> Unit)? = null

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
        viewModelScope.launch {
            VideoExports.finished.collect { result ->
                if (result.request.source != file || result.cancelled) return@collect
                pendingDone?.invoke(result.saved, result.error)
                pendingDone = null
            }
        }
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
                        frameMs = 1000f / frameRate()
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
        thumbHeight = 120
        thumbWidth = (thumbHeight * w / h.toFloat()).toInt().coerceAtLeast(1)
        for (i in 0 until THUMBNAILS) {
            val timeUs = duration * 1000 * (2 * i + 1) / (2 * THUMBNAILS)
            val frame = runCatching {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, thumbWidth, thumbHeight)
            }.getOrNull() ?: continue
            withContext(Dispatchers.Main) { thumbnails += frame }
        }
    }

    /** The video's frame rate, or 30 if the container doesn't say. */
    private fun frameRate(): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { if (it.containsKey(MediaFormat.KEY_FRAME_RATE)) it.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else null }
                ?.takeIf { it > 0f } ?: 30f
        } catch (e: Exception) {
            30f
        } finally {
            extractor.release()
        }
    }

    /**
     * Individual frames [frameMs] apart, centered on [centerMs] — a close-up for frame-precise
     * trimming, instead of the [thumbnails] sampled across the whole video.
     */
    suspend fun frameStrip(centerMs: Long, count: Int): List<Bitmap> = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.path)
            val half = count / 2
            (0 until count).mapNotNull { i ->
                val t = (centerMs + (i - half) * frameMs).coerceIn(0f, durationMs.toFloat())
                runCatching {
                    retriever.getScaledFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, thumbWidth, thumbHeight)
                }.getOrNull()
            }
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
     * when [replace] (MP4 files only). It runs in [VideoExports], so it carries on if the editor is
     * left; [done] gets the saved file, or null and a reason, if the editor is still open then.
     */
    fun export(replace: Boolean, done: (File?, String?) -> Unit) {
        if (VideoExports.current != null) return
        player.pause()
        pendingDone = done
        val target = if (replace && canReplace()) file else copyTarget(file)
        VideoExports.start(getApplication(), VideoExports.Request(file, target, startMs, endMs, if (cropped) cropValues() else null, hdr))
    }

    /** The kept frame in normalized coordinates (-1..1, y pointing up): left, right, bottom, top. */
    private fun cropValues(): FloatArray = floatArrayOf(
        2 * crop.left / width - 1,
        2 * crop.right / width - 1,
        1 - 2 * crop.bottom / height,
        1 - 2 * crop.top / height,
    )

    fun cancelExport() = VideoExports.cancel()

    fun canReplace(): Boolean = file.extension.equals("mp4", ignoreCase = true)

    override fun onCleared() {
        // A save in progress carries on in VideoExports.
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
