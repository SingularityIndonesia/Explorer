package com.singularity_universe.explorer.remote

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * An SSH key for signing in to servers. [privateKey] is the key file's text (OpenSSH or PEM),
 * possibly protected by [passphrase]; [publicKey] is the line that goes into a server's
 * ~/.ssh/authorized_keys.
 */
data class SshKey(
    val id: String,
    val name: String,
    /** e.g. "ssh-ed25519", "ssh-rsa". */
    val type: String,
    val privateKey: String,
    val publicKey: String,
    val fingerprint: String,
    val passphrase: String = "",
)

/** Generating and importing keys with JSch (pure Java, so it also runs in unit tests). */
object SshKeyTools {
    enum class Kind(val label: String) { Ed25519("Ed25519"), Rsa("RSA 3072") }

    init {
        useBouncyCastle()
    }

    /**
     * JSch's default Ed25519 classes need Java 15+, which Android doesn't provide; its BouncyCastle
     * versions (BouncyCastle is bundled) work everywhere.
     */
    fun useBouncyCastle() {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
    }

    /** A new key pair, named [name] (also the public key's comment). */
    fun generate(name: String, kind: Kind): SshKey {
        val jsch = JSch()
        val pair = when (kind) {
            Kind.Ed25519 -> KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            Kind.Rsa -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        }
        try {
            // The OpenSSH format (what ssh-keygen writes): unlike PEM it can hold Ed25519 keys.
            val privateKey = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toString(Charsets.UTF_8.name())
            return describe(pair, name, privateKey, "")
        } finally {
            pair.dispose()
        }
    }

    /**
     * Reads a private key file's text. Throws [IllegalArgumentException] with a readable message
     * when it isn't a key, or when the passphrase is missing or wrong.
     */
    fun import(name: String, privateKey: String, passphrase: String): SshKey {
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        val pair = try {
            KeyPair.load(JSch(), text.toByteArray(), null)
        } catch (e: JSchException) {
            throw IllegalArgumentException("That isn't a private key JSch can read (OpenSSH, PEM or PuTTY)", e)
        }
        try {
            if (pair.isEncrypted) {
                if (passphrase.isEmpty()) throw IllegalArgumentException("This key is protected: enter its passphrase")
                // decrypt() on a copy: the stored text stays protected, and the passphrase is kept for signing in.
                if (!pair.decrypt(passphrase)) throw IllegalArgumentException("Wrong passphrase")
            }
            return describe(pair, name, text, passphrase)
        } finally {
            pair.dispose()
        }
    }

    private fun describe(pair: KeyPair, name: String, privateKey: String, passphrase: String): SshKey {
        val comment = name.replace(Regex("\\s+"), "-").ifEmpty { "explorer" }
        val publicKey = ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toString(Charsets.UTF_8.name()).trim()
        return SshKey(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { pair.keyTypeString },
            type = pair.keyTypeString,
            privateKey = privateKey,
            publicKey = publicKey,
            fingerprint = pair.fingerPrint ?: "",
            passphrase = passphrase,
        )
    }

    /** The shell command that adds [publicKey] to ~/.ssh/authorized_keys (once), like ssh-copy-id. */
    fun installCommand(publicKey: String): String {
        val quoted = "'" + publicKey.replace("'", "'\\''") + "'"
        return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF $quoted ~/.ssh/authorized_keys || printf '%s\\n' $quoted >> ~/.ssh/authorized_keys) && " +
            "chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && echo installed"
    }
}

/** Saved SSH keys; private keys and passphrases are encrypted with [Secrets]. */
internal class SshKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_keys", Context.MODE_PRIVATE)

    fun load(): List<SshKey> = runCatching {
        val array = JSONArray(prefs.getString(KEY_KEYS, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SshKey(
                id = o.getString("id"),
                name = o.getString("name"),
                type = o.getString("type"),
                privateKey = Secrets.decrypt(o.getString("privateKey")),
                publicKey = o.getString("publicKey"),
                fingerprint = o.optString("fingerprint"),
                passphrase = runCatching { Secrets.decrypt(o.getString("passphrase")) }.getOrDefault(""),
            )
        }
    }.getOrDefault(emptyList())

    fun save(keys: List<SshKey>) {
        val array = JSONArray()
        keys.forEach { k ->
            array.put(
                JSONObject()
                    .put("id", k.id)
                    .put("name", k.name)
                    .put("type", k.type)
                    .put("privateKey", Secrets.encrypt(k.privateKey))
                    .put("publicKey", k.publicKey)
                    .put("fingerprint", k.fingerprint)
                    .put("passphrase", Secrets.encrypt(k.passphrase))
            )
        }
        prefs.edit().putString(KEY_KEYS, array.toString()).apply()
    }

    private companion object {
        const val KEY_KEYS = "keys"
    }
}
