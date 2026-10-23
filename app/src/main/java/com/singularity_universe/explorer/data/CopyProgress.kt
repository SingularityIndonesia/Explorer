package com.singularity_universe.explorer.data

import java.io.File
import java.io.IOException

/**
 * Bytes copied so far, shared by every way of copying (local, SFTP, ADB), and a way to stop in the
 * middle of a file. Written from I/O threads, read by the UI.
 */
open class CopyProgress {
    @Volatile var bytes = 0L
        private set

    @Volatile var cancelled = false

    open fun add(count: Long) {
        bytes += count
    }

    /** Throws [CopyCancelledException] once the user cancelled. */
    fun check() {
        if (cancelled) throw CopyCancelledException()
    }
}

/** A copy stopped because the user cancelled it (not an error). */
class CopyCancelledException : IOException("Cancelled")

/** Copying files and folders on this phone, reporting to a [CopyProgress]. */
object FileCopier {
    private const val BUFFER = 256 * 1024

    /**
     * Copies [source] (a file or a whole folder) to [target]. When cancelled, the file being copied
     * is deleted and [CopyCancelledException] thrown; files already copied stay.
     */
    fun copy(source: File, target: File, progress: CopyProgress?) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) throw IOException("Couldn't create ${target.name}")
            source.listFiles()?.forEach { copy(it, File(target, it.name), progress) }
            target.setLastModified(source.lastModified())
            return
        }
        progress?.check()
        target.parentFile?.mkdirs()
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        progress?.add(read.toLong())
                        progress?.check()
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        target.setLastModified(source.lastModified())
    }

    /** Total size of a file or folder. */
    fun sizeOf(file: File): Long = if (file.isDirectory) file.listFiles()?.sumOf(::sizeOf) ?: 0L else file.length()
}
