package com.singularity_universe.explorer.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the queue, the track and the position, so reopening Explorer offers to carry on where
 * the music stopped (paused; play continues). Cleared when the queue is stopped.
 */
internal class ResumeState(context: Context) {
    private val prefs = context.getSharedPreferences("explorer", Context.MODE_PRIVATE)
    private var lastSaved = 0L

    fun save(player: Player) {
        if (player.mediaItemCount == 0) {
            prefs.edit().remove(KEY).apply()
            return
        }
        // Positions change constantly: saved at most every few seconds, and on every pause or skip.
        val now = System.currentTimeMillis()
        if (player.isPlaying && now - lastSaved < 5_000) return
        lastSaved = now
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            items.put(
                JSONObject()
                    .put("uri", (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString() ?: continue)
                    .put("id", item.mediaId)
                    .put("title", item.mediaMetadata.title?.toString() ?: "")
            )
        }
        prefs.edit().putString(
            KEY,
            JSONObject()
                .put("items", items)
                .put("index", player.currentMediaItemIndex)
                .put("position", player.currentPosition.coerceAtLeast(0))
                .toString(),
        ).apply()
    }

    /** Loads the saved queue paused at its position (prepared only when play is pressed). */
    fun restore(player: Player) {
        val saved = runCatching { JSONObject(prefs.getString(KEY, null) ?: return) }.getOrNull() ?: return
        val array = saved.optJSONArray("items") ?: return
        val items = (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val uri = Uri.parse(o.getString("uri"))
            // Files on the phone that were deleted meanwhile are left out.
            if (uri.scheme == "file" && uri.path?.let { File(it).exists() } != true) return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(o.optString("id"))
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(o.optString("title")).build())
                .build()
        }
        if (items.isEmpty()) return
        val index = saved.optInt("index").coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, saved.optLong("position"))
    }

    private companion object {
        const val KEY = "audio_resume"
    }
}
