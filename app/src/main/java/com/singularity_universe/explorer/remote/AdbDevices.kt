package com.singularity_universe.explorer.remote

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
    val host: String,
    /** The "IP address & Port" port on the device's Wireless debugging screen (changes when toggled). */
    val port: Int,
) {
    val address: String get() = "$host:$port"
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

    /** Drops connections that died while the app was away (Wi-Fi change, debugging turned off). */
    fun onResume() {
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

    /** The activity is finishing: close every connection. */
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

    // ---- Using a device ----

    suspend fun exec(id: String, command: String): ShellResult = withStream(id, shellService(command)) { it.readShellResult() }

    suspend fun pull(id: String, path: String, destination: File) = withStream(id, "sync:") { it.syncRecv(path, destination) }

    suspend fun push(id: String, source: File, path: String) = withStream(id, "sync:") { it.syncSend(source, path) }

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
        synchronized(live) { live[id] }?.takeIf { it.isConnected }?.let { return@withLock it }
        drop(id)
        val device = device(id) ?: throw IOException("This device was removed")
        status[id] = Status.Connecting
        val adb = AdbManager(app)
        try {
            val connected = tryConnect(adb, device.host, device.port) || reconnectOnNewPort(adb, device)
            if (!connected) throw IOException("Couldn't reach ${device.address}. Is Wireless debugging on?")
            synchronized(live) { live[id] = adb }
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
        val old = synchronized(live) { live.remove(id) } ?: return
        runCatching { old.close() }
    }

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun load(): List<AdbDevice> = runCatching {
        val array = JSONArray(prefs.getString(KEY_DEVICES, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            AdbDevice(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"))
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val array = JSONArray()
        devices.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port))
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
