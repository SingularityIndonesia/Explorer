package com.singularity_universe.explorer.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException

/**
 * Reading and extracting .zip, .tar (plain, .gz, .bz2, .xz), .7z and .gz files, and creating zips.
 * Plain Java, so it's unit-tested on the JVM.
 */
object Archives {
    enum class Format { Zip, Tar, TarGz, TarBz2, TarXz, SevenZ, Gzip }

    /** One file or folder inside an archive; [path] uses "/" and has no trailing slash. */
    class Entry(val path: String, val isDirectory: Boolean, val size: Long, val time: Long) {
        val name: String get() = path.substringAfterLast('/')
        val parent: String get() = path.substringBeforeLast('/', "")
    }

    /** The archive is encrypted: ask for a password ([wrong] when the one given didn't work). */
    class PasswordRequiredException(val wrong: Boolean) :
        IOException(if (wrong) "Wrong password" else "This archive is password-protected")

    fun formatOf(name: String): Format? {
        val n = name.lowercase()
        return when {
            n.endsWith(".zip") -> Format.Zip
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Format.TarGz
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> Format.TarBz2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Format.TarXz
            n.endsWith(".tar") -> Format.Tar
            n.endsWith(".7z") -> Format.SevenZ
            n.endsWith(".gz") -> Format.Gzip
            else -> null
        }
    }

    /** The name an archive's contents get when extracted into a folder: "photos.tar.gz" → "photos". */
    fun baseName(name: String): String {
        val lower = name.lowercase()
        val suffix = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar", ".zip", ".7z", ".gz")
            .firstOrNull { lower.endsWith(it) } ?: return name
        return name.dropLast(suffix.length).ifEmpty { name }
    }

    // ---- Reading ----

    /** Every entry, plus folders only implied by the paths of the files in them. */
    @Throws(IOException::class)
    fun list(file: File, password: String? = null): List<Entry> {
        val entries = LinkedHashMap<String, Entry>()
        visit(file, password, readData = false) { entry, _ ->
            entries[entry.path] = entry
            var parent = entry.parent
            while (parent.isNotEmpty() && parent !in entries) {
                entries[parent] = Entry(parent, true, 0, entry.time)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return entries.values.toList()
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
    fun extract(file: File, target: File, only: Collection<String>? = null, base: String = "", password: String? = null): List<File> {
        val root = target.canonicalFile
        root.mkdirs()
        val prefix = if (base.isEmpty()) "" else "$base/"
        val written = ArrayList<File>()
        visit(file, password, readData = true) { entry, data ->
            val path = entry.path
            if (only != null && only.none { path == it || path.startsWith("$it/") }) return@visit
            if (prefix.isNotEmpty() && !path.startsWith(prefix)) return@visit
            val relative = path.removePrefix(prefix)
            if (relative.isEmpty()) return@visit
            val out = File(root, relative).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw IOException("Refusing to extract \"$path\" outside the folder")
            if (entry.isDirectory) {
                out.mkdirs()
                return@visit
            }
            out.parentFile?.mkdirs()
            BufferedOutputStream(out.outputStream()).use { data!!.copyTo(it) }
            if (entry.time > 0) out.setLastModified(entry.time)
            written += out
        }
        return written
    }

    /** Extracts one file to exactly [target]. */
    @Throws(IOException::class)
    fun extractEntry(file: File, path: String, target: File, password: String? = null) {
        var found = false
        visit(file, password, readData = true) { entry, data ->
            if (found || entry.isDirectory || entry.path != path) return@visit
            target.parentFile?.mkdirs()
            BufferedOutputStream(target.outputStream()).use { data!!.copyTo(it) }
            found = true
        }
        if (!found) throw IOException("$path isn't in the archive")
    }

    /**
     * Calls [visitor] for each entry, with its data positioned to read when [readData] (null for
     * folders, or when only listing).
     */
    private fun visit(file: File, password: String?, readData: Boolean, visitor: (Entry, InputStream?) -> Unit) {
        when (formatOf(file.name) ?: Format.Zip) {
            Format.Zip -> visitZip(file, password, readData, visitor)
            Format.SevenZ -> visitSevenZ(file, password, readData, visitor)
            Format.Gzip -> {
                // One compressed file: its name without ".gz".
                val entry = Entry(file.name.dropLast(3).ifEmpty { "file" }, false, 0, file.lastModified())
                if (readData) GzipCompressorInputStream(BufferedInputStream(file.inputStream())).use { visitor(entry, it) }
                else visitor(entry, null)
            }
            else -> visitTar(file, readData, visitor)
        }
    }

    private fun visitTar(file: File, readData: Boolean, visitor: (Entry, InputStream?) -> Unit) {
        val raw = BufferedInputStream(file.inputStream())
        val stream = when (formatOf(file.name)) {
            Format.TarGz -> GzipCompressorInputStream(raw)
            Format.TarBz2 -> BZip2CompressorInputStream(raw)
            Format.TarXz -> XZCompressorInputStream(raw)
            else -> raw
        }
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                // Links could point anywhere on the phone: they're not extracted.
                if (e.isSymbolicLink || e.isLink) continue
                val path = clean(e.name) ?: continue
                visitor(Entry(path, e.isDirectory, if (e.isDirectory) 0 else e.size, e.modTime.time), if (readData && !e.isDirectory) tar else null)
            }
        }
    }

    private fun visitSevenZ(file: File, password: String?, readData: Boolean, visitor: (Entry, InputStream?) -> Unit) {
        val sevenZ = try {
            SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password.toCharArray()) }.get()
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw PasswordRequiredException(wrong = false)
        } catch (e: IOException) {
            // An encrypted header with a wrong password reads as garbage.
            if (password != null) throw PasswordRequiredException(wrong = true) else throw e
        }
        sevenZ.use { archive ->
            while (true) {
                val e = try {
                    archive.nextEntry ?: break
                } catch (ex: org.apache.commons.compress.PasswordRequiredException) {
                    throw PasswordRequiredException(wrong = false)
                }
                val path = clean(e.name) ?: continue
                val time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L
                val entry = Entry(path, e.isDirectory, if (e.isDirectory) 0 else e.size, time)
                if (readData && !e.isDirectory) {
                    try {
                        visitor(entry, archive.getInputStream(e))
                    } catch (ex: org.apache.commons.compress.PasswordRequiredException) {
                        throw PasswordRequiredException(wrong = false)
                    } catch (ex: IOException) {
                        if (password != null && ex !is PasswordRequiredException && ex.message?.contains("checksum", true) == true) throw PasswordRequiredException(wrong = true)
                        throw ex
                    }
                } else {
                    visitor(entry, null)
                }
            }
        }
    }

    private fun visitZip(file: File, password: String?, readData: Boolean, visitor: (Entry, InputStream?) -> Unit) {
        // Encrypted zips (ZipCrypto or AES) go through zip4j: Java's own zip support can't decrypt them,
        // and newer Java versions won't even list them. Names are readable without the password.
        val encrypted = runCatching { Zip4jFile(file).isEncrypted }.getOrDefault(false)
        if (encrypted) {
            if (readData && password.isNullOrEmpty()) throw PasswordRequiredException(wrong = false)
            val zip = if (password.isNullOrEmpty()) Zip4jFile(file) else Zip4jFile(file, password.toCharArray())
            for (header in zip.fileHeaders) {
                val path = clean(header.fileName) ?: continue
                val entry = Entry(path, header.isDirectory, header.uncompressedSize, header.lastModifiedTimeEpoch)
                if (header.isDirectory || !readData) {
                    visitor(entry, null)
                    continue
                }
                try {
                    zip.getInputStream(header).use { visitor(entry, it) }
                } catch (e: Zip4jException) {
                    if (e.type == Zip4jException.Type.WRONG_PASSWORD || e.message?.contains("password", true) == true) {
                        throw PasswordRequiredException(wrong = true)
                    }
                    throw IOException(e.message, e)
                }
            }
            return
        }
        openZip(file).use { zip ->
            for (e in zip.entries()) {
                val path = clean(e.name) ?: continue
                val entry = Entry(path, e.isDirectory, if (e.isDirectory) 0 else e.size.coerceAtLeast(0), e.time.coerceAtLeast(0))
                if (readData && !e.isDirectory) {
                    try {
                        zip.getInputStream(e).use { visitor(entry, it) }
                    } catch (ex: ZipException) {
                        if (ex.message?.contains("encrypted", true) == true) throw PasswordRequiredException(wrong = false)
                        throw ex
                    }
                } else {
                    visitor(entry, null)
                }
            }
        }
    }

    /** Opens a zip, trying UTF-8 names first and then the legacy DOS encoding older tools wrote. */
    private fun openZip(file: File): ZipFile =
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

    // ---- Creating ----

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

    /** "./a//b/" → "a/b"; null for empty names. ".." is kept, so extraction can refuse it. */
    private fun clean(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }
}
