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
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Folder to open first; blank means the user's home folder. */
    val startPath: String = "",
) {
    val address: String get() = if (port == 22) "$username@$host" else "$username@$host:$port"
}

/**
 * Persists SSH servers. Passwords are encrypted with an AES key that lives in the Android
 * Keystore, so they're never stored in plain text and can't be read off the device.
 */
internal class SshHostStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_hosts", Context.MODE_PRIVATE)

    fun load(): List<SshHost> = runCatching {
        val array = JSONArray(prefs.getString(KEY_HOSTS, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SshHost(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.getInt("port"),
                username = o.getString("username"),
                password = runCatching { decrypt(o.getString("password")) }.getOrDefault(""),
                startPath = o.optString("startPath"),
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
                    .put("host", h.host)
                    .put("port", h.port)
                    .put("username", h.username)
                    .put("password", encrypt(h.password))
                    .put("startPath", h.startPath)
            )
        }
        prefs.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

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

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }

    private companion object {
        const val KEY_HOSTS = "hosts"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "explorer_ssh_passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
