package com.singularity_universe.explorer.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.FileItem
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** A file the viewers can read: on this device already, or being downloaded from a server or device. */
sealed interface LocalCopy {
    data class Loading(val progress: Float?) : LocalCopy
    data class Ready(val file: File) : LocalCopy
    data class Failed(val message: String) : LocalCopy
}

/**
 * The local file to show for [item]: the file itself, or (for servers, other devices and
 * Android/data) a cached copy downloaded when [enabled]. Bumping [refreshKey] re-downloads it even
 * if a copy is already shown, for a user-requested refresh.
 */
@Composable
fun rememberLocalCopy(vm: ExplorerViewModel, item: FileItem, enabled: Boolean = true, refreshKey: Int = 0): LocalCopy {
    val remote = remember(item.path) { vm.repo.needsCopyToOpen(item.path) }
    var state by remember(item.path) { mutableStateOf<LocalCopy>(if (remote) LocalCopy.Loading(null) else LocalCopy.Ready(item.file)) }
    LaunchedEffect(item.path, enabled, refreshKey) {
        if (!remote || !enabled || (refreshKey == 0 && state is LocalCopy.Ready)) return@LaunchedEffect
        state = LocalCopy.Loading(null)
        state = try {
            LocalCopy.Ready(vm.repo.cachedCopy(item, force = refreshKey != 0) { progress -> state = LocalCopy.Loading(progress) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalCopy.Failed(e.message ?: "Couldn't download this file")
        }
    }
    return state
}

/** "Downloading… 42% of 12 MB", or why it failed. */
@Composable
fun LocalCopyStatus(state: LocalCopy, item: FileItem, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val context = LocalContext.current
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is LocalCopy.Loading -> {
                val progress = state.progress
                if (progress != null && progress > 0f) CircularProgressIndicator(progress = { progress }, color = color)
                else CircularProgressIndicator(color = color)
                val size = Formatter.formatShortFileSize(context, item.size)
                Text(
                    if (progress != null && progress > 0f) "Downloading… ${(progress * 100).roundToInt()}% of $size" else "Downloading $size…",
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is LocalCopy.Failed -> Text(state.message, color = color, textAlign = TextAlign.Center)
            is LocalCopy.Ready -> Unit
        }
    }
}
