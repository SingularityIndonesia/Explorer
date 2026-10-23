package com.singularity_universe.explorer.data

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivesTest {
    private val dir = Files.createTempDirectory("zips").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun zip(name: String, charset: Charset = Charsets.UTF_8, vararg files: Pair<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream(), charset).use { out ->
            files.forEach { (path, text) ->
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun listsImpliedFolders() {
        // No folder entries at all: "docs" and "docs/old" exist only through their files.
        val file = zip("a.zip", files = arrayOf("readme.txt" to "hi", "docs/old/report.txt" to "x", "docs/plan.md" to "y"))
        val entries = Archives.list(file)
        assertEquals(listOf("docs", "readme.txt"), Archives.children(entries, "").map { it.path }.sorted())
        assertTrue(entries.first { it.path == "docs" }.isDirectory)
        assertEquals(listOf("docs/old", "docs/plan.md"), Archives.children(entries, "docs").map { it.path }.sorted())
        assertEquals(2, Archives.childCounts(entries)["docs"])
        assertEquals(2L, entries.first { it.path == "readme.txt" }.size)
    }

    @Test
    fun unicodeAndLegacyNames() {
        val utf8 = zip("u.zip", files = arrayOf("café/naïve 日本.txt" to "ok"))
        assertEquals("café/naïve 日本.txt", Archives.list(utf8).first { !it.isDirectory }.path)
        // Old Windows tools wrote names in the DOS code page, without the UTF-8 flag.
        val legacy = zip("l.zip", Charset.forName("IBM437"), "café.txt" to "ok")
        assertEquals("café.txt", Archives.list(legacy).single().path)
        val out = File(dir, "legacy-out")
        Archives.extract(legacy, out)
        assertEquals("ok", File(out, "café.txt").readText())
    }

    @Test
    fun refusesZipSlip() {
        val evil = zip("evil.zip", files = arrayOf("fine.txt" to "a", "../../escaped.txt" to "pwned"))
        val out = File(dir, "out")
        assertThrows(IOException::class.java) { Archives.extract(evil, out) }
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
    }

    @Test
    fun extractsSelectionRelativeToFolder() {
        val file = zip("s.zip", files = arrayOf("docs/a.txt" to "A", "docs/sub/b.txt" to "B", "other.txt" to "C"))
        val out = File(dir, "picked")
        val written = Archives.extract(file, out, only = listOf("docs/sub", "docs/a.txt"), base = "docs")
        assertEquals(setOf("a.txt", "sub/b.txt"), written.map { it.relativeTo(out.canonicalFile).path.replace('\\', '/') }.toSet())
        assertEquals("B", File(out, "sub/b.txt").readText())
        assertFalse(File(out, "other.txt").exists())
    }

    @Test
    fun compressRoundTrip() {
        val src = File(dir, "project").apply { mkdirs() }
        File(src, "main.kt").writeText("fun main() {}")
        File(src, "res/values").mkdirs()
        File(src, "res/values/strings.xml").writeText("<resources/>")
        File(src, "empty").mkdirs()
        val note = File(dir, "note.txt").apply { writeText("note") }
        val target = File(dir, "out.zip")
        Archives.compress(listOf(src, note), target)
        val paths = Archives.list(target).map { it.path }.toSet()
        assertTrue(paths.containsAll(listOf("project", "project/main.kt", "project/res/values/strings.xml", "project/empty", "note.txt")))
        val out = File(dir, "unzipped")
        Archives.extract(target, out)
        assertEquals("<resources/>", File(out, "project/res/values/strings.xml").readText())
        assertTrue(File(out, "project/empty").isDirectory)
        assertEquals("note", File(out, "note.txt").readText())
    }

    @Test
    fun compressingAFolderDoesNotIncludeTheZipBeingWritten() {
        val src = File(dir, "photos").apply { mkdirs() }
        File(src, "a.jpg").writeText("jpeg")
        val target = File(src, "photos.zip")
        Archives.compress(listOf(src), target)
        assertEquals(setOf("photos", "photos/a.jpg"), Archives.list(target).map { it.path }.toSet())
    }

    @Test
    fun extractsOneEntry() {
        val file = zip("one.zip", files = arrayOf("a/b/c.txt" to "deep"))
        val target = File(dir, "c.txt")
        Archives.extractEntry(file, "a/b/c.txt", target)
        assertEquals("deep", target.readText())
    }

    // ---- Other formats ----

    private fun tar(name: String, wrap: (java.io.OutputStream) -> java.io.OutputStream, vararg files: Pair<String, String>): File {
        val file = File(dir, name)
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(wrap(file.outputStream())).use { out ->
            out.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
            files.forEach { (path, text) ->
                val bytes = text.toByteArray()
                out.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun tarInEveryCompression() {
        val files = arrayOf("project/src/main.kt" to "fun main() {}", "project/README.md" to "# hi")
        val variants = listOf(
            tar("p.tar", { it }, *files),
            tar("p.tar.gz", { org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(it) }, *files),
            tar("p.tbz2", { org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(it) }, *files),
            tar("p.tar.xz", { org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(it) }, *files),
        )
        for (archive in variants) {
            val entries = Archives.list(archive)
            assertEquals(archive.name, listOf("project"), Archives.children(entries, "").map { it.path })
            assertEquals(archive.name, setOf("project/README.md", "project/src"), Archives.children(entries, "project").map { it.path }.toSet())
            val out = File(dir, "out-${archive.name}")
            Archives.extract(archive, out)
            assertEquals(archive.name, "fun main() {}", File(out, "project/src/main.kt").readText())
            val one = File(dir, "one-${archive.name}.md")
            Archives.extractEntry(archive, "project/README.md", one)
            assertEquals("# hi", one.readText())
        }
        assertEquals("p", Archives.baseName("p.tar.gz"))
        assertEquals(Archives.Format.TarBz2, Archives.formatOf("p.tbz2"))
    }

    @Test
    fun tarRefusesEscapesAndSkipsLinks() {
        val evil = tar("evil.tar", { it }, "ok.txt" to "fine", "../../escape.txt" to "pwned")
        assertThrows(IOException::class.java) { Archives.extract(evil, File(dir, "x")) }
        assertFalse(File(dir, "escape.txt").exists())
        val withLink = File(dir, "link.tar")
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(withLink.outputStream()).use { out ->
            out.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry("passwd", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK).apply { linkName = "/etc/passwd" })
            out.closeArchiveEntry()
        }
        assertTrue("links aren't listed or extracted", Archives.list(withLink).isEmpty())
    }

    @Test
    fun singleGzipFile() {
        val gz = File(dir, "notes.txt.gz")
        org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(gz.outputStream()).use { it.write("gzipped".toByteArray()) }
        assertEquals(listOf("notes.txt"), Archives.list(gz).map { it.path })
        val out = File(dir, "g")
        Archives.extract(gz, out)
        assertEquals("gzipped", File(out, "notes.txt").readText())
    }

    @Test
    fun sevenZip() {
        val file = File(dir, "a.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(file).use { out ->
            for ((path, text) in listOf("docs/a.txt" to "AAA", "b.txt" to "BBB")) {
                val bytes = text.toByteArray()
                out.putArchiveEntry(org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply { name = path; size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        assertEquals(setOf("docs", "b.txt"), Archives.children(Archives.list(file), "").map { it.path }.toSet())
        val out = File(dir, "7")
        Archives.extract(file, out, only = listOf("docs"))
        assertEquals("AAA", File(out, "docs/a.txt").readText())
        assertFalse(File(out, "b.txt").exists())
    }

    @Test
    fun passwordProtectedZip() {
        val src = File(dir, "secret.txt").apply { writeText("top secret") }
        val file = File(dir, "locked.zip")
        net.lingala.zip4j.ZipFile(file, "hunter2".toCharArray()).addFile(
            src,
            net.lingala.zip4j.model.ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
            },
        )
        // Names are readable without the password; the contents aren't.
        assertEquals(listOf("secret.txt"), Archives.list(file).map { it.path })
        val noPassword = assertThrows(Archives.PasswordRequiredException::class.java) { Archives.extract(file, File(dir, "a")) }
        assertFalse(noPassword.wrong)
        val wrong = assertThrows(Archives.PasswordRequiredException::class.java) { Archives.extract(file, File(dir, "b"), password = "nope") }
        assertTrue(wrong.wrong)
        val out = File(dir, "c")
        Archives.extract(file, out, password = "hunter2")
        assertEquals("top secret", File(out, "secret.txt").readText())
    }
}
