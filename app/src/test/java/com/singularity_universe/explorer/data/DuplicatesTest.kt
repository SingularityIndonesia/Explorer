package com.singularity_universe.explorer.data

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private val dir = Files.createTempDirectory("dupes").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray, modified: Long = 0): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
            if (modified > 0) setLastModified(modified)
        }

    @Test
    fun findsCopiesOldestFirst() {
        val photo = Random(1).nextBytes(300_000)
        val original = file("DCIM/photo.jpg", photo, modified = 1_600_000_000_000)
        val copy = file("Download/photo (1).jpg", photo, modified = 1_700_000_000_000)
        val other = file("Download/other.jpg", Random(2).nextBytes(300_000))
        val groups = Duplicates.find(listOf(copy, other, original))
        assertEquals(1, groups.size)
        assertEquals(listOf(original, copy), groups.single())
    }

    @Test
    fun sameStartAndEndButDifferentMiddleIsNotADuplicate() {
        // Equal size, equal first and last 64 KB: only the full hash can tell them apart.
        val a = Random(3).nextBytes(500_000)
        val b = a.copyOf().also { it[250_000] = (it[250_000] + 1).toByte() }
        val groups = Duplicates.find(listOf(file("a.bin", a), file("b.bin", b)))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun smallFilesAndSizeFilter() {
        val text = "hello".toByteArray()
        val groups = Duplicates.find(listOf(file("1.txt", text), file("2.txt", text), file("3.txt", text)))
        assertEquals(3, groups.single().size)
        assertTrue("below the minimum size", Duplicates.find(listOf(file("4.txt", text), file("5.txt", text)), minSize = 1024).isEmpty())
    }

    @Test
    fun sameFileListedTwiceIsNotItsOwnDuplicate() {
        val one = file("once.bin", Random(4).nextBytes(10_000))
        assertTrue(Duplicates.find(listOf(one, File(dir, "./once.bin"))).isEmpty())
    }

    @Test
    fun biggestSavingsFirst() {
        val small = Random(5).nextBytes(1_000)
        val big = Random(6).nextBytes(200_000)
        val groups = Duplicates.find(listOf(file("s1", small), file("s2", small), file("b1", big), file("b2", big)))
        assertEquals(listOf(200_000L, 1_000L), groups.map { it.first().length() })
    }
}
