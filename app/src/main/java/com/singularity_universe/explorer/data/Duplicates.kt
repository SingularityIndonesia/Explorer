package com.singularity_universe.explorer.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds files with identical content, without reading every file in full: only files of equal
 * size can match; among those, a quick hash of their start and end narrows it down; a full
 * SHA-256 then confirms. Plain Java, so it's unit-tested on the JVM.
 */
object Duplicates {
    private const val SAMPLE = 64 * 1024

    /**
     * Groups of identical files (each group sorted oldest first, so the original comes first).
     * Files under [minSize] bytes are ignored. [onProgress] gets 0..1 while hashing.
     */
    fun find(files: List<File>, minSize: Long = 1, onProgress: (Float) -> Unit = {}): List<List<File>> {
        val bySize = files.asSequence()
            .filter { it.isFile && it.length() >= minSize }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
            .groupBy { it.length() }
            .values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }.coerceAtLeast(1)
        var done = 0
        val groups = ArrayList<List<File>>()
        for (sameSize in bySize) {
            val byQuick = sameSize.groupBy { quickHash(it) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (candidates in byQuick) {
                // Small files: the quick hash already read all of them.
                val confirmed = if (candidates.first().length() <= 2L * SAMPLE) listOf(candidates)
                else candidates.groupBy { fullHash(it) }.filterKeys { it != null }.values.filter { it.size > 1 }
                confirmed.forEach { group -> groups += group.sortedBy { it.lastModified() } }
            }
            done += sameSize.size
            onProgress(done.toFloat() / total)
        }
        // Biggest savings first.
        return groups.sortedByDescending { group -> group.first().length() * (group.size - 1) }
    }

    private fun quickHash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val buffer = ByteArray(SAMPLE)
            val head = raf.read(buffer, 0, minOf(SAMPLE.toLong(), length).toInt()).coerceAtLeast(0)
            digest.update(buffer, 0, head)
            if (length > SAMPLE) {
                val tailStart = maxOf(SAMPLE.toLong(), length - SAMPLE)
                raf.seek(tailStart)
                val tail = raf.read(buffer, 0, (length - tailStart).toInt()).coerceAtLeast(0)
                digest.update(buffer, 0, tail)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun fullHash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
