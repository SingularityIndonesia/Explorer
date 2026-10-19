package com.singularity_universe.explorer.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A piece of screen data that is loaded once and kept in memory, so revisiting a screen shows
 * it instantly. It reloads only when [refresh] is called (pull-to-refresh), or after a file
 * operation marks it stale via [invalidate].
 */
class Resource<T>(private val scope: CoroutineScope, private val loader: suspend () -> T) {
    var value by mutableStateOf<T?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** The last load failure, cleared by the next successful load. */
    var error by mutableStateOf<Throwable?>(null)
        private set

    /** True while reloading data that is already on screen — drives the pull-to-refresh spinner. */
    val refreshing: Boolean get() = loading && value != null

    private var stale = true
    private var job: Job? = null
    private var observers = 0

    fun refresh() {
        stale = false
        job?.cancel()
        job = scope.launch {
            loading = true
            try {
                value = loader()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the previous data, and let the screen explain what went wrong.
                Log.w("Explorer", "Loading failed", e)
                error = e
            } finally {
                loading = false
            }
        }
    }

    /** Marks the data outdated: reloads now if a screen shows it, otherwise when one next does. */
    fun invalidate() {
        stale = true
        if (observers > 0) refresh()
    }

    internal fun attach() {
        observers++
        if (stale && job?.isActive != true) refresh()
    }

    internal fun detach() {
        observers--
    }
}

/** Shows this resource on the current screen, loading it the first time it's needed. */
@Composable
fun <T> Resource<T>.observe(): Resource<T> {
    DisposableEffect(this) {
        attach()
        onDispose { detach() }
    }
    return this
}
