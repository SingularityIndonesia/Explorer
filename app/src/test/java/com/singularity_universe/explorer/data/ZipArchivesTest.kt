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

class ZipArchivesTest {
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
        val entries = ZipArchives.list(file)
        assertEquals(listOf("docs", "readme.txt"), ZipArchives.children(entries, "").map { it.path }.sorted())
        assertTrue(entries.first { it.path == "docs" }.isDirectory)
        assertEquals(listOf("docs/old", "docs/plan.md"), ZipArchives.children(entries, "docs").map { it.path }.sorted())
        assertEquals(2, ZipArchives.childCounts(entries)["docs"])
        assertEquals(2L, entries.first { it.path == "readme.txt" }.size)
    }

    @Test
    fun unicodeAndLegacyNames() {
        val utf8 = zip("u.zip", files = arrayOf("café/naïve 日本.txt" to "ok"))
        assertEquals("café/naïve 日本.txt", ZipArchives.list(utf8).first { !it.isDirectory }.path)
        // Old Windows tools wrote names in the DOS code page, without the UTF-8 flag.
        val legacy = zip("l.zip", Charset.forName("IBM437"), "café.txt" to "ok")
        assertEquals("café.txt", ZipArchives.list(legacy).single().path)
        val out = File(dir, "legacy-out")
        ZipArchives.extract(legacy, out)
        assertEquals("ok", File(out, "café.txt").readText())
    }

    @Test
    fun refusesZipSlip() {
        val evil = zip("evil.zip", files = arrayOf("fine.txt" to "a", "../../escaped.txt" to "pwned"))
        val out = File(dir, "out")
        assertThrows(IOException::class.java) { ZipArchives.extract(evil, out) }
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
    }

    @Test
    fun extractsSelectionRelativeToFolder() {
        val file = zip("s.zip", files = arrayOf("docs/a.txt" to "A", "docs/sub/b.txt" to "B", "other.txt" to "C"))
        val out = File(dir, "picked")
        val written = ZipArchives.extract(file, out, only = listOf("docs/sub", "docs/a.txt"), base = "docs")
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
        ZipArchives.compress(listOf(src, note), target)
        val paths = ZipArchives.list(target).map { it.path }.toSet()
        assertTrue(paths.containsAll(listOf("project", "project/main.kt", "project/res/values/strings.xml", "project/empty", "note.txt")))
        val out = File(dir, "unzipped")
        ZipArchives.extract(target, out)
        assertEquals("<resources/>", File(out, "project/res/values/strings.xml").readText())
        assertTrue(File(out, "project/empty").isDirectory)
        assertEquals("note", File(out, "note.txt").readText())
    }

    @Test
    fun compressingAFolderDoesNotIncludeTheZipBeingWritten() {
        val src = File(dir, "photos").apply { mkdirs() }
        File(src, "a.jpg").writeText("jpeg")
        val target = File(src, "photos.zip")
        ZipArchives.compress(listOf(src), target)
        assertEquals(setOf("photos", "photos/a.jpg"), ZipArchives.list(target).map { it.path }.toSet())
    }

    @Test
    fun extractsOneEntry() {
        val file = zip("one.zip", files = arrayOf("a/b/c.txt" to "deep"))
        val target = File(dir, "c.txt")
        ZipArchives.extractEntry(file, "a/b/c.txt", target)
        assertEquals("deep", target.readText())
    }
}
