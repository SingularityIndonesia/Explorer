package com.singularity_universe.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.singularity_universe.explorer.ui.TextEditorViewModel.SaveStatus
import com.singularity_universe.explorer.ui.TextEditorViewModel.State

/** Plain text editor: saves automatically, with undo/redo, and Prettify for JSON. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(vm: TextEditorViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    val history = (vm.state as? State.Ready)?.history

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (history != null) SaveStatusText(vm.saveStatus, onRetry = vm::retrySave)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (history != null) {
                        IconButton(onClick = history::undo, enabled = history.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "Undo")
                        }
                        IconButton(onClick = history::redo, enabled = history.canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "Redo")
                        }
                        if (vm.isJson) TextButton(onClick = vm::prettify) { Text("Prettify") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            if (vm.conflict) ConflictBanner(onReload = vm::reload, onKeepMine = vm::keepMine, modifier = Modifier.align(Alignment.TopCenter))
            when (val state = vm.state) {
                State.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is State.Failed -> Text(
                    state.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is State.Ready -> Editor(state.history)
            }
        }
    }
}

@Composable
private fun Editor(history: EditHistory) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = history.value,
        onValueChange = history::onValueChange,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // Code and config files: no autocorrect or automatic capitals.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            // Hardware keyboards: Ctrl+Z undoes, Ctrl+Shift+Z or Ctrl+Y redoes.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Z && event.isShiftPressed -> history.redo()
                    event.key == Key.Z -> history.undo()
                    event.key == Key.Y -> history.redo()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SaveStatusText(status: SaveStatus, onRetry: () -> Unit) {
    val style = MaterialTheme.typography.bodySmall
    when (status) {
        SaveStatus.Saved -> Text("Saved", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SaveStatus.Saving -> Text("Saving…", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is SaveStatus.Failed -> Text(
            "Couldn't save: ${status.reason} · Tap to retry",
            style = style,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

/** The file changed elsewhere: take the other version, or save these edits over it. */
@Composable
private fun ConflictBanner(onReload: () -> Unit, onKeepMine: () -> Unit, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.padding(8.dp).zIndex(1f)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                "This file was changed somewhere else since you opened it. Saving is paused.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onReload) { Text("Reload theirs") }
                TextButton(onClick = onKeepMine) { Text("Keep mine") }
            }
        }
    }
}

