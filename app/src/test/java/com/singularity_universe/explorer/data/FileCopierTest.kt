package com.singularity_universe.explorer.data

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCopierTest {
    private val dir = Files.createTempDirectory("copier").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun copiesAFolderAndCountsBytes() {
        val src = File(dir, "src/sub").apply { mkdirs() }.parentFile!!
        val a = Random(1).nextBytes(700_000)
        File(src, "a.bin").writeBytes(a)
        File(src, "sub/b.txt").writeText("hello")
        val progress = CopyProgress()
        FileCopier.copy(src, File(dir, "out"), progress)
        assertArrayEquals(a, File(dir, "out/a.bin").readBytes())
        assertEquals("hello", File(dir, "out/sub/b.txt").readText())
        assertEquals(700_005L, progress.bytes)
        assertEquals(700_005L, FileCopier.sizeOf(src))
    }

    @Test
    fun cancellingStopsMidFileAndRemovesThePartialCopy() {
        val big = File(dir, "big.bin").apply { writeBytes(Random(2).nextBytes(5_000_000)) }
        val target = File(dir, "copy.bin")
        // The user cancels right after the first chunk.
        val progress = object : CopyProgress() {
            override fun add(count: Long) {
                super.add(count)
                cancelled = true
            }
        }
        assertThrows(CopyCancelledException::class.java) { FileCopier.copy(big, target, progress) }
        assertFalse("the half-written file is deleted", target.exists())
        assertTrue("stopped before the end", progress.bytes < big.length())
    }
}
