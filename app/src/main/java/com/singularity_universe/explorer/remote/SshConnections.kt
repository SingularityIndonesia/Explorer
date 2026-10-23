package com.singularity_universe.explorer.remote

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns the SSH servers the user saved and one live session per server.
 *
 * Like [RemoteConnection] there is one instance per process; MainActivity obtains it in
 * `onCreate`, provides it to the UI and calls [onResume] / [onPause] / [close]. A session is
 * opened on first use and then reused for every folder on that server (with keepalives), so
 * browsing never reconnects.
 */
class SshConnections private constructor(private val app: Context) {

    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Connected : Status
        data class Failed(val message: String) : Status
    }

    /** An open session, and the address it was opened through. */
    private class Live(val session: Session, val sftp: ChannelSftp, val home: String, val address: String)

    private val store = SshHostStore(app)
    private val keyStore = SshKeyStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownHosts = File(app.noBackupFilesDir, "ssh/known_hosts")
    private val live = HashMap<String, Live>()
    private val locks = HashMap<String, Mutex>()

    /** Saved servers, observable by Compose. */
    val hosts = mutableStateListOf<SshHost>().apply { addAll(store.load()) }

    /** Connection state per server id, observable by Compose. */
    val status = mutableStateMapOf<String, Status>()

    /** Saved SSH keys, observable by Compose. */
    val keys = mutableStateListOf<SshKey>().apply { addAll(keyStore.load()) }

    init {
        SshKeyTools.useBouncyCastle()
    }

    fun key(id: String?): SshKey? = id?.let { keys.firstOrNull { k -> k.id == it } }

    fun addKey(key: SshKey) {
        keys += key
        keyStore.save(keys)
    }

    /** Deletes a key; servers that signed in with it go back to their password. */
    fun removeKey(id: String) {
        keys.removeAll { it.id == id }
        keyStore.save(keys)
        hosts.filter { it.keyId == id }.forEach { save(it.copy(keyId = null)) }
    }

    /**
     * Adds [key] to the server's ~/.ssh/authorized_keys over the current sign-in (usually the
     * password), then signs in to it with the key from now on.
     */
    suspend fun installKey(id: String, key: SshKey) {
        val result = exec(id, SshKeyTools.installCommand(key.publicKey))
        if (result.exitCode != 0 || !result.output.contains("installed")) {
            throw IOException(result.output.trim().lines().lastOrNull()?.ifBlank { null } ?: "Couldn't add the key on the server")
        }
        host(id)?.let { save(it.copy(keyId = key.id)) }
    }

    /**
     * Forgets the host keys remembered for the server's addresses, so the next connection trusts it
     * again (after the server was reinstalled, or its keys were regenerated).
     */
    fun forgetHostKeys(id: String) {
        val host = host(id) ?: return
        disconnect(id)
        scope.launch {
            runCatching {
                val repository = JSch().apply { setKnownHosts(knownHosts.path) }.hostKeyRepository
                host.hosts.forEach { address ->
                    val endpoint = Endpoint.parse(address) ?: return@forEach
                    val port = endpoint.port ?: host.port
                    repository.remove(if (port == 22) endpoint.host else "[${endpoint.host}]:$port", null)
                }
            }.onFailure { Log.w(TAG, "Couldn't forget host keys", it) }
        }
    }

    fun host(id: String): SshHost? = hosts.firstOrNull { it.id == id }

    // ---- Saved servers ----

    fun save(host: SshHost): SshHost {
        val saved = if (host.id.isEmpty()) host.copy(id = UUID.randomUUID().toString()) else host
        val index = hosts.indexOfFirst { it.id == saved.id }
        if (index >= 0) hosts[index] = saved else hosts += saved
        store.save(hosts)
        // Settings changed: the next use reconnects with them.
        if (index >= 0) disconnect(saved.id)
        return saved
    }

    /** Switches which address the server is reached through; the next use reconnects through it. */
    fun useAddress(id: String, index: Int) {
        val host = host(id) ?: return
        if (index !in host.hosts.indices || index == host.activeHost) return
        save(host.copy(activeHost = index))
    }

    fun remove(id: String) {
        hosts.removeAll { it.id == id }
        store.save(hosts)
        disconnect(id)
        status.remove(id)
    }

    // ---- Lifecycle, driven by MainActivity ----

    /** Drops sessions that died while the app was away (Wi-Fi change, server restart). */
    fun onResume() {
        scope.launch {
            val dead = synchronized(live) { live.filterValues { !it.session.isConnected }.keys.toList() }
            dead.forEach { id ->
                lockFor(id).withLock { drop(id) }
                status[id] = Status.Idle
            }
        }
    }

    /** Sessions stay open in the background, kept alive by SSH keepalives; nothing to pause. */
    fun onPause() = Unit

    /** The activity is finishing: close every session. */
    fun close() {
        scope.launch {
            val ids = synchronized(live) { live.keys.toList() }
            ids.forEach { id -> lockFor(id).withLock { drop(id) } }
            ids.forEach { status[it] = Status.Idle }
        }
    }

    fun disconnect(id: String) {
        scope.launch {
            lockFor(id).withLock { drop(id) }
            status[id] = Status.Idle
        }
    }

    // ---- Using a server ----

    /** The user's home folder on the server (or the configured start folder). */
    suspend fun startPath(id: String): String {
        val host = host(id) ?: throw IOException("This server was removed")
        return host.startPath.ifBlank { sftp(id) { _, home -> home } }
    }

    /**
     * Runs [block] with the server's SFTP channel, connecting first if needed. Calls for one
     * server are serialized, since a channel isn't thread-safe.
     */
    suspend fun <T> sftp(id: String, block: (ChannelSftp, home: String) -> T): T = withContext(Dispatchers.IO) {
        lockFor(id).withLock {
            val connection = connected(id)
            try {
                block(connection.sftp, connection.home)
            } catch (e: Exception) {
                // A dead socket shows up as a failed operation: drop it so the next call reconnects.
                if (!connection.session.isConnected || !connection.sftp.isConnected) {
                    drop(id)
                    status[id] = Status.Idle
                }
                throw e
            }
        }
    }

    /** Runs a command on the server (used for server-side copies). */
    suspend fun exec(id: String, command: String): ShellResult = withContext(Dispatchers.IO) {
        lockFor(id).withLock {
            val channel = connected(id).session.openChannel("exec") as ChannelExec
            channel.setCommand("$command 2>&1")
            val output = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            val text = output.readBytes().decodeToString()
            while (!channel.isClosed) Thread.sleep(20)
            ShellResult(text, channel.exitStatus).also { channel.disconnect() }
        }
    }

    private fun connected(id: String): Live {
        val host = host(id) ?: throw IOException("This server was removed")
        // Reuse the open session unless it died or the user switched to another address.
        synchronized(live) { live[id] }
            ?.takeIf { it.session.isConnected && it.sftp.isConnected && it.address == host.address }
            ?.let { return it }
        drop(id)
        status[id] = Status.Connecting
        try {
            val session = newSession(host)
            val sftp = (session.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_TIMEOUT_MS) }
            val opened = Live(session, sftp, sftp.home, host.address)
            synchronized(live) { live[id] = opened }
            status[id] = Status.Connected
            return opened
        } catch (e: CancellationException) {
            status[id] = Status.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SSH connect to ${host.host} failed", e)
            val message = friendlyError(e)
            status[id] = Status.Failed(message)
            throw IOException(message, e)
        }
    }

    /**
     * Opens a separate session to a saved server, for a caller that owns it (the terminal): it isn't
     * shared, reused or closed by this class. Throws an [IOException] with a readable message.
     */
    suspend fun openSession(id: String): Session = withContext(Dispatchers.IO) {
        val host = host(id) ?: throw IOException("This server was removed")
        try {
            newSession(host).also { session ->
                // Cancelled while connecting: nobody will own this session, so close it.
                if (!isActive) {
                    session.disconnect()
                    throw CancellationException()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SSH connect to ${host.host} failed", e)
            throw IOException(friendlyError(e), e)
        }
    }

    private fun newSession(host: SshHost): Session {
        knownHosts.parentFile?.mkdirs()
        synchronized(knownHosts) { if (!knownHosts.exists()) knownHosts.createNewFile() }
        val jsch = JSch().apply { setKnownHosts(knownHosts.path) }
        // With a key, it's offered first (publickey); the password stays as a fallback.
        key(host.keyId)?.let { key ->
            jsch.addIdentity("explorer-${key.id}", key.privateKey.toByteArray(), null, key.passphrase.ifEmpty { null }?.toByteArray())
        }
        return jsch.getSession(host.username, host.host, host.connectPort).apply {
            setPassword(host.password.toByteArray())
            userInfo = PasswordUserInfo(host.password)
            // "ask" + PasswordUserInfo: trust a server the first time, refuse if its key changes.
            setConfig("StrictHostKeyChecking", "ask")
            setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            setServerAliveInterval(KEEPALIVE_MS)
            setServerAliveCountMax(3)
            connect(CONNECT_TIMEOUT_MS)
        }
    }

    private fun drop(id: String) {
        val old = synchronized(live) { live.remove(id) } ?: return
        runCatching { old.sftp.disconnect() }
        runCatching { old.session.disconnect() }
    }

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun friendlyError(e: Exception): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("Auth fail", ignoreCase = true) || text.contains("Auth cancel", ignoreCase = true) ->
                "Wrong username, password or key"
            text.contains("HostKey has been changed", ignoreCase = true) || text.contains("reject HostKey", ignoreCase = true) ->
                "The server's identity changed. If that's expected, use Forget server identity in its menu."
            text.contains("timeout", ignoreCase = true) -> "The server didn't respond"
            e.cause is java.net.ConnectException || text.contains("refused", ignoreCase = true) ->
                "Connection refused — is SSH running on that port?"
            e.cause is java.net.UnknownHostException -> "Unknown host"
            else -> text.ifBlank { "Couldn't connect" }
        }
    }

    /**
     * Answers JSch's prompts: the stored password for keyboard-interactive logins, "yes" to trust
     * a server seen for the first time, and "no" when a known server's key has changed.
     */
    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?): Boolean =
            message?.contains("HAS CHANGED", ignoreCase = true) != true
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }

    companion object {
        private const val TAG = "ExplorerSsh"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_MS = 15_000

        @Volatile
        private var instance: SshConnections? = null

        fun get(context: Context): SshConnections =
            instance ?: synchronized(this) {
                instance ?: SshConnections(context.applicationContext).also { instance = it }
            }
    }
}
