package com.singularity_universe.explorer.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What streaming and background transfers rely on, against a real SFTP server: reading a file
 * from any position (seeking in a video), and several channels working on one session at once
 * (browsing while a download runs).
 */
class SftpChannelsTest {
    private lateinit var server: SshServer
    private val root = Files.createTempDirectory("sftp-root").toFile()

    @Before
    fun start() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == "steve" && password == "pw" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
    }

    @After
    fun stop() {
        server.stop(true)
        root.deleteRecursively()
    }

    private fun session() = JSch().getSession("steve", "127.0.0.1", server.port).apply {
        setPassword("pw")
        setConfig("StrictHostKeyChecking", "no")
        connect(10_000)
    }

    private fun channel(session: com.jcraft.jsch.Session) = (session.openChannel("sftp") as ChannelSftp).apply { connect(10_000) }

    @Test
    fun readsFromAnyPosition() {
        val bytes = Random(7).nextBytes(3_000_000)
        File(root, "movie.mp4").writeBytes(bytes)
        val session = session()
        try {
            val sftp = channel(session)
            assertEquals(bytes.size.toLong(), sftp.stat("/movie.mp4").size)
            // A seek to 2,500,000: what SftpDataSource asks for.
            // A Long: an Int would pick the deprecated get(path, monitor, mode), which ignores it.
            val tail = sftp.get("/movie.mp4", null, 2_500_000L).use { it.readBytes() }
            assertArrayEquals(bytes.copyOfRange(2_500_000, bytes.size), tail)
            sftp.disconnect()
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun browsingWhileADownloadRunsOnAnotherChannel() {
        File(root, "big.bin").writeBytes(Random(8).nextBytes(8_000_000))
        File(root, "docs").mkdirs()
        File(root, "docs/a.txt").writeText("a")
        val session = session()
        try {
            val download = channel(session)
            val browse = channel(session)
            // Start a download, read part of it, then list a folder on the other channel mid-transfer.
            val stream = download.get("/big.bin")
            val first = ByteArray(1_000_000)
            var got = 0
            while (got < first.size) got += stream.read(first, got, first.size - got).also { check(it > 0) }
            @Suppress("UNCHECKED_CAST")
            val names = (browse.ls("/docs") as java.util.Vector<ChannelSftp.LsEntry>).map { it.filename }.filter { !it.startsWith(".") }
            assertEquals(listOf("a.txt"), names)
            assertEquals(7_000_000L, stream.readBytes().size.toLong())
            stream.close()
            download.disconnect()
            browse.disconnect()
        } finally {
            session.disconnect()
        }
    }
}
