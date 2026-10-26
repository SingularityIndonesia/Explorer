package com.singularity_universe.explorer.profiler

import android.os.Debug
import java.io.File

/**
 * Samples the app's memory once a second while it runs: the Java heap (objects the app made), the
 * native heap (bitmaps, codecs, C libraries), the total the system charges the app (PSS) and the
 * garbage collections so far.
 */
object MemorySampler {
    class Sample(
        val atNanos: Long,
        val javaUsed: Long,
        val javaMax: Long,
        val nativeUsed: Long,
        /** Total memory charged to the app, including code and graphics (read every few seconds). */
        val pss: Long,
        val gcCount: Long,
    )

    private const val KEEP = 600
    private val samples = ArrayDeque<Sample>()
    private var thread: Thread? = null

    @Synchronized
    fun samples(): List<Sample> = samples.toList()

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({
            var tick = 0
            var pss = Debug.getPss() * 1024
            while (true) {
                // PSS takes a few milliseconds to read: every fifth sample is enough.
                if (tick++ % 5 == 0) pss = Debug.getPss() * 1024
                add(current(pss))
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "Profiler memory").apply {
            isDaemon = true
            start()
        }
    }

    fun current(pss: Long = Debug.getPss() * 1024): Sample {
        val runtime = Runtime.getRuntime()
        return Sample(
            atNanos = System.nanoTime(),
            javaUsed = runtime.totalMemory() - runtime.freeMemory(),
            javaMax = runtime.maxMemory(),
            nativeUsed = Debug.getNativeHeapAllocatedSize(),
            pss = pss,
            gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0,
        )
    }

    @Synchronized
    private fun add(sample: Sample) {
        if (samples.size == KEEP) samples.removeFirst()
        samples.addLast(sample)
    }

    /** Writes a heap dump Android Studio can open (Profiler › Load from file). */
    fun dumpHeap(directory: File): File {
        directory.mkdirs()
        val file = File(directory, "explorer-heap-${System.currentTimeMillis()}.hprof")
        Debug.dumpHprofData(file.path)
        return file
    }
}
