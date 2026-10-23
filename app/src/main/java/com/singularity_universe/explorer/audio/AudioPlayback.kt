package com.singularity_universe.explorer.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File

/**
 * The app's side of audio playback: a controller connected to [PlaybackService], and what's
 * playing as Compose state for the mini player and the Now playing sheet.
 */
object AudioPlayback {
    data class NowPlaying(
        val title: String,
        val artist: String?,
        val artwork: Bitmap?,
        val playing: Boolean,
        val duration: Long,
        val index: Int,
        val count: Int,
        val repeat: Int,
        val shuffle: Boolean,
    )

    /** What's playing (or paused), or null when nothing is loaded. */
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set

    var controller: MediaController? = null
        private set
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var artworkFor: Any? = null
    private var artwork: Bitmap? = null

    /** Connects (once) to the playback service, then runs [then] with the controller. */
    fun connect(context: Context, then: (MediaController) -> Unit = {}) {
        controller?.let { then(it); return }
        pending += then
        if (connecting) return
        connecting = true
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update(player)
            })
            update(connected)
            pending.toList().forEach { it(connected) }
            pending.clear()
        }, ContextCompat.getMainExecutor(app))
    }

    /** Plays [files] as a queue, starting at [index]. [names] are shown until the files' own tags load. */
    fun play(context: Context, files: List<File>, index: Int, names: List<String> = files.map { it.name }) =
        playUris(context, files.map { Uri.fromFile(it) to it.path }, index, names)

    /**
     * Plays a queue of addresses: `file://` on the phone, or `ssh://` on a server (streamed).
     * Each is (address, id); [names] are shown until the files' own tags load.
     */
    fun playUris(context: Context, sources: List<Pair<Uri, String>>, index: Int, names: List<String>) {
        connect(context) { player ->
            val items = sources.mapIndexed { i, (uri, id) ->
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(names.getOrElse(i) { id.substringAfterLast('/') }.substringBeforeLast('.')).build())
                    .build()
            }
            player.setMediaItems(items, index.coerceIn(0, items.lastIndex), 0L)
            player.prepare()
            player.play()
        }
    }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.let { if (it.currentPosition > 3_000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun position(): Long = controller?.currentPosition ?: 0L

    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off → all → one → off. */
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Stops and clears the queue (the mini player's close button). */
    fun stop() = controller?.let {
        it.stop()
        it.clearMediaItems()
    }

    private fun update(player: Player) {
        if (player.mediaItemCount == 0) {
            nowPlaying = null
            return
        }
        val metadata = player.mediaMetadata
        // Embedded cover art, decoded once per track.
        val art = metadata.artworkData
        if (art !== artworkFor) {
            artworkFor = art
            artwork = art?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        nowPlaying = NowPlaying(
            title = metadata.title?.toString() ?: player.currentMediaItem?.mediaId?.substringAfterLast('/') ?: "Audio",
            artist = (metadata.artist ?: metadata.albumArtist)?.toString(),
            artwork = artwork,
            playing = player.isPlaying,
            duration = player.duration.coerceAtLeast(0L),
            index = player.currentMediaItemIndex,
            count = player.mediaItemCount,
            repeat = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
        )
    }
}
