package com.singularity_universe.explorer.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved SSH server. Files on it are addressed as `ssh://<id>/absolute/path`. */
data class SshHost(
    val id: String,
    val name: String,
    /** Ways to reach the server (e.g. home Wi-Fi IP, VPN IP), as `host` or `host:port`. */
    val hosts: List<String>,
    /** Default port, for addresses that don't name their own. */
    val port: Int,
    val username: String,
    val password: String,
    /** Folder to open first; blank means the user's home folder. */
    val startPath: String = "",
    /** Which of [hosts] is used now. */
    val activeHost: Int = 0,
    /** Sign in with this saved SSH key ([SshKey.id]) instead of the password. */
    val keyId: String? = null,
) {
    private val endpoint: Endpoint
        get() = Endpoint.parse(hosts.getOrElse(activeHost) { hosts.firstOrNull().orEmpty() }) ?: Endpoint("")

    /** Host of the address in use. */
    val host: String get() = endpoint.host

    /** Port of the address in use. */
    val connectPort: Int get() = endpoint.port ?: port

    /** The address in use, e.g. "steve@10.0.0.2" or "steve@vpn.example:2222". */
    val address: String get() = addressOf(activeHost)

    fun addressOf(index: Int): String {
        val e = Endpoint.parse(hosts.getOrElse(index) { "" }) ?: return username
        val p = e.port ?: port
        val host = Endpoint(e.host).toString()
        return if (p == 22) "$username@$host" else "$username@$host:$p"
    }
}

/** Persists SSH servers; passwords are encrypted with [Secrets]. */
internal class SshHostStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_hosts", Context.MODE_PRIVATE)

    fun load(): List<SshHost> = runCatching {
        val array = JSONArray(prefs.getString(KEY_HOSTS, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SshHost(
                id = o.getString("id"),
                name = o.getString("name"),
                // Older versions saved a single "host".
                hosts = o.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: listOf(o.getString("host")),
                port = o.getInt("port"),
                activeHost = o.optInt("activeHost"),
                username = o.getString("username"),
                password = runCatching { Secrets.decrypt(o.getString("password")) }.getOrDefault(""),
                startPath = o.optString("startPath"),
                keyId = o.optString("keyId").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    fun save(hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { h ->
            array.put(
                JSONObject()
                    .put("id", h.id)
                    .put("name", h.name)
                    .put("hosts", JSONArray(h.hosts))
                    .put("activeHost", h.activeHost)
                    .put("port", h.port)
                    .put("username", h.username)
                    .put("password", Secrets.encrypt(h.password))
                    .put("startPath", h.startPath)
                    .put("keyId", h.keyId ?: "")
            )
        }
        prefs.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

    private companion object {
        const val KEY_HOSTS = "hosts"
    }
}

/**
 * Encrypts saved secrets (passwords, private keys, passphrases) with an AES key that lives in the
 * Android Keystore, so they're never stored in plain text and can't be read off the device.
 */
internal object Secrets {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "explorer_ssh_passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }
}
