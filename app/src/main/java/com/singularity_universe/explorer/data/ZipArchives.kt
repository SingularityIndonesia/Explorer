package com.singularity_universe.explorer.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Reading, extracting and creating .zip files. Plain Java, so it's unit-tested on the JVM. */
object ZipArchives {
    /** One file or folder inside an archive; [path] uses "/" and has no trailing slash. */
    class Entry(val path: String, val isDirectory: Boolean, val size: Long, val time: Long) {
        val name: String get() = path.substringAfterLast('/')
        val parent: String get() = path.substringBeforeLast('/', "")
    }

    /** Opens a zip, trying UTF-8 names first and then the legacy DOS encoding older tools wrote. */
    private fun open(file: File): ZipFile =
        try {
            ZipFile(file, Charsets.UTF_8).also { zip -> zip.entries().toList() }
        } catch (e: Exception) {
            // Names that aren't valid UTF-8: Android reports "malformed input" (IllegalArgumentException),
            // newer Java "bad entry name" (ZipException). A truly broken file fails both ways.
            if (e !is IllegalArgumentException && e !is ZipException) throw e
            try {
                ZipFile(file, Charset.forName("IBM437"))
            } catch (legacy: Exception) {
                throw e
            }
        }

    /** Every entry, plus folders that are only implied by the paths of the files in them. */
    @Throws(IOException::class)
    fun list(file: File): List<Entry> = open(file).use { zip ->
        val entries = LinkedHashMap<String, Entry>()
        for (e in zip.entries()) {
            val path = clean(e.name) ?: continue
            entries[path] = Entry(path, e.isDirectory, if (e.isDirectory) 0 else e.size.coerceAtLeast(0), e.time.coerceAtLeast(0))
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && parent !in entries) {
                entries[parent] = Entry(parent, true, 0, e.time.coerceAtLeast(0))
                parent = parent.substringBeforeLast('/', "")
            }
        }
        entries.values.toList()
    }

    /** The entries directly inside [folder] ("" for the top level). */
    fun children(entries: List<Entry>, folder: String): List<Entry> = entries.filter { it.parent == folder }

    /** How many entries are directly inside each folder. */
    fun childCounts(entries: List<Entry>): Map<String, Int> = entries.groupingBy { it.parent }.eachCount()

    /**
     * Extracts into [target] (created if needed). With [only], just those entries (files, or
     * folders with everything in them). Paths are taken relative to [base], so extracting
     * "docs/a.txt" with base "docs" gives target/a.txt. Entries that would land outside [target]
     * (a "zip slip" attack, e.g. "../../evil") are refused. Returns the files written.
     */
    @Throws(IOException::class)
    fun extract(file: File, target: File, only: Collection<String>? = null, base: String = ""): List<File> = open(file).use { zip ->
        val root = target.canonicalFile
        root.mkdirs()
        val prefix = if (base.isEmpty()) "" else "$base/"
        val written = ArrayList<File>()
        for (e in zip.entries()) {
            val path = clean(e.name) ?: continue
            if (only != null && only.none { path == it || path.startsWith("$it/") }) continue
            if (!path.startsWith(prefix) && prefix.isNotEmpty()) continue
            val relative = path.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val out = File(root, relative).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw IOException("Refusing to extract \"${e.name}\" outside the folder")
            if (e.isDirectory) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            try {
                zip.getInputStream(e).use { input -> BufferedOutputStream(out.outputStream()).use { input.copyTo(it) } }
            } catch (ex: ZipException) {
                throw IOException(if (ex.message?.contains("encrypted", true) == true) "This zip is password-protected" else ex.message, ex)
            }
            if (e.time > 0) out.setLastModified(e.time)
            written += out
        }
        written
    }

    /** Extracts one file to exactly [target]. */
    @Throws(IOException::class)
    fun extractEntry(file: File, path: String, target: File) {
        open(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { clean(it.name) == path } ?: throw IOException("$path isn't in the archive")
            target.parentFile?.mkdirs()
            try {
                zip.getInputStream(entry).use { input -> BufferedOutputStream(target.outputStream()).use { input.copyTo(it) } }
            } catch (ex: ZipException) {
                throw IOException(if (ex.message?.contains("encrypted", true) == true) "This zip is password-protected" else ex.message, ex)
            }
        }
    }

    /** Zips [sources] (files and whole folders) into [target], each at the top level of the archive. */
    @Throws(IOException::class)
    fun compress(sources: List<File>, target: File) {
        val targetCanonical = target.canonicalFile
        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
            fun add(file: File, name: String) {
                if (file.canonicalFile == targetCanonical) return // Don't zip the zip being written.
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$name/").apply { time = file.lastModified() })
                    zip.closeEntry()
                    file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") }
                } else {
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    BufferedInputStream(file.inputStream()).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            sources.forEach { add(it, it.name) }
        }
    }

    /** "./a//b/" → "a/b"; null for empty names or Windows-style absolute paths. */
    private fun clean(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }
}
