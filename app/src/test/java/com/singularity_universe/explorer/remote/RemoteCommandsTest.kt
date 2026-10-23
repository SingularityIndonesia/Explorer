package com.singularity_universe.explorer.remote

import com.singularity_universe.explorer.data.Archives
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The server-side compress command, run by a real shell on a temporary folder. */
class RemoteCommandsTest {
    private val dir = Files.createTempDirectory("remote cmd").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun run(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    private fun sample() {
        File(dir, "photos").mkdirs()
        File(dir, "photos/a.jpg").writeText("jpeg")
        File(dir, "-odd name.txt").writeText("dash")
    }

    @Test
    fun tarWhenZipIsntUsed() {
        sample()
        val first = RemoteCommands.created(run(RemoteCommands.compress(dir.path, "My Files", listOf("photos", "-odd name.txt"), allowZip = false)))
        assertEquals("My Files.tar.gz", first)
        val paths = Archives.list(File(dir, first!!)).map { it.path }.toSet()
        assertTrue(paths.toString(), paths.containsAll(listOf("photos/a.jpg", "-odd name.txt")))
        // The name is taken now: the next one gets a number.
        assertEquals("My Files (1).tar.gz", RemoteCommands.created(run(RemoteCommands.compress(dir.path, "My Files", listOf("photos"), allowZip = false))))
    }

    @Test
    fun zipWhenAvailable() {
        assumeTrue("zip isn't installed here", ProcessBuilder("sh", "-c", "command -v zip").start().waitFor() == 0)
        sample()
        val name = RemoteCommands.created(run(RemoteCommands.compress(dir.path, "pack", listOf("photos"), allowZip = true)))
        assertEquals("pack.zip", name)
        assertTrue(Archives.list(File(dir, name!!)).any { it.path == "photos/a.jpg" })
    }
}
