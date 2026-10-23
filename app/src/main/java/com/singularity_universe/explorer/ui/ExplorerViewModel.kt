package com.singularity_universe.explorer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.singularity_universe.explorer.data.Category
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.FileRepository
import com.singularity_universe.explorer.data.SortOrder
import com.singularity_universe.explorer.data.isPreviewable
import com.singularity_universe.explorer.data.isText
import com.singularity_universe.explorer.data.looksLikeText
import com.singularity_universe.explorer.data.resolveFilePath
import com.singularity_universe.explorer.data.toFileItem
import com.singularity_universe.explorer.remote.AdbDevices
import com.singularity_universe.explorer.remote.AdbFiles
import com.singularity_universe.explorer.remote.AdbPath
import com.singularity_universe.explorer.remote.RemoteConnection
import com.singularity_universe.explorer.remote.RemoteFiles
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshFiles
import com.singularity_universe.explorer.remote.SshPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed interface Route {
    data object Home : Route
    data object Recents : Route
    data object Starred : Route
    data object Search : Route
    data object OtherStorage : Route
    data object About : Route
    data object Remote : Route
    data object Settings : Route
    data object Trash : Route
    /** [highlight] is a file inside the folder to scroll to and flash, e.g. after "Open with". */
    data class Folder(val path: String, val highlight: String? = null) : Route
    data class CategoryFiles(val category: Category) : Route
    /** Swipeable viewer over [items] (images and videos), starting at [index]. */
    class Viewer(val items: List<FileItem>, val index: Int) : Route
    /** Read-only view of a text file, with an Edit button that opens the editor. */
    class TextPreview(val item: FileItem) : Route
}

/** Files picked with "Copy to" / "Move to", waiting to be pasted into a folder. */
data class PendingTransfer(val paths: List<String>, val move: Boolean)

class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    val repo = FileRepository(app)
    private val prefs = app.getSharedPreferences("explorer", Context.MODE_PRIVATE)

    val backStack = mutableStateListOf<Route>(Route.Home)

    var hasStorageAccess by mutableStateOf(false)

    private val resources = HashMap<Any, Resource<*>>()

    var busy by mutableStateOf(false)
        private set

    var pendingTransfer by mutableStateOf<PendingTransfer?>(null)

    /** Folders pinned to the navigation drawer, in the order they were added. */
    var shortcuts by mutableStateOf(loadShortcuts())
        private set

    var starred by mutableStateOf(prefs.getStringSet(KEY_STARRED, emptySet())!!.toSet())
        private set

    var showHidden by mutableStateOf(prefs.getBoolean(KEY_HIDDEN, false))
        private set

    init {
        repo.showHidden = showHidden
        // Like Google Files: items stay in Trash for 30 days.
        viewModelScope.launch { runCatching { repo.purgeExpiredTrash() } }
    }

    var sortOrder by mutableStateOf(
        runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null)!!) }.getOrDefault(SortOrder.NameAsc)
    )
        private set

    var gridView by mutableStateOf(prefs.getBoolean(KEY_GRID, false))
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun navigate(route: Route) {
        backStack += route
    }

    /**
     * Opens a local file in the app: images and videos in the viewer (swiping through the other
     * previewable files in [siblings]), and text in the text viewer. Files of unknown type are
     * checked for text first. Anything else goes to [otherwise], usually another app.
     */
    fun openInApp(item: FileItem, siblings: List<FileItem>, otherwise: (FileItem) -> Unit) {
        when {
            item.isPreviewable -> {
                val media = siblings.filter { it.isPreviewable }.ifEmpty { listOf(item) }
                navigate(Route.Viewer(media, media.indexOfFirst { it.path == item.path }.coerceAtLeast(0)))
            }
            item.isText -> navigate(Route.TextPreview(item))
            item.mimeType == "application/octet-stream" -> viewModelScope.launch {
                if (withContext(Dispatchers.IO) { looksLikeText(item.file) }) navigate(Route.TextPreview(item))
                else otherwise(item)
            }
            else -> otherwise(item)
        }
    }

    /** A file was changed outside the file lists (e.g. in the text editor): their sizes and dates are outdated. */
    fun fileChanged() = invalidateAll()

    // ---- Remote access ----

    private var remoteConnection: RemoteConnection? = null

    /**
     * Receives the activity-owned connection. Called from every `onCreate`, but the connection is
     * a singleton, so after a rotation this is a no-op.
     */
    fun attachRemote(connection: RemoteConnection) {
        if (remoteConnection === connection) return
        remoteConnection = connection
        repo.remote = RemoteFiles(connection)
        viewModelScope.launch {
            // Folders that showed "connect remote access" reload as soon as it connects.
            snapshotFlow { connection.status }
                .filter { it == RemoteConnection.Status.Connected }
                .collect {
                    resources.filterKeys { it is Pair<*, *> && it.first == "folder" && repo.isRestricted(it.second as String) }
                        .values.forEach { it.invalidate() }
                }
        }
    }

    private var sshConnections: SshConnections? = null

    /** Receives the activity-owned SSH connections (a process-wide singleton). */
    fun attachSsh(connections: SshConnections) {
        if (sshConnections === connections) return
        sshConnections = connections
        repo.ssh = SshFiles(connections)
    }

    /** Opens a saved server at its start folder (connecting on first use). */
    fun openServer(hostId: String) {
        val connections = sshConnections ?: return
        viewModelScope.launch {
            busy = true
            try {
                val start = connections.startPath(hostId)
                navigate(Route.Folder(SshPath(hostId, start).toString()))
            } catch (e: Exception) {
                message(e.message ?: "Couldn't connect")
            } finally {
                busy = false
            }
        }
    }

    private var adbDevices: AdbDevices? = null

    /** Receives the activity-owned Android device connections (a process-wide singleton). */
    fun attachAdb(devices: AdbDevices) {
        if (adbDevices === devices) return
        adbDevices = devices
        repo.adb = AdbFiles(devices)
    }

    /** Opens another Android device at its internal storage (connecting on first use). */
    fun openDevice(deviceId: String) = navigate(Route.Folder(AdbPath(deviceId, AdbPath.START).toString()))

    /**
     * Name for a folder in titles and shortcuts: "Internal storage", a server's or device's name
     * for its top folder, or the folder name.
     */
    fun displayName(path: String): String {
        if (path == repo.rootDir.path) return "Internal storage"
        SshPath.parse(path)?.let { ssh ->
            return if (ssh.path == "/") sshConnections?.host(ssh.hostId)?.name ?: "Server" else ssh.name
        }
        AdbPath.parse(path)?.let { adb ->
            return if (adb.path == "/" || adb.path == AdbPath.START) adbDevices?.device(adb.deviceId)?.name ?: "Device"
            else adb.name
        }
        return File(path).name
    }

    /** Where breadcrumbs start: internal storage, "/" on a server, or a device's internal storage. */
    fun breadcrumbRoot(path: String): String {
        SshPath.parse(path)?.let { return "${SshPath.SCHEME}${it.hostId}" }
        AdbPath.parse(path)?.let {
            val start = AdbPath(it.deviceId, AdbPath.START)
            return if (it.path.startsWith(AdbPath.START)) start.toString() else "${AdbPath.SCHEME}${it.deviceId}"
        }
        return repo.rootDir.path
    }

    /** The folder containing [path], on this phone or on an SSH server. */
    fun parentOf(path: String): String =
        SshPath.parse(path)?.let { SshPath(it.hostId, it.parent).toString() }
            ?: AdbPath.parse(path)?.let { AdbPath(it.deviceId, it.parent).toString() }
            ?: File(path).parent ?: repo.rootDir.path

    /** Opens the folder containing [item], scrolled to it and highlighted. */
    fun locate(item: FileItem) = navigate(Route.Folder(parentOf(item.path), highlight = item.path))

    /** Starts "Copy to" / "Move to" for [paths]: the user then picks a folder and taps Copy/Move here. */
    fun startTransfer(paths: List<String>, move: Boolean) {
        pendingTransfer = PendingTransfer(paths, move)
        navigate(Route.Folder(parentOf(paths.first())))
        message("Open a folder, then tap ${if (move) "Move here" else "Copy here"}")
    }

    /** Where a folder is, for subtitles: its path, or "Server name · /path" on an SSH server. */
    fun displayLocation(path: String): String {
        SshPath.parse(path)?.let { return "${sshConnections?.host(it.hostId)?.name ?: "Server"} · ${it.path}" }
        AdbPath.parse(path)?.let { return "${adbDevices?.device(it.deviceId)?.name ?: "Device"} · ${it.path}" }
        return path
    }

    /** Copies a file out of Android/data so another app can open it, then hands it to [open]. */
    fun openRestricted(item: FileItem, open: (FileItem) -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                val local = repo.copyForOpening(item, getApplication<Application>().cacheDir)
                open(local.toFileItem())
            } catch (e: Exception) {
                message(e.message ?: "Couldn't open this file")
            } finally {
                busy = false
            }
        }
    }

    // ---- Drawer shortcuts ----

    fun addShortcut(path: String) {
        if (path !in shortcuts) toggleShortcut(path)
    }

    fun toggleShortcut(path: String) {
        val added = path !in shortcuts
        shortcuts = if (added) shortcuts + path else shortcuts - path
        prefs.edit().putString(KEY_SHORTCUTS, JSONArray(shortcuts).toString()).apply()
        message(if (added) "Added to drawer" else "Removed from drawer")
    }

    private fun loadShortcuts(): List<String> = runCatching {
        val json = JSONArray(prefs.getString(KEY_SHORTCUTS, "[]"))
        List(json.length()) { json.getString(it) }
    }.getOrDefault(emptyList())

    // ---- Cached screen data ----

    @Suppress("UNCHECKED_CAST")
    private fun <T> resource(key: Any, loader: suspend () -> T): Resource<T> =
        resources.getOrPut(key) { Resource(viewModelScope, loader) } as Resource<T>

    fun folder(path: String) = resource("folder" to path) { repo.listFolder(path, showHidden) }
    fun category(category: Category) = resource(category) { repo.categoryFiles(category) }
    fun apps() = resource("apps") { repo.installedApps() }
    fun recents() = resource("recents") { repo.recents(limit = 200) }
    fun storage() = resource("storage") { repo.storageInfo() }
    fun categorySizes() = resource("sizes") {
        coroutineScope {
            Category.entries.map { async { it to repo.categorySize(it) } }.awaitAll().toMap()
        }
    }
    fun trash() = resource("trash") { repo.trashItems() }
    fun starredFiles() = resource("starred") {
        withContext(Dispatchers.IO) { starred.map(::File).filter { it.exists() }.map { it.toFileItem() } }
    }

    /** Marks every cached list outdated, e.g. after files were changed. */
    private fun invalidateAll() {
        resources.values.forEach { it.invalidate() }
    }

    /** Handles "Open with Explorer" from another app: shows the folder containing the file. */
    fun openExternal(uri: Uri, mimeType: String? = null) {
        viewModelScope.launch {
            val path = resolveFilePath(getApplication(), uri)
            if (path == null) {
                message("Couldn't find where this file is stored")
                return@launch
            }
            val file = File(path)
            val isFolder = file.isDirectory || mimeType in FOLDER_MIME_TYPES
            val route = if (isFolder) {
                Route.Folder(file.path)
            } else {
                Route.Folder(file.parent ?: repo.rootDir.path, highlight = file.path)
            }
            // The file may be new since this folder was last listed.
            folder(route.path).invalidate()
            backStack.clear()
            backStack += Route.Home
            backStack += route
        }
    }

    fun goHome() {
        backStack.clear()
        backStack += Route.Home
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun toggleStar(paths: Collection<String>) {
        val allStarred = paths.all { it in starred }
        starred = if (allStarred) starred - paths.toSet() else starred + paths
        prefs.edit().putStringSet(KEY_STARRED, starred).apply()
        starredFiles().invalidate()
        message(if (allStarred) "Removed from Starred" else "Added to Starred")
    }

    fun updateShowHidden(value: Boolean) {
        showHidden = value
        repo.showHidden = value
        prefs.edit().putBoolean(KEY_HIDDEN, value).apply()
        // Folders, categories, recents and sizes all depend on this setting.
        invalidateAll()
    }

    fun updateSortOrder(value: SortOrder) {
        sortOrder = value
        prefs.edit().putString(KEY_SORT, value.name).apply()
    }

    fun updateGridView(value: Boolean) {
        gridView = value
        prefs.edit().putBoolean(KEY_GRID, value).apply()
    }

    fun message(text: String) {
        messageChannel.trySend(text)
    }

    /** Runs a file operation off the main thread, reporting the outcome and refreshing screens. */
    fun runOperation(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
                successMessage?.let(::message)
            } catch (e: Exception) {
                Log.w("Explorer", "Operation failed", e)
                message(e.message ?: "Something went wrong")
            } finally {
                busy = false
                invalidateAll()
            }
        }
    }

    /** Phone files go to Trash; files on servers, in Android/data or on SD cards are deleted for good. */
    fun delete(paths: Collection<String>) {
        val (toTrash, permanent) = paths.partition(repo::canTrash)
        val summary = when {
            permanent.isEmpty() -> "Moved ${plural(toTrash.size)} to Trash"
            toTrash.isEmpty() -> "Deleted ${plural(permanent.size)}"
            else -> "Moved ${plural(toTrash.size)} to Trash, deleted ${plural(permanent.size)}"
        }
        runOperation(summary) {
            if (toTrash.isNotEmpty()) repo.moveToTrash(toTrash)
            if (permanent.isNotEmpty()) repo.delete(permanent)
            forgetStarred(paths)
        }
    }

    fun restoreFromTrash(ids: Collection<String>) =
        runOperation("Restored ${plural(ids.size)}") { repo.restoreFromTrash(ids) }

    fun deleteFromTrash(ids: Collection<String>) =
        runOperation("Deleted ${plural(ids.size)} for good") { repo.deleteFromTrash(ids) }

    fun emptyTrash() = runOperation("Trash emptied") { repo.emptyTrash() }

    private fun forgetStarred(paths: Collection<String>) {
        if (paths.any { it in starred }) {
            starred = starred - paths.toSet()
            prefs.edit().putStringSet(KEY_STARRED, starred).apply()
        }
    }

    fun rename(path: String, newName: String) = runOperation("Renamed") {
        repo.rename(path, newName)
        if (path in starred) {
            starred = starred - path + (path.substringBeforeLast('/') + "/" + newName)
            prefs.edit().putStringSet(KEY_STARRED, starred).apply()
        }
    }

    fun createFolder(parent: String, name: String) = runOperation("Folder created") {
        repo.createFolder(parent, name)
    }

    fun pasteInto(destDir: String) {
        val transfer = pendingTransfer ?: return
        pendingTransfer = null
        val verb = if (transfer.move) "Moved" else "Copied"
        runOperation("$verb ${plural(transfer.paths.size)}") {
            repo.transfer(transfer.paths, destDir, transfer.move)
        }
    }

    companion object {
        private const val KEY_STARRED = "starred"
        private const val KEY_HIDDEN = "show_hidden"
        private const val KEY_SORT = "sort"
        private const val KEY_GRID = "grid"
        private const val KEY_SHORTCUTS = "shortcuts"

        /** Mime types other apps use when asking to open a folder. */
        private val FOLDER_MIME_TYPES = setOf("resource/folder", "inode/directory", "vnd.android.document/directory")

        fun plural(count: Int) = if (count == 1) "1 item" else "$count items"
    }
}
