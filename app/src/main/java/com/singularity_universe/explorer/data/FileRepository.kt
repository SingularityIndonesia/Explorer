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
import com.singularity_universe.explorer.remote.AdbFiles
import com.singularity_universe.explorer.remote.AdbPath
import com.singularity_universe.explorer.remote.RemoteAccessRequiredException
import com.singularity_universe.explorer.remote.RemoteCommands
import com.singularity_universe.explorer.remote.RemoteFiles
import com.singularity_universe.explorer.remote.SshFiles
import com.singularity_universe.explorer.remote.SshPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs

class FileRepository(private val context: Context) {

    val rootDir: File = Environment.getExternalStorageDirectory()

    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    val trash = Trash(rootDir)

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

    /** Set by the activity: other Android devices reached over their Wireless debugging. */
    @Volatile
    var adb: AdbFiles? = null

    fun isSsh(path: String): Boolean = path.startsWith(SshPath.SCHEME)

    fun isAdbDevice(path: String): Boolean = path.startsWith(AdbPath.SCHEME)

    /** On another machine: an SSH server or another Android device. */
    fun isRemote(path: String): Boolean = isSsh(path) || isAdbDevice(path)

    /** Files other apps can't read directly: they get a local copy first. */
    fun needsCopyToOpen(path: String): Boolean = isRestricted(path) || isRemote(path)

    private fun sshFiles(): SshFiles = ssh ?: throw IOException("SSH isn't available")

    private fun adbFiles(): AdbFiles = adb ?: throw IOException("Android devices aren't available")

    private fun connectedRemote(): RemoteFiles =
        remote?.takeIf { it.isConnected } ?: throw RemoteAccessRequiredException()

    private fun visible(selection: String): String =
        if (showHidden) selection else "($selection) AND ${FileColumns.DATA} NOT LIKE '%/.%'"

    // ---- Browsing ----

    suspend fun listFolder(path: String, showHidden: Boolean): List<FileItem> = withContext(Dispatchers.IO) {
        if (isSsh(path)) return@withContext sshFiles().list(path, showHidden)
        if (isAdbDevice(path)) return@withContext adbFiles().list(path, showHidden)
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
        // Modified date, not date added: it's the date rows show and Recents groups by month.
        sort = "${FileColumns.DATE_MODIFIED} DESC",
        // Over-fetch so dropping folders (the Download/ branch matches subfolders too) still fills the list.
        limit = limit * 2,
    ).filterNot { it.isDirectory }.take(limit)

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
        if (isAdbDevice(parent)) return@withContext adbFiles().createFolder(parent, name)
        if (isRestricted(parent)) return@withContext connectedRemote().createFolder("$parent/$name")
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("\"$name\" already exists")
        if (!dir.mkdirs()) throw IOException("Couldn't create folder")
    }

    suspend fun rename(path: String, newName: String): Unit = withContext(Dispatchers.IO) {
        if (isSsh(path)) return@withContext sshFiles().rename(path, newName)
        if (isAdbDevice(path)) return@withContext adbFiles().rename(path, newName)
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (isRestricted(path)) return@withContext connectedRemote().rename(path, dst.path)
        if (dst.exists()) throw IOException("\"$newName\" already exists")
        val scanTargets = filesUnder(src)
        if (!src.renameTo(dst)) throw IOException("Couldn't rename")
        scan(scanTargets + filesUnder(dst))
    }

    /**
     * Whether deleting [path] moves it to Trash. Only internal storage qualifies: servers,
     * Android/data and SD cards can't be moved into the trash folder cheaply.
     */
    fun canTrash(path: String): Boolean =
        path.startsWith(rootDir.path + "/") && !isRestricted(path) && !trash.isInside(path)

    suspend fun moveToTrash(paths: Collection<String>): Unit = withContext(Dispatchers.IO) {
        val scanTargets = paths.flatMap { filesUnder(File(it)) }
        trash.moveIn(paths)
        scan(scanTargets)
    }

    suspend fun trashItems(): List<TrashItem> = withContext(Dispatchers.IO) { trash.items() }

    suspend fun restoreFromTrash(ids: Collection<String>): Unit = withContext(Dispatchers.IO) {
        val restored = trash.restore(ids)
        scan(restored.flatMap { filesUnder(File(it)) })
    }

    suspend fun deleteFromTrash(ids: Collection<String>): Unit = withContext(Dispatchers.IO) { trash.deleteForever(ids) }

    suspend fun emptyTrash(): Unit = withContext(Dispatchers.IO) { trash.empty() }

    suspend fun purgeExpiredTrash(): Unit = withContext(Dispatchers.IO) { trash.purgeExpired() }

    suspend fun delete(paths: Collection<String>): Unit = withContext(Dispatchers.IO) {
        val (remote, onDevice) = paths.partition(::isRemote)
        if (remote.isNotEmpty()) remoteDelete(remote)
        val (hidden, normal) = onDevice.partition(::isRestricted)
        if (hidden.isNotEmpty()) connectedRemote().delete(hidden)
        val scanTargets = normal.flatMap { filesUnder(File(it)) }
        val failed = normal.count { !File(it).deleteRecursively() }
        scan(scanTargets)
        if (failed > 0) throw IOException("Couldn't delete $failed item(s)")
    }

    suspend fun transfer(paths: Collection<String>, destDir: String, move: Boolean, progress: CopyProgress? = null): Unit =
        withContext(Dispatchers.IO) {
            val dest = File(destDir)
            val scanTargets = mutableListOf<String>()
            for (path in paths) {
                if (isRemote(path) || isRemote(destDir)) {
                    scanTargets += transferRemote(path, destDir, move, progress)
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
                val size = if (move) FileCopier.sizeOf(src) else 0L
                val renamed = move && src.renameTo(target)
                if (renamed) {
                    progress?.add(size)
                } else {
                    try {
                        FileCopier.copy(src, target, progress)
                    } catch (e: CopyCancelledException) {
                        // Cancelled: nothing half-copied is left behind (and a move keeps its source).
                        target.deleteRecursively()
                        throw e
                    }
                    if (move) src.deleteRecursively()
                }
                scanTargets += filesUnder(target)
            }
            scan(scanTargets)
        }

    /**
     * One item of a copy/move where an SSH server or another Android device is involved.
     * Returns local paths to rescan. Android/data can't take part: it's only reachable through
     * a different connection.
     */
    private suspend fun transferRemote(path: String, destDir: String, move: Boolean, progress: CopyProgress?): List<String> {
        if (isRestricted(path) || isRestricted(destDir)) {
            throw IOException("Copy between Android/data and a server or device isn't supported — copy via another folder")
        }
        val sameMachine = remoteMachine(path) != null && remoteMachine(path) == remoteMachine(destDir)
        return when {
            sameMachine -> {
                if (move && remoteParent(path) == remoteParent("$destDir/x")) return emptyList()
                if (isSsh(path)) sshFiles().transferWithin(path, destDir, move)
                else adbFiles().transferWithin(path, destDir, move)
                emptyList()
            }
            isRemote(path) && isRemote(destDir) -> {
                // Between two machines: through a temporary local copy.
                val temp = File(context.cacheDir, "transfer/${System.nanoTime()}").apply { mkdirs() }
                try {
                    remoteUpload(remoteDownload(path, temp, progress), destDir, progress)
                } finally {
                    temp.deleteRecursively()
                }
                if (move) remoteDelete(listOf(path))
                emptyList()
            }
            isRemote(path) -> {
                val local = remoteDownload(path, File(destDir), progress)
                if (move) remoteDelete(listOf(path))
                filesUnder(local)
            }
            else -> {
                val src = File(path)
                remoteUpload(src, destDir, progress)
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

    /** "ssh:<id>" or "adb:<id>": paths on the same machine can be copied there directly. */
    private fun remoteMachine(path: String): String? =
        SshPath.parse(path)?.let { "ssh:${it.hostId}" } ?: AdbPath.parse(path)?.let { "adb:${it.deviceId}" }

    private fun remoteParent(path: String): String? =
        SshPath.parse(path)?.let { SshPath(it.hostId, it.parent).toString() }
            ?: AdbPath.parse(path)?.let { AdbPath(it.deviceId, it.parent).toString() }

    private suspend fun remoteDownload(path: String, localDir: File, progress: CopyProgress? = null): File =
        if (isSsh(path)) sshFiles().download(path, localDir, progress) else adbFiles().download(path, localDir, progress)

    private suspend fun remoteUpload(local: File, destDir: String, progress: CopyProgress? = null): String =
        if (isSsh(destDir)) sshFiles().upload(local, destDir, progress) else adbFiles().upload(local, destDir, progress)

    private suspend fun remoteDelete(paths: Collection<String>) {
        val (onServers, onDevices) = paths.partition(::isSsh)
        if (onServers.isNotEmpty()) sshFiles().delete(onServers)
        if (onDevices.isNotEmpty()) adbFiles().delete(onDevices)
    }

    // ---- Storage cleanup ----

    /** What Free up space found. Each list is biggest first. */
    class CleanupReport(
        val large: List<FileItem>,
        val duplicates: List<List<FileItem>>,
        val oldDownloads: List<FileItem>,
        val apks: List<ApkFile>,
        /** Groups of similar photos, sharpest first (the one to keep). */
        val similar: List<List<FileItem>> = emptyList(),
        val blurry: List<FileItem> = emptyList(),
    )

    /** An installer file; [installed] when that version (or newer) of the app is on the phone. */
    class ApkFile(val item: FileItem, val label: String?, val installed: Boolean)

    /** Scans shared storage for space to free. [onStage] reports what it's doing (and 0..1 when known). */
    suspend fun cleanupScan(onStage: (String, Float?) -> Unit = { _, _ -> }): CleanupReport = withContext(Dispatchers.IO) {
        val bySize = "${FileColumns.SIZE} DESC"
        onStage("Looking for large files", null)
        val large = query("${FileColumns.SIZE} >= ?", arrayOf(LARGE_FILE.toString()), sort = bySize, limit = 300).filterNot { it.isDirectory }

        onStage("Looking for old downloads", null)
        val cutoff = (System.currentTimeMillis() - OLD_DOWNLOAD_DAYS * 24L * 3600 * 1000) / 1000
        val oldDownloads = query(
            "${FileColumns.RELATIVE_PATH} LIKE ? AND ${FileColumns.DATE_MODIFIED} < ? AND ${FileColumns.SIZE} > 0",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/%", cutoff.toString()),
            sort = bySize,
            limit = 500,
        ).filterNot { it.isDirectory }

        onStage("Looking for installer files", null)
        val apks = query(
            "(${FileColumns.MIME_TYPE} = ? OR ${FileColumns.DISPLAY_NAME} LIKE ?)",
            arrayOf(APK_MIME, "%.apk"),
            sort = bySize,
            limit = 300,
        ).filterNot { it.isDirectory }.map(::apkFile)

        onStage("Looking for duplicates", 0f)
        val candidates = query("${FileColumns.SIZE} >= ?", arrayOf(DUPLICATE_MIN.toString()), sort = bySize, limit = 20_000)
            .filterNot { it.isDirectory }
        val byPath = candidates.associateBy { it.path }
        val duplicates = Duplicates.find(candidates.map { it.file }, minSize = DUPLICATE_MIN) { onStage("Looking for duplicates", it) }
            .map { group -> group.mapNotNull { byPath[it.path] ?: runCatching { it.toFileItem() }.getOrNull() } }
            .filter { it.size > 1 }

        val (similar, blurry) = photoReview(onStage)
        CleanupReport(large, duplicates, oldDownloads, apks, similar, blurry)
    }

    /**
     * Similar photos (bursts, retakes) and blurry ones, judged from MediaStore's small cached
     * thumbnails rather than the photos themselves.
     */
    private suspend fun photoReview(onStage: (String, Float?) -> Unit): Pair<List<List<FileItem>>, List<FileItem>> {
        onStage("Looking at photos", 0f)
        val photos = query("${FileColumns.MEDIA_TYPE} = ?", arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString()), limit = PHOTO_REVIEW_LIMIT)
            .filter { !it.isDirectory && it.mediaUri != null }
        val hashes = ArrayList<Pair<FileItem, Long>>(photos.size)
        val sharpness = HashMap<String, Double>()
        photos.forEachIndexed { index, photo ->
            runCatching {
                val thumb = context.contentResolver.loadThumbnail(photo.mediaUri!!, android.util.Size(256, 256), null)
                sharpness[photo.path] = PhotoSimilarity.sharpness(grayscale(thumb), thumb.width, thumb.height)
                val tiny = android.graphics.Bitmap.createScaledBitmap(thumb, PhotoSimilarity.HASH_WIDTH, PhotoSimilarity.HASH_HEIGHT, true)
                hashes += photo to PhotoSimilarity.dHash(grayscale(tiny))
                if (tiny !== thumb) tiny.recycle()
                thumb.recycle()
            }
            if (index % 25 == 0) onStage("Looking at photos", index.toFloat() / photos.size)
        }
        val similar = PhotoSimilarity.group(hashes).map { group -> group.sortedByDescending { sharpness[it.path] ?: 0.0 } }
            .sortedByDescending { group -> group.drop(1).sumOf { it.size } }
        // Screenshots are often flat on purpose; they're not "blurry photos".
        val blurry = photos.filter { (sharpness[it.path] ?: Double.MAX_VALUE) < PhotoSimilarity.BLURRY_BELOW && !it.path.contains("/Screenshots/", ignoreCase = true) }
            .sortedBy { sharpness[it.path] }
        return similar to blurry
    }

    private fun grayscale(bitmap: android.graphics.Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return pixels
    }

    private fun apkFile(item: FileItem): ApkFile {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(item.path, 0) }.getOrNull()
        val label = info?.applicationInfo?.let { app ->
            app.sourceDir = item.path
            app.publicSourceDir = item.path
            runCatching { app.loadLabel(pm).toString() }.getOrNull()
        }
        val installed = info != null && runCatching {
            pm.getPackageInfo(info.packageName, 0).longVersionCode >= info.longVersionCode
        }.getOrDefault(false)
        return ApkFile(item, label, installed)
    }

    // ---- Zip files ----

    /** The phone's Downloads folder (where archives from servers are extracted). */
    val downloadsDir: File get() = File(rootDir, Environment.DIRECTORY_DOWNLOADS)

    /**
     * Extracts [only] (or everything) from the local [archive] into a new folder in [destDir],
     * named after [archiveName]. Paths inside are taken relative to [base]. Returns the folder.
     */
    suspend fun extractArchive(archive: File, archiveName: String, destDir: String, only: Collection<String>?, base: String, password: String?): File =
        withContext(Dispatchers.IO) {
            val folder = uniqueTarget(File(destDir), Archives.baseName(archiveName))
            val written = try {
                Archives.extract(archive, folder, only, base, password)
            } catch (e: Exception) {
                folder.deleteRecursively()
                throw e
            }
            scan(written.map { it.path })
            folder
        }

    /**
     * Whether [paths] can be compressed where they are: on this phone, or all in one folder of one
     * server or device (Android/data can't).
     */
    fun canCompress(paths: Collection<String>): Boolean = paths.isNotEmpty() && paths.none(::isRestricted) &&
        (paths.none(::isRemote) || (paths.all(::isRemote) && paths.map(::remoteParent).distinct().size == 1))

    /**
     * Compresses [paths] (all in one folder of a server or device) on that machine, without
     * downloading them: a zip if the server has `zip`, otherwise a .tar.gz (always on Android
     * devices). Returns the archive's path.
     */
    suspend fun compressRemote(paths: List<String>, name: String): String = withContext(Dispatchers.IO) {
        val folder = remoteParent(paths.first()) ?: throw IOException("Not on a server")
        val base = name.removeSuffix(".zip").removeSuffix(".ZIP").ifEmpty { "Archive" }
        val names = paths.map { it.trimEnd('/').substringAfterLast('/') }
        val ssh = SshPath.parse(folder)
        val result = if (ssh != null) {
            sshFiles().connections.exec(ssh.hostId, RemoteCommands.compress(ssh.path, base, names, allowZip = true))
        } else {
            val adb = AdbPath.parse(folder) ?: throw IOException("Not on a server")
            adbFiles().devices.exec(adb.deviceId, RemoteCommands.compress(adb.path, base, names, allowZip = false))
        }
        val created = RemoteCommands.created(result.output)
            ?: throw IOException(result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "Couldn't compress")
        "${folder.trimEnd('/')}/$created"
    }

    /** Zips [paths] into a new archive called [name] in [destDir]. Returns the archive. */
    suspend fun compress(paths: List<String>, destDir: String, name: String): File = withContext(Dispatchers.IO) {
        val target = uniqueTarget(File(destDir), if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip")
        try {
            Archives.compress(paths.map(::File), target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        scan(listOf(target.path))
        target
    }

    // ---- Local copies of remote files (previews and editors) ----

    /**
     * A local copy of a file on a server, another device or in Android/data, for the in-app
     * viewers and editors. Downloaded once and reused while the remote file keeps the same size and
     * date. [onProgress] gets 0..1 while downloading (from a background thread).
     */
    suspend fun cachedCopy(item: FileItem, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = File(remoteCacheDir(), sha1(item.path))
        val target = File(dir, item.name)
        val upToDate = target.exists() && target.length() == item.size &&
            (item.lastModified <= 0 || abs(target.lastModified() - item.lastModified) < 2_000)
        if (upToDate) return@withContext target
        dir.mkdirs()
        // Downloaded beside the target and renamed at the end: a broken download is never reused.
        val partial = File(dir, ".${item.name}.part")
        partial.delete()
        coroutineScope {
            val progress = launch {
                while (isActive) {
                    if (item.size > 0) onProgress((partial.length().toFloat() / item.size).coerceIn(0f, 1f))
                    delay(150)
                }
            }
            try {
                downloadTo(item.path, partial)
            } finally {
                progress.cancel()
            }
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Couldn't store the downloaded file")
        if (item.lastModified > 0) target.setLastModified(item.lastModified)
        onProgress(1f)
        trimRemoteCache(keep = dir)
        target
    }

    /** Whether edits to a copy of [path] can be saved back there: servers, devices and Android/data. */
    fun canWriteBack(path: String): Boolean = isRemote(path) || isRestricted(path)

    /** Saves an edited local copy over the remote file at [remotePath]. */
    suspend fun writeBack(local: File, remotePath: String): Unit = withContext(Dispatchers.IO) {
        when {
            isSsh(remotePath) -> sshFiles().uploadTo(local, remotePath)
            isAdbDevice(remotePath) -> adbFiles().uploadTo(local, remotePath)
            // Android/data: through this phone's own Wireless debugging connection.
            else -> connectedRemote().push(local, remotePath)
        }
    }

    /** Total size of [path] (a file or folder anywhere), or null when it can't be measured quickly. */
    suspend fun totalSize(path: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                isSsh(path) -> sshFiles().size(path)
                isAdbDevice(path) -> adbFiles().size(path)
                isRestricted(path) -> null
                else -> FileCopier.sizeOf(File(path))
            }
        }.getOrNull()
    }

    /** Size and modification time of a remote file, to notice changes made elsewhere. */
    suspend fun remoteStamp(path: String): String? = withContext(Dispatchers.IO) {
        when {
            isSsh(path) -> sshFiles().stamp(path)
            isAdbDevice(path) -> adbFiles().stamp(path)
            else -> connectedRemote().stamp(path)
        }
    }

    /** Downloads the remote file again over its local copy. */
    suspend fun refreshCopy(remotePath: String, local: File): Unit = withContext(Dispatchers.IO) { downloadTo(remotePath, local) }

    /** Uploads [local] as a new file into [remoteDir] (with a free name). Returns the remote path. */
    suspend fun uploadCopy(local: File, remoteDir: String): String = withContext(Dispatchers.IO) {
        if (isRestricted(remoteDir)) connectedRemote().pushNew(local, remoteDir) else remoteUpload(local, remoteDir)
    }

    private suspend fun downloadTo(path: String, target: File) = when {
        isSsh(path) -> sshFiles().downloadTo(path, target)
        isAdbDevice(path) -> adbFiles().downloadTo(path, target)
        else -> connectedRemote().pull(path, target)
    }

    private fun remoteCacheDir() = File(context.cacheDir, "remote-files")

    /** Keeps the cache of remote copies under [MAX_REMOTE_CACHE] by dropping the least recently used. */
    private fun trimRemoteCache(keep: File) {
        val entries = remoteCacheDir().listFiles()?.filter { it != keep } ?: return
        var total = entries.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } +
            keep.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        for (dir in entries.sortedBy { it.lastModified() }) {
            if (total <= MAX_REMOTE_CACHE) break
            total -= dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** Copies a file from Android/data or a server into app storage so it can be opened. */
    suspend fun copyForOpening(item: FileItem, cacheDir: File): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "remote/${item.name}")
        when {
            isSsh(item.path) -> sshFiles().downloadTo(item.path, target)
            isAdbDevice(item.path) -> adbFiles().downloadTo(item.path, target)
            else -> connectedRemote().pull(item.path, target)
        }
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

    private companion object {
        /** Remote copies kept for previews; Android may also clear them when space runs low. */
        const val MAX_REMOTE_CACHE = 500L * 1024 * 1024

        const val LARGE_FILE = 100L * 1024 * 1024
        const val DUPLICATE_MIN = 100L * 1024
        const val OLD_DOWNLOAD_DAYS = 90
        const val PHOTO_REVIEW_LIMIT = 3_000
    }
}
