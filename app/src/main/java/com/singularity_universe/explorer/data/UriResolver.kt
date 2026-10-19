package com.singularity_universe.explorer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.OpenableColumns
import com.singularity_universe.explorer.remote.RemoteFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Maps a URI handed to us by another app ("Open with Explorer") to a real file path, so we can
 * show the folder that contains it. Returns null when the file isn't on shared storage.
 */
suspend fun resolveFilePath(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching { resolve(context, uri) }.getOrNull()
        ?.let { File(it) }
        // Android/data can't be checked from here; remote access will tell if it's really there.
        ?.takeIf { it.exists() || RemoteFiles.isRestricted(it.path) }
        // "/sdcard/..." is an alias; use the real path so breadcrumbs and caches line up.
        ?.canonicalPath
}

private fun resolve(context: Context, uri: Uri): String? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

    fromDocumentsProvider(context, uri)?.let { return it }
    // MediaStore URIs, and many apps' own providers, still expose the real path in _data.
    queryString(context, uri, MediaStore.MediaColumns.DATA)?.let { return it }
    return findByNameAndSize(context, uri)
}

/** URIs from the system file picker / Files app. */
private fun fromDocumentsProvider(context: Context, uri: Uri): String? {
    if (!DocumentsContract.isDocumentUri(context, uri) && !DocumentsContract.isTreeUri(uri)) return null
    val docId = if (DocumentsContract.isDocumentUri(context, uri)) {
        DocumentsContract.getDocumentId(uri)
    } else {
        DocumentsContract.getTreeDocumentId(uri)
    }
    return when (uri.authority) {
        // "primary:Download/report.pdf" or "1A2B-3C4D:Music/song.mp3" (SD card)
        "com.android.externalstorage.documents" -> {
            val volume = docId.substringBefore(':')
            val relative = docId.substringAfter(':', "")
            val root = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().path
            } else {
                "/storage/$volume"
            }
            if (relative.isEmpty()) root else "$root/$relative"
        }
        // "raw:/storage/emulated/0/Download/x.pdf", "msf:123" (MediaStore id) or a bare id
        "com.android.providers.downloads.documents" -> when {
            docId.startsWith("raw:") -> docId.removePrefix("raw:")
            docId.startsWith("msf:") -> mediaStorePath(context, docId.removePrefix("msf:").toLongOrNull())
            else -> null
        }
        // "image:42", "video:7", "audio:3", "document:9"
        "com.android.providers.media.documents" -> mediaStorePath(context, docId.substringAfter(':').toLongOrNull())
        else -> null
    }
}

private fun mediaStorePath(context: Context, id: Long?): String? {
    id ?: return null
    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    return queryString(context, uri, FileColumns.DATA)
}

/** Last resort for opaque URIs (e.g. another app's FileProvider): look the file up by name and size. */
private fun findByNameAndSize(context: Context, uri: Uri): String? {
    var name: String? = null
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    val displayName = name ?: return null
    val selection = buildString {
        append("${FileColumns.DISPLAY_NAME} = ?")
        if (size >= 0) append(" AND ${FileColumns.SIZE} = ?")
    }
    val args = if (size >= 0) arrayOf(displayName, size.toString()) else arrayOf(displayName)
    context.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        arrayOf(FileColumns.DATA),
        selection,
        args,
        "${FileColumns.DATE_MODIFIED} DESC",
    )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
    return null
}

private fun queryString(context: Context, uri: Uri, column: String): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()
