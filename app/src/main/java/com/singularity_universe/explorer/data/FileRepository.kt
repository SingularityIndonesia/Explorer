package com.singularity_universe.explorer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import com.singularity_universe.explorer.remote.RemoteAccessRequiredException
import com.singularity_universe.explorer.remote.RemoteFiles
import com.singularity_universe.explorer.remote.SshFiles
import com.singularity_universe.explorer.remote.SshPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class FileRepository(private val context: Context) {

    val rootDir: File = Environment.getExternalStorageDirectory()

    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** When false, MediaStore results skip dot-files and anything inside a dot-folder. */
    @Volatile
    var showHidden = false

    /** Set by the activity: reaches Android/data and Android/obb through Wireless debugging. */
    @Volatile
    var remote: RemoteFiles? = null

    /** Set by the activity: SSH servers the user added. */
    @Volatile
    var ssh: SshFiles? = null

    fun isRestricted(path: String): Boolean = RemoteFiles.isRestricted(path)

    fun isSsh(path: String): Boolean = path.startsWith(SshPath.SCHEME)

    /** Files other apps can't read directly: they get a local copy first. */
    fun needsCopyToOpen(path: String): Boolean = isRestricted(path) || isSsh(path)

    private fun sshFiles(): SshFiles = ssh ?: throw IOException("SSH isn't available")

    private fun connectedRemote(): RemoteFiles =
        remote?.takeIf { it.isConnected } ?: throw RemoteAccessRequiredException()

    private fun visible(selection: String): String =
        if (showHidden) selection else "($selection) AND ${FileColumns.DATA} NOT LIKE '%/.%'"

    // ---- Browsing ----

    suspend fun listFolder(path: String, showHidden: Boolean): List<FileItem> = withContext(Dispatchers.IO) {
        if (isSsh(path)) return@withContext sshFiles().list(path, showHidden)
        if (isRestricted(path)) return@withContext connectedRemote().list(path, showHidden)
        val items = File(path).listFiles().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toFileItem(showHidden) }
        val mediaPaths = items.filter { it.kind == FileKind.Image || it.kind == FileKind.Video }.map { it.path }
        val uris = mediaUrisFor(mediaPaths)
        items.map { item -> uris[item.path]?.let { item.copy(mediaUri = it) } ?: item }
    }

    /** Looks up MediaStore URIs for files listed straight from disk, so they can use cached thumbnails. */
    private fun mediaUrisFor(paths: List<String>): Map<String, Uri> {
        val result = HashMap<String, Uri>()
        paths.chunked(500).forEach { chunk ->
            val selection = "${FileColumns.DATA} IN (${chunk.joinToString { "?" }})"
            context.contentResolver.query(
                filesUri,
                arrayOf(FileColumns.DATA, FileColumns._ID, FileColumns.MEDIA_TYPE),
                queryArgs(selection, chunk.toTypedArray(), null, null),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    mediaUri(c.getLong(1), c.getInt(2))?.let { result[c.getString(0)] = it }
                }
            }
        }
        return result
    }

    private fun mediaUri(id: Long, mediaType: Int): Uri? = when (mediaType) {
        FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        else -> null
    }

    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val stat = StatFs(rootDir.path)
        StorageInfo(total = stat.totalBytes, free = stat.availableBytes)
    }

    suspend fun recents(limit: Int = 30): List<FileItem> = query(
        selection = "(${FileColumns.MEDIA_TYPE} IN (?, ?) OR ${FileColumns.RELATIVE_PATH} LIKE ?)",
        args = arrayOf(
            FileColumns.MEDIA_TYPE_IMAGE.toString(),
            FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "${Environment.DIRECTORY_DOWNLOADS}/%",
        ),
        sort = "${FileColumns.DATE_ADDED} DESC",
        limit = limit,
    )

    suspend fun search(text: String, category: Category?): List<FileItem> {
        val (catSelection, catArgs) = category?.let { categorySelection(it) } ?: (null to emptyArray())
        val selection = buildString {
            append("${FileColumns.DISPLAY_NAME} LIKE ?")
            if (catSelection != null) append(" AND ($catSelection)")
        }
        return query(selection, arrayOf("%$text%", *catArgs), limit = 300)
    }

    // ---- Categories ----

    suspend fun categoryFiles(category: Category): List<FileItem> {
        val (selection, args) = categorySelection(category) ?: return emptyList()
        return query(selection, args)
    }

    suspend fun categorySize(category: Category): Long = withContext(Dispatchers.IO) {
        if (category == Category.Apps) return@withContext userApps().sumOf { it.apkSize() }
        val (selection, args) = categorySelection(category) ?: return@withContext 0L
        var total = 0L
        context.contentResolver.query(
            filesUri, arrayOf(FileColumns.SIZE), queryArgs(visible(selection), args, null, null), null,
        )?.use { c -> while (c.moveToNext()) total += c.getLong(0) }
        total
    }

    private fun categorySelection(category: Category): Pair<String, Array<String>>? {
        val notDir = "${FileColumns.MIME_TYPE} IS NOT NULL"
        return when (category) {
            Category.Images -> "${FileColumns.MEDIA_TYPE} = ?" to arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString())
            Category.Videos -> "${FileColumns.MEDIA_TYPE} = ?" to arrayOf(FileColumns.MEDIA_TYPE_VIDEO.toString())
            Category.Audio -> "${FileColumns.MEDIA_TYPE} = ?" to arrayOf(FileColumns.MEDIA_TYPE_AUDIO.toString())
            Category.Downloads -> "$notDir AND ${FileColumns.RELATIVE_PATH} LIKE ?" to
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/%")
            Category.Documents -> {
                val placeholders = DOCUMENT_MIMES.joinToString { "?" }
                "(${FileColumns.MIME_TYPE} IN ($placeholders) OR ${FileColumns.MIME_TYPE} LIKE ?)" to
                    (DOCUMENT_MIMES.toTypedArray() + "text/%")
            }
            Category.Apps -> null
        }
    }

    suspend fun installedApps(): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        userApps()
            .map { info ->
                AppItem(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    size = info.apkSize(),
                    apkPath = info.sourceDir,
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedByDescending { it.size }
    }

    private fun userApps(): List<ApplicationInfo> = context.packageManager.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }

    private fun ApplicationInfo.apkSize(): Long =
        (listOf(sourceDir) + splitSourceDirs.orEmpty()).sumOf { File(it).length() }

    // ---- File operations ----

    suspend fun createFolder(parent: String, name: String): Unit = withContext(Dispatchers.IO) {
        if (isSsh(parent)) return@withContext sshFiles().createFolder(parent, name)
        if (isRestricted(parent)) return@withContext connectedRemote().createFolder("$parent/$name")
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("\"$name\" already exists")
        if (!dir.mkdirs()) throw IOException("Couldn't create folder")
    }

    suspend fun rename(path: String, newName: String): Unit = withContext(Dispatchers.IO) {
        if (isSsh(path)) return@withContext sshFiles().rename(path, newName)
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (isRestricted(path)) return@withContext connectedRemote().rename(path, dst.path)
        if (dst.exists()) throw IOException("\"$newName\" already exists")
        val scanTargets = filesUnder(src)
        if (!src.renameTo(dst)) throw IOException("Couldn't rename")
        scan(scanTargets + filesUnder(dst))
    }

    suspend fun delete(paths: Collection<String>): Unit = withContext(Dispatchers.IO) {
        val (onServers, onDevice) = paths.partition(::isSsh)
        if (onServers.isNotEmpty()) sshFiles().delete(onServers)
        val (hidden, normal) = onDevice.partition(::isRestricted)
        if (hidden.isNotEmpty()) connectedRemote().delete(hidden)
        val scanTargets = normal.flatMap { filesUnder(File(it)) }
        val failed = normal.count { !File(it).deleteRecursively() }
        scan(scanTargets)
        if (failed > 0) throw IOException("Couldn't delete $failed item(s)")
    }

    suspend fun transfer(paths: Collection<String>, destDir: String, move: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val dest = File(destDir)
            val scanTargets = mutableListOf<String>()
            for (path in paths) {
                if (isSsh(path) || isSsh(destDir)) {
                    scanTargets += transferSsh(path, destDir, move)
                    continue
                }
                val src = File(path)
                if (dest.absolutePath == src.absolutePath || dest.absolutePath.startsWith(src.absolutePath + "/")) {
                    throw IOException("Can't ${if (move) "move" else "copy"} a folder into itself")
                }
                if (move && src.parentFile?.absolutePath == dest.absolutePath) continue
                if (isRestricted(path) || isRestricted(destDir)) {
                    // Android/data on either side: only the shell user can read/write it.
                    if (move && !isRestricted(path)) scanTargets += filesUnder(src)
                    val target = connectedRemote().transfer(path, destDir, move)
                    if (!isRestricted(target)) scanTargets += filesUnder(File(target))
                    continue
                }
                val target = uniqueTarget(dest, src.name)
                if (move) scanTargets += filesUnder(src)
                val renamed = move && src.renameTo(target)
                if (!renamed) {
                    if (!src.copyRecursively(target)) throw IOException("Couldn't copy ${src.name}")
                    if (move) src.deleteRecursively()
                }
                scanTargets += filesUnder(target)
            }
            scan(scanTargets)
        }

    /**
     * One item of a copy/move where an SSH server is involved. Returns local paths to rescan.
     * Android/data can't take part: it's only reachable through a different connection.
     */
    private suspend fun transferSsh(path: String, destDir: String, move: Boolean): List<String> {
        if (isRestricted(path) || isRestricted(destDir)) {
            throw IOException("Copy between Android/data and a server isn't supported — copy via another folder")
        }
        val files = sshFiles()
        val from = SshPath.parse(path)
        val to = SshPath.parse(destDir)
        return when {
            from != null && to != null && from.hostId == to.hostId -> {
                if (move && from.parent == to.path) return emptyList()
                files.transferWithin(path, destDir, move)
                emptyList()
            }
            from != null && to != null -> {
                // Between two servers: through a temporary local copy.
                val temp = File(context.cacheDir, "transfer/${System.nanoTime()}").apply { mkdirs() }
                try {
                    files.upload(files.download(path, temp), destDir)
                } finally {
                    temp.deleteRecursively()
                }
                if (move) files.delete(listOf(path))
                emptyList()
            }
            from != null -> {
                val local = files.download(path, File(destDir))
                if (move) files.delete(listOf(path))
                filesUnder(local)
            }
            else -> {
                val src = File(path)
                files.upload(src, destDir)
                if (move) {
                    val removed = filesUnder(src)
                    src.deleteRecursively()
                    removed
                } else {
                    emptyList()
                }
            }
        }
    }

    /** Copies a file from Android/data or a server into app storage so it can be opened. */
    suspend fun copyForOpening(item: FileItem, cacheDir: File): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "remote/${item.name}")
        if (isSsh(item.path)) sshFiles().downloadTo(item.path, target) else connectedRemote().pull(item.path, target)
        target
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (candidate.exists()) candidate = File(dir, "$base ($i)$ext").also { i++ }
        return candidate
    }

    private fun filesUnder(file: File): List<String> =
        file.walkTopDown().take(2000).map { it.absolutePath }.toList()

    private fun scan(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    // ---- MediaStore ----

    private fun queryArgs(selection: String?, args: Array<String>?, sort: String?, limit: Int?) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        sort?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
        limit?.let { putInt(ContentResolver.QUERY_ARG_LIMIT, it) }
    }

    private suspend fun query(
        selection: String,
        args: Array<String>,
        sort: String = "${FileColumns.DATE_MODIFIED} DESC",
        limit: Int? = null,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            FileColumns.DATA,
            FileColumns.DISPLAY_NAME,
            FileColumns.SIZE,
            FileColumns.DATE_MODIFIED,
            FileColumns.MIME_TYPE,
            FileColumns._ID,
            FileColumns.MEDIA_TYPE,
        )
        val result = mutableListOf<FileItem>()
        context.contentResolver.query(filesUri, projection, queryArgs(visible(selection), args, sort, limit), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val file = File(path)
                    if (!file.exists()) continue
                    if (file.isDirectory) {
                        result += file.toFileItem()
                        continue
                    }
                    val name = c.getString(1) ?: file.name
                    result += FileItem(
                        path = path,
                        name = name,
                        isDirectory = false,
                        size = c.getLong(2),
                        lastModified = c.getLong(3) * 1000,
                        mimeType = c.getString(4) ?: mimeTypeOf(name),
                        mediaUri = mediaUri(c.getLong(5), c.getInt(6)),
                    )
                }
            }
        result
    }
}
