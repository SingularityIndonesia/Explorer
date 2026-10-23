package com.singularity_universe.explorer.remote

import com.singularity_universe.explorer.data.CopyProgress
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.UUID

/** Another Android device reached over its Wireless debugging. Files: `adb://<id>/absolute/path`. */
data class AdbDevice(
    val id: String,
    val name: String,
    /** Ways to reach the device (e.g. home Wi-Fi IP, VPN IP), as `host` or `host:port`. */
    val hosts: List<String>,
    /** The "IP address & Port" port on the device's Wireless debugging screen (changes when toggled). */
    val port: Int,
    /** Which of [hosts] is used now. */
    val activeHost: Int = 0,
) {
    private val endpoint: Endpoint
        get() = Endpoint.parse(hosts.getOrElse(activeHost) { hosts.firstOrNull().orEmpty() }) ?: Endpoint("")

    /** Host of the address in use. */
    val host: String get() = endpoint.host

    /** Port of the address in use: its own port if it names one, else the Wireless debugging port. */
    val connectPort: Int get() = endpoint.port ?: port

    /** Whether the address in use names its own port (then it's never auto-updated). */
    val hasFixedPort: Boolean get() = endpoint.port != null

    /** The address in use, e.g. "192.168.1.5:37191". */
    val address: String get() = addressOf(activeHost)

    fun addressOf(index: Int): String {
        val e = Endpoint.parse(hosts.getOrElse(index) { "" }) ?: return ""
        return Endpoint(e.host, e.port ?: port).toString()
    }
}

/**
 * Owns the Android devices the user added and one live ADB connection per device.
 *
 * Like [SshConnections] there is one instance per process; MainActivity obtains it in `onCreate`,
 * provides it to the UI and calls [onResume] / [onPause] / [close]. A connection is opened on
 * first use and reused for every folder on that device.
 */
class AdbDevices private constructor(private val app: Context) {

    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Connected : Status
        data class Failed(val message: String) : Status
    }

    private val prefs = app.getSharedPreferences("adb_devices", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = HashMap<String, AbsAdbConnectionManager>()

    /** The address each live connection was opened through. */
    private val liveAddress = HashMap<String, String>()

    /** Devices with an open remote control window; their connections outlive Explorer's main window. */
    private val sessions = HashMap<String, Int>()

    /** Set when Explorer's main window finished; connections still used by a session close when it ends. */
    @Volatile
    private var closeRequested = false
    private val locks = HashMap<String, Mutex>()

    /** Saved devices, observable by Compose. */
    val devices = mutableStateListOf<AdbDevice>().apply { addAll(load()) }

    /** Connection state per device id, observable by Compose. */
    val status = mutableStateMapOf<String, Status>()

    fun device(id: String): AdbDevice? = devices.firstOrNull { it.id == id }

    // ---- Saved devices ----

    fun save(device: AdbDevice): AdbDevice {
        val saved = if (device.id.isEmpty()) device.copy(id = UUID.randomUUID().toString()) else device
        val index = devices.indexOfFirst { it.id == saved.id }
        if (index >= 0) devices[index] = saved else devices += saved
        persist()
        if (index >= 0) disconnect(saved.id)
        return saved
    }

    /** Switches which address the device is reached through; the next use reconnects through it. */
    fun useAddress(id: String, index: Int) {
        val device = device(id) ?: return
        if (index !in device.hosts.indices || index == device.activeHost) return
        save(device.copy(activeHost = index))
    }

    fun remove(id: String) {
        devices.removeAll { it.id == id }
        persist()
        disconnect(id)
        status.remove(id)
    }

    /**
     * Pairs with a device using the code and port from its "Pair device with pairing code" dialog.
     * Only needed once per device: it then trusts this app's key.
     */
    suspend fun pair(id: String, pairingPort: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val device = device(id) ?: return@withContext Result.failure(IOException("This device was removed"))
        runCatching {
            val adb = AdbManager(app)
            try {
                val ok = runInterruptible { adb.pair(device.host, pairingPort, code.trim()) }
                if (!ok) throw IOException("Pairing failed. Check the code and try again.")
            } finally {
                runCatching { adb.close() }
            }
        }.onFailure {
            Log.w(TAG, "Pairing with ${device.host} failed", it)
            status[id] = Status.Failed(it.message ?: "Pairing failed")
        }
    }

    // ---- Lifecycle, driven by MainActivity ----

    /** A remote control window started using this device's connection. */
    fun beginSession(id: String) = synchronized(sessions) { sessions[id] = (sessions[id] ?: 0) + 1 }

    /** A remote control window closed; if Explorer itself already closed, release the connection too. */
    fun endSession(id: String) {
        val idle = synchronized(sessions) {
            val left = (sessions[id] ?: 1) - 1
            if (left <= 0) sessions.remove(id) else sessions[id] = left
            left <= 0
        }
        if (idle && closeRequested) disconnect(id)
    }

    private fun inSession(id: String) = synchronized(sessions) { id in sessions }

    /** Drops connections that died while the app was away (Wi-Fi change, debugging turned off). */
    fun onResume() {
        closeRequested = false
        scope.launch {
            val dead = synchronized(live) { live.filterValues { !it.isConnected }.keys.toList() }
            dead.forEach { id ->
                lockFor(id).withLock { drop(id) }
                status[id] = Status.Idle
            }
        }
    }

    /** Connections stay open in the background; nothing to pause. */
    fun onPause() = Unit

    /** Explorer's main window is finishing: close every connection not used by a remote control window. */
    fun close() {
        closeRequested = true
        scope.launch {
            val ids = synchronized(live) { live.keys.toList() }.filterNot(::inSession)
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

    // ---- Using a device ----

    suspend fun exec(id: String, command: String): ShellResult = withStream(id, shellService(command)) { it.readShellResult() }

    suspend fun pull(id: String, path: String, destination: File, progress: CopyProgress? = null) =
        withStream(id, "sync:") { it.syncRecv(path, destination, progress) }

    suspend fun push(id: String, source: File, path: String, progress: CopyProgress? = null) =
        withStream(id, "sync:") { it.syncSend(source, path, progress) }

    /**
     * Opens a long-lived stream (e.g. remote control's video) on the device's shared connection.
     * The caller owns it and must close it.
     */
    suspend fun openStream(id: String, service: String): AdbStream = withContext(Dispatchers.IO) {
        val adb = connected(id)
        runInterruptible { adb.openStream(service) }
    }

    /** Opens a stream on the device's shared connection (connecting first if needed). */
    private suspend fun <T> withStream(id: String, service: String, block: (AdbStream) -> T): T = withContext(Dispatchers.IO) {
        val adb = connected(id)
        try {
            runInterruptible { adb.openStream(service) }.use(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!adb.isConnected) {
                lockFor(id).withLock { drop(id) }
                status[id] = Status.Idle
            }
            throw e
        }
    }

    private suspend fun connected(id: String): AbsAdbConnectionManager = lockFor(id).withLock {
        val device = device(id) ?: throw IOException("This device was removed")
        // Reuse the open connection unless it died or the user switched to another address.
        synchronized(live) { live[id]?.takeIf { it.isConnected && liveAddress[id] == device.host } }
            ?.let { return@withLock it }
        drop(id)
        status[id] = Status.Connecting
        val adb = AdbManager(app)
        try {
            val connected = tryConnect(adb, device.host, device.connectPort) ||
                (!device.hasFixedPort && reconnectOnNewPort(adb, device))
            if (!connected) throw IOException("Couldn't reach ${device.address}. Is Wireless debugging on?")
            synchronized(live) {
                live[id] = adb
                liveAddress[id] = device.host
            }
            status[id] = Status.Connected
            adb
        } catch (e: CancellationException) {
            runCatching { adb.close() }
            status[id] = Status.Idle
            throw e
        } catch (e: AdbPairingRequiredException) {
            runCatching { adb.close() }
            val message = "Not paired yet — edit the device and enter its pairing code"
            status[id] = Status.Failed(message)
            throw IOException(message, e)
        } catch (e: Exception) {
            runCatching { adb.close() }
            Log.w(TAG, "ADB connect to ${device.address} failed", e)
            val message = e.message ?: "Couldn't connect"
            status[id] = Status.Failed(message)
            throw IOException(message, e)
        }
    }

    private suspend fun tryConnect(adb: AbsAdbConnectionManager, host: String, port: Int): Boolean = try {
        runInterruptible { adb.connect(host, port) }
    } catch (e: AdbPairingRequiredException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Android gives Wireless debugging a new port each time it's switched on. Look for the
     * device's current port on the local network (mDNS) and remember it.
     */
    private suspend fun reconnectOnNewPort(adb: AbsAdbConnectionManager, device: AdbDevice): Boolean {
        val target = runCatching { InetAddress.getByName(device.host) }.getOrNull() ?: return false
        var found = -1
        val mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { address, port ->
            if (port > 0 && address == target) found = port
        }
        mdns.start()
        try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { while (found <= 0) delay(200) }
        } finally {
            mdns.stop()
        }
        if (found <= 0 || found == device.port) return false
        if (!tryConnect(adb, device.host, found)) return false
        val updated = device.copy(port = found)
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) devices[index] = updated
        persist()
        return true
    }

    private fun drop(id: String) {
        val old = synchronized(live) { liveAddress.remove(id); live.remove(id) } ?: return
        runCatching { old.close() }
    }

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun load(): List<AdbDevice> = runCatching {
        val array = JSONArray(prefs.getString(KEY_DEVICES, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            AdbDevice(
                id = o.getString("id"),
                name = o.getString("name"),
                // Older versions saved a single "host".
                hosts = o.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: listOf(o.getString("host")),
                port = o.getInt("port"),
                activeHost = o.optInt("activeHost"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val array = JSONArray()
        devices.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("hosts", JSONArray(it.hosts))
                    .put("activeHost", it.activeHost)
                    .put("port", it.port)
            )
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    companion object {
        private const val TAG = "ExplorerAdbDevices"
        private const val KEY_DEVICES = "devices"
        private const val DISCOVERY_TIMEOUT_MS = 5_000L

        @Volatile
        private var instance: AdbDevices? = null

        fun get(context: Context): AdbDevices =
            instance ?: synchronized(this) {
                instance ?: AdbDevices(context.applicationContext).also { instance = it }
            }
    }
}
