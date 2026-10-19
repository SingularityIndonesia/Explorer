package com.singularity_universe.explorer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.singularity_universe.explorer.data.ThumbnailFetcher
import com.singularity_universe.explorer.data.ThumbnailKeyer

class ExplorerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ThumbnailKeyer())
            add(ThumbnailFetcher.Factory())
        }
        .crossfade(true)
        .build()
}
