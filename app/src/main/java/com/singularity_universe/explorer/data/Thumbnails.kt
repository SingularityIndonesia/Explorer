package com.singularity_universe.explorer.data

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import java.io.File

/**
 * Coil model for a small preview of an image or video.
 *
 * Loads MediaStore's cached thumbnail when the file is indexed (cheap — this is what the system
 * gallery uses), instead of decoding the full original file.
 */
data class Thumbnail(
    val path: String,
    val mediaUri: Uri?,
    val isVideo: Boolean,
    val lastModified: Long,
    val sizePx: Int = 320,
)

fun FileItem.thumbnail(sizePx: Int = 320) = Thumbnail(path, mediaUri, kind == FileKind.Video, lastModified, sizePx)

class ThumbnailFetcher(private val data: Thumbnail, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val size = Size(data.sizePx, data.sizePx)
        val bitmap: Bitmap = data.mediaUri
            ?.let { runCatching { options.context.contentResolver.loadThumbnail(it, size, null) }.getOrNull() }
            ?: if (data.isVideo) {
                ThumbnailUtils.createVideoThumbnail(File(data.path), size, null)
            } else {
                ThumbnailUtils.createImageThumbnail(File(data.path), size, null)
            }
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Thumbnail> {
        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader) = ThumbnailFetcher(data, options)
    }
}

class ThumbnailKeyer : Keyer<Thumbnail> {
    override fun key(data: Thumbnail, options: Options) = "thumb:${data.path}:${data.lastModified}:${data.sizePx}"
}
