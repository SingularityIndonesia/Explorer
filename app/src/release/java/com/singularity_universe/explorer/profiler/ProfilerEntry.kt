package com.singularity_universe.explorer.profiler

import android.content.Context

/** Release builds have no profiler: Settings hides its section. The debug build has the real one. */
object ProfilerEntry {
    const val available = false
    val recording: Boolean get() = false
    fun setRecording(on: Boolean) = Unit
    fun open(context: Context) = Unit
}
