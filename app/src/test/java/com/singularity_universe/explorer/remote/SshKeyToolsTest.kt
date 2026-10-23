package com.singularity_universe.explorer.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.PublicKey
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshKeyToolsTest {
    private lateinit var server: SshServer
    private val authorized = mutableListOf<PublicKey>()

    @Before
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            // Only the keys a test authorizes may sign in; passwords never.
            setPublickeyAuthenticator { _, key, _ -> authorized.any { it == key } }
            setPasswordAuthenticator { _, _, _ -> false }
            start()
        }
    }

    @After
    fun stopServer() = server.stop(true)

    /** Signs in the way SshConnections does: the stored key text and passphrase as a JSch identity. */
    private fun signIn(key: SshKey): Boolean {
        SshKeyTools.useBouncyCastle()
        val jsch = JSch()
        jsch.addIdentity("test", key.privateKey.toByteArray(), null, key.passphrase.ifEmpty { null }?.toByteArray())
        val session = jsch.getSession("steve", "127.0.0.1", server.port).apply {
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "publickey")
        }
        return try {
            session.connect(10_000)
            session.isConnected
        } catch (e: Exception) {
            false
        } finally {
            session.disconnect()
        }
    }

    private fun authorize(key: SshKey) {
        authorized += PublicKeyEntry.parsePublicKeyEntry(key.publicKey).resolvePublicKey(null, emptyMap(), null)
    }

    @Test
    fun generatedEd25519SignsIn() {
        val key = SshKeyTools.generate("Pixel phone", SshKeyTools.Kind.Ed25519)
        assertEquals("ssh-ed25519", key.type)
        assertTrue(key.publicKey, key.publicKey.startsWith("ssh-ed25519 AAAA") && key.publicKey.endsWith(" Pixel-phone"))
        assertTrue(key.fingerprint, key.fingerprint.isNotEmpty())
        assertFalse("not authorized yet", signIn(key))
        authorize(key)
        assertTrue(signIn(key))
    }

    @Test
    fun generatedRsaSignsIn() {
        val key = SshKeyTools.generate("old server", SshKeyTools.Kind.Rsa)
        assertEquals("ssh-rsa", key.type)
        authorize(key)
        assertTrue(signIn(key))
    }

    @Test
    fun importedKeyKeepsItsPublicKey() {
        val original = SshKeyTools.generate("laptop", SshKeyTools.Kind.Ed25519)
        val imported = SshKeyTools.import("laptop", original.privateKey, "")
        assertEquals(original.publicKey.substringBeforeLast(' '), imported.publicKey.substringBeforeLast(' '))
        authorize(imported)
        assertTrue(signIn(imported))
    }

    @Test
    fun protectedKeyNeedsTheRightPassphrase() {
        SshKeyTools.useBouncyCastle()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val protected = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, "s3cret".toByteArray()) }.toString()
        assertThrows(IllegalArgumentException::class.java) { SshKeyTools.import("k", protected, "") }
        assertThrows(IllegalArgumentException::class.java) { SshKeyTools.import("k", protected, "wrong") }
        val key = SshKeyTools.import("k", protected, "s3cret")
        assertTrue("stored text stays protected", key.privateKey.contains("ENCRYPTED") || key.privateKey.contains("OPENSSH"))
        authorize(key)
        assertTrue("signs in with the saved passphrase", signIn(key))
    }

    @Test
    fun notAKey() {
        assertThrows(IllegalArgumentException::class.java) { SshKeyTools.import("x", "hello", "") }
    }

    /** The install command, run by a real shell against a temporary home folder. */
    @Test
    fun installCommandAddsTheKeyOnce() {
        val home = Files.createTempDirectory("home").toFile()
        val key = SshKeyTools.generate("it's mine", SshKeyTools.Kind.Ed25519)
        fun run(): String {
            val process = ProcessBuilder("sh", "-c", SshKeyTools.installCommand(key.publicKey))
                .apply { environment()["HOME"] = home.path }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(output, 0, process.waitFor())
            return output
        }
        assertTrue(run().contains("installed"))
        assertTrue(run().contains("installed"))
        val file = File(home, ".ssh/authorized_keys")
        assertEquals("added once, even when installed twice", listOf(key.publicKey), file.readLines())
        val mode = Files.getPosixFilePermissions(file.toPath()).joinToString(",") { it.name }
        assertEquals("OWNER_READ,OWNER_WRITE", mode.split(",").sorted().joinToString(","))
        home.deleteRecursively()
    }
}
