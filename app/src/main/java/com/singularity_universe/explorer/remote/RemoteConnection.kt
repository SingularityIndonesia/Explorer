package com.singularity_universe.explorer.remote

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress

/** Thrown when a folder (Android/data, Android/obb) can only be reached through remote access. */
class RemoteAccessRequiredException : IOException("Connect remote access to open this folder")

class ShellResult(val output: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * The single connection to this phone's own Wireless debugging (adbd), used to reach folders
 * Android hides from normal apps.
 *
 * There is exactly one instance per process ([get]); [com.singularity_universe.explorer.MainActivity]
 * obtains it in `onCreate`, provides it to the UI, and drives it with [onResume], [onPause] and
 * [close]. The socket stays open between screens, so opening folders never reconnects.
 */
class RemoteConnection private constructor(private val app: Context) {

    sealed interface Status {
        /** Never paired with this phone's Wireless debugging. */
        data object NotPaired : Status
        /** Paired, but not connected (closed, or turned off by the user). */
        data object Disconnected : Status
        data object Connecting : Status
        data object Connected : Status
        /** Paired, but Wireless debugging is off or unreachable (e.g. after a reboot or without Wi-Fi). */
        data object DebuggingOff : Status
        data class Failed(val message: String) : Status
    }

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var manager: AdbManager? = null
    private var connectJob: Job? = null

    private var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    /** Whether to connect automatically on resume; false after the user taps Disconnect. */
    private var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    var status by mutableStateOf(if (paired) Status.Disconnected else Status.NotPaired)
        private set

    val isConnected: Boolean get() = status == Status.Connected

    // ---- Lifecycle, driven by MainActivity ----

    fun onResume() {
        val alive = manager?.isConnected == true
        when {
            alive -> status = Status.Connected
            paired && autoConnect -> connectAsync()
            status == Status.Connected -> status = Status.Disconnected
        }
    }

    fun onPause() {
        // Keep an open socket for when the user comes back; just stop trying to establish one.
        if (status == Status.Connecting) {
            connectJob?.cancel()
            status = Status.Disconnected
        }
    }

    /** Called when the activity is finishing: release the socket. Pairing is kept. */
    fun close() {
        connectJob?.cancel()
        stopPairingDiscovery()
        scope.launch {
            lock.withLock {
                runCatching { manager?.close() }
                manager = null
            }
            status = if (paired) Status.Disconnected else Status.NotPaired
        }
    }

    // ---- Connecting ----

    fun connectAsync() {
        if (connectJob?.isActive == true) return
        autoConnect = true
        connectJob = scope.launch { connect() }
    }

    suspend fun connect(): Boolean = lock.withLock {
        val current = manager
        if (current?.isConnected == true) {
            status = Status.Connected
            return@withLock true
        }
        status = Status.Connecting
        try {
            val adb = current ?: AdbManager(app).also { manager = it }
            // Finds the Wireless debugging port via mDNS, then performs the TLS handshake.
            val connected = runInterruptible { adb.connectTls(app, CONNECT_TIMEOUT_MS) }
            status = if (connected) Status.Connected else Status.DebuggingOff
            if (connected) paired = true
            connected
        } catch (e: AdbPairingRequiredException) {
            paired = false
            status = Status.NotPaired
            false
        } catch (e: CancellationException) {
            status = if (paired) Status.Disconnected else Status.NotPaired
            throw e
        } catch (e: Exception) {
            status = if (paired) Status.DebuggingOff else Status.Failed(e.message ?: "Couldn't connect")
            false
        }
    }

    fun disconnect() {
        autoConnect = false
        connectJob?.cancel()
        scope.launch {
            lock.withLock { runCatching { manager?.disconnect() } }
            status = Status.Disconnected
        }
    }

    /** Disconnects and deletes this app's debugging key; the user has to pair again. */
    fun forget() {
        connectJob?.cancel()
        scope.launch {
            lock.withLock {
                runCatching { manager?.close() }
                (manager ?: AdbManager(app)).forget()
                manager = null
            }
            paired = false
            status = Status.NotPaired
        }
    }

    // ---- Pairing ----

    @Volatile private var pairingHost: InetAddress? = null
    @Volatile private var pairingPort: Int = -1
    private var pairingMdns: AdbMdns? = null

    /** Starts looking for the "Pair device with pairing code" service on this phone. */
    @Synchronized
    fun startPairingDiscovery() {
        if (pairingMdns?.isRunning == true) return
        pairingPort = -1
        pairingMdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0) {
                pairingHost = host
                pairingPort = port
            } else {
                pairingPort = -1
            }
        }.also { it.start() }
    }

    @Synchronized
    fun stopPairingDiscovery() {
        pairingMdns?.stop()
        pairingMdns = null
    }

    /** Pairs using the 6-digit code from Settings › Wireless debugging › Pair device with pairing code. */
    suspend fun pair(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        startPairingDiscovery()
        val found = withTimeoutOrNull(PAIRING_DISCOVERY_TIMEOUT_MS) {
            while (pairingPort <= 0) delay(200)
            true
        }
        val host = pairingHost
        if (found == null || host == null) {
            return@withContext Result.failure(
                IOException("Couldn't find the pairing service. Keep the pairing code dialog open and try again.")
            )
        }
        val result = runCatching {
            lock.withLock {
                val adb = manager ?: AdbManager(app).also { manager = it }
                val address = host.hostAddress ?: throw IOException("Couldn't find the pairing service")
                val ok = runInterruptible { adb.pair(address, pairingPort, code.trim()) }
                if (!ok) throw IOException("Pairing failed. Check the code and try again.")
            }
        }
        if (result.isSuccess) {
            stopPairingDiscovery()
            paired = true
            autoConnect = true
            connect()
        }
        result.recoverCatching { throw IOException(it.message ?: "Pairing failed", it) }
    }

    // ---- Running commands ----

    /** Runs a shell command as the `shell` user and returns its output and exit code. */
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        openStream(shellService(command)).use { it.readShellResult() }
    }

    /** Copies a file into [destination] over ADB's sync protocol (binary-safe). */
    suspend fun pull(path: String, destination: File) = withContext(Dispatchers.IO) {
        openStream("sync:").use { it.syncRecv(path, destination) }
    }

    private suspend fun openStream(destination: String) = try {
        val adb = manager?.takeIf { it.isConnected } ?: run {
            if (!paired || !connect()) throw RemoteAccessRequiredException()
            manager!!
        }
        runInterruptible { adb.openStream(destination) }
    } catch (e: RemoteAccessRequiredException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The socket died (Wi-Fi off, debugging turned off, reboot of adbd).
        Log.w(TAG, "Remote stream failed", e)
        status = if (paired) Status.DebuggingOff else Status.NotPaired
        throw IOException("Remote access was disconnected", e)
    }

    companion object {
        private const val TAG = "ExplorerRemote"
        private const val KEY_PAIRED = "paired"
        private const val KEY_AUTO = "auto_connect"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val PAIRING_DISCOVERY_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: RemoteConnection? = null

        fun get(context: Context): RemoteConnection =
            instance ?: synchronized(this) {
                instance ?: RemoteConnection(context.applicationContext).also { instance = it }
            }
    }
}

/** Quotes a path for the Android shell (`mksh`). */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
