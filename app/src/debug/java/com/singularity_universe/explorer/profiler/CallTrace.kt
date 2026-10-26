package com.singularity_universe.explorer.profiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives every traced function's start and end (debug builds add the calls to the app's classes,
 * see build-logic/CallTracePlugin) and keeps, while recording:
 * - per function: how many calls, total, self (minus traced callees), average and longest time,
 *   and when it was first and last called;
 * - the most recent [TIMELINE_SIZE] calls in order, with thread and nesting depth.
 *
 * Exits are matched by name, so turning recording on or off in the middle of a call never leaves
 * the bookkeeping unbalanced: an end without a recorded start is ignored.
 */
object CallTrace {
    @Volatile
    @JvmField
    var recording = false

    /** Set once anything was recorded: until then, ends have nothing to match and return at once. */
    @Volatile
    private var used = false

    /** System.nanoTime() when recording started; times are shown relative to it. */
    @Volatile
    var startedAt = 0L
        private set

    private val stats = ConcurrentHashMap<String, FunctionStats>()

    private val frames = object : ThreadLocal<Frames>() {
        override fun initialValue() = Frames(Thread.currentThread().name)
    }

    // Recent calls, as a ring buffer of parallel arrays (no allocation per call).
    private const val TIMELINE_SIZE = 50_000
    private val callNames = arrayOfNulls<String>(TIMELINE_SIZE)
    private val callThreads = arrayOfNulls<String>(TIMELINE_SIZE)
    private val callStarts = LongArray(TIMELINE_SIZE)
    private val callDurations = LongArray(TIMELINE_SIZE)
    private val callDepths = IntArray(TIMELINE_SIZE)
    private val callCount = AtomicLong()

    fun start() {
        if (recording) return
        if (startedAt == 0L) startedAt = System.nanoTime()
        used = true
        recording = true
    }

    fun stop() {
        recording = false
    }

    fun clear() {
        stats.clear()
        callCount.set(0)
        startedAt = if (recording) System.nanoTime() else 0L
    }

    @JvmStatic
    fun enter(name: String) {
        if (!recording) return
        frames.get()!!.push(name, System.nanoTime())
    }

    @JvmStatic
    fun exit(name: String) {
        if (!used) return
        val now = System.nanoTime()
        val frames = frames.get()!!
        val depth = frames.depth
        if (depth == 0 || frames.names[depth - 1] !== name) return
        val index = depth - 1
        val start = frames.starts[index]
        val duration = now - start
        val self = duration - frames.children[index]
        frames.names[index] = null
        frames.depth = index
        if (index > 0) frames.children[index - 1] += duration

        stats.getOrPut(name) { FunctionStats(name) }.add(start, duration, self)
        val slot = (callCount.getAndIncrement() % TIMELINE_SIZE).toInt()
        callNames[slot] = name
        callThreads[slot] = frames.thread
        callStarts[slot] = start
        callDurations[slot] = duration
        callDepths[slot] = index
    }

    /** All functions called since recording started (or the last [clear]). */
    fun functions(): List<FunctionSnapshot> = stats.values.map { it.snapshot() }

    /** The recent calls, newest first, at most [limit]. */
    fun recentCalls(limit: Int = TIMELINE_SIZE, matching: (String) -> Boolean = { true }): List<Call> {
        val total = callCount.get()
        val available = minOf(total, TIMELINE_SIZE.toLong()).toInt()
        val result = ArrayList<Call>(minOf(limit, available))
        for (k in 0 until available) {
            if (result.size >= limit) break
            val slot = ((total - 1 - k) % TIMELINE_SIZE).toInt()
            val name = callNames[slot] ?: continue
            if (!matching(name)) continue
            result += Call(name, callThreads[slot].orEmpty(), callStarts[slot], callDurations[slot], callDepths[slot])
        }
        return result
    }

    /** Calls recorded in total, including those older than the timeline keeps. */
    val totalCalls: Long get() = callCount.get()

    class Call(val name: String, val thread: String, val startNanos: Long, val durationNanos: Long, val depth: Int)

    class FunctionSnapshot(
        val name: String,
        val calls: Long,
        val totalNanos: Long,
        val selfNanos: Long,
        val maxNanos: Long,
        val firstNanos: Long,
        val lastNanos: Long,
    ) {
        val averageNanos: Long get() = if (calls == 0L) 0 else totalNanos / calls
    }

    private class FunctionStats(val name: String) {
        private var calls = 0L
        private var total = 0L
        private var self = 0L
        private var max = 0L
        private var first = Long.MAX_VALUE
        private var last = 0L

        @Synchronized
        fun add(start: Long, duration: Long, selfTime: Long) {
            calls++
            total += duration
            self += selfTime
            if (duration > max) max = duration
            if (start < first) first = start
            if (start > last) last = start
        }

        @Synchronized
        fun snapshot() = FunctionSnapshot(name, calls, total, self, max, first, last)
    }

    /** One thread's open calls. */
    private class Frames(val thread: String) {
        var names = arrayOfNulls<String>(64)
        var starts = LongArray(64)
        var children = LongArray(64)
        var depth = 0

        fun push(name: String, start: Long) {
            if (depth == names.size) {
                if (depth >= MAX_DEPTH) return // Runaway recursion: stop tracking deeper calls.
                names = names.copyOf(depth * 2)
                starts = starts.copyOf(depth * 2)
                children = children.copyOf(depth * 2)
            }
            names[depth] = name
            starts[depth] = start
            children[depth] = 0
            depth++
        }
    }

    private const val MAX_DEPTH = 4096
}
