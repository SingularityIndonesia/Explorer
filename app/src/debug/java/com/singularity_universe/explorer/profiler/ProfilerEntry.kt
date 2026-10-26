package com.singularity_universe.explorer.profiler

import android.content.Context
import android.content.Intent

/** What Settings uses to control the profiler; release builds have a stand-in that does nothing. */
object ProfilerEntry {
    const val available = true
    val recording: Boolean get() = CallTrace.recording

    fun setRecording(on: Boolean) = if (on) Profiler.start() else Profiler.stop()

    fun open(context: Context) = context.startActivity(Intent(context, ProfilerActivity::class.java))
}
