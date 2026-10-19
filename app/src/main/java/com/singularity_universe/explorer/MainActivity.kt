package com.singularity_universe.explorer

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.remote.RemoteConnection
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.ui.AboutScreen
import com.singularity_universe.explorer.ui.CategoryScreen
import com.singularity_universe.explorer.ui.ExplorerViewModel
import com.singularity_universe.explorer.ui.FolderScreen
import com.singularity_universe.explorer.ui.HomeScreen
import com.singularity_universe.explorer.ui.LocalRemoteConnection
import com.singularity_universe.explorer.ui.LocalSshConnections
import com.singularity_universe.explorer.ui.MediaViewerScreen
import com.singularity_universe.explorer.ui.OtherStorageScreen
import com.singularity_universe.explorer.ui.PermissionScreen
import com.singularity_universe.explorer.ui.RecentsScreen
import com.singularity_universe.explorer.ui.RemoteScreen
import com.singularity_universe.explorer.ui.Route
import com.singularity_universe.explorer.ui.SearchScreen
import com.singularity_universe.explorer.ui.SettingsScreen
import com.singularity_universe.explorer.ui.StarredScreen
import com.singularity_universe.explorer.ui.theme.ExplorerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ExplorerViewModel by viewModels()

    /**
     * The one remote (Wireless debugging) connection. This activity owns its lifecycle and provides
     * it to the ViewModel and every screen; the instance itself is process-wide, so a rotation
     * reuses the open socket instead of reconnecting.
     */
    private lateinit var remote: RemoteConnection

    /** SSH servers: one reused session per server, owned the same way as [remote]. */
    private lateinit var ssh: SshConnections

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        remote = RemoteConnection.get(this)
        ssh = SshConnections.get(this)
        vm.attachRemote(remote)
        vm.attachSsh(ssh)
        setContent {
            CompositionLocalProvider(LocalRemoteConnection provides remote, LocalSshConnections provides ssh) {
                ExplorerTheme {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        if (vm.hasStorageAccess) ExplorerRoot(vm) else PermissionScreen()
                    }
                }
            }
        }
        // Only on a fresh launch — after rotation the ViewModel already shows the right folder.
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Explorer" from another app: jump to the folder containing the file. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let { vm.openExternal(it, intent.type) }
    }

    override fun onResume() {
        super.onResume()
        val granted = Environment.isExternalStorageManager()
        if (granted != vm.hasStorageAccess) vm.hasStorageAccess = granted
        remote.onResume()
        ssh.onResume()
    }

    override fun onPause() {
        remote.onPause()
        ssh.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // Not on rotation: only when the user actually leaves the app.
        if (isFinishing) {
            remote.close()
            ssh.close()
        }
        super.onDestroy()
    }
}

@Composable
private fun ExplorerRoot(vm: ExplorerViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val stateHolder = rememberSaveableStateHolder()
    val ssh = LocalSshConnections.current

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = drawerState.isClosed && vm.backStack.size > 1) { vm.back() }
    BackHandler(enabled = vm.pendingTransfer != null && vm.backStack.size == 1) { vm.pendingTransfer = null }

    fun go(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || vm.backStack.size == 1,
        drawerContent = {
            ModalDrawerSheet {
                // Navigation and shortcuts take the remaining height (and scroll when there are many);
                // About stays pinned to the bottom.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        "Files",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    DrawerItem("Browse", Icons.Outlined.Folder) { go(vm::goHome) }
                    DrawerItem("Recents", Icons.Outlined.Schedule) { go { vm.goHome(); vm.navigate(Route.Recents) } }
                    DrawerItem("Starred", Icons.Outlined.StarBorder) { go { vm.goHome(); vm.navigate(Route.Starred) } }
                    if (ssh.hosts.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                        DrawerSectionTitle("Servers")
                        ssh.hosts.forEach { host ->
                            val connected = ssh.status[host.id] == SshConnections.Status.Connected
                            NavigationDrawerItem(
                                label = { Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                icon = { Icon(Icons.Outlined.Dns, null) },
                                badge = {
                                    if (connected) {
                                        Text(
                                            "Connected",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                selected = false,
                                onClick = { go { vm.goHome(); vm.openServer(host.id) } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    if (vm.shortcuts.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                        DrawerSectionTitle("Shortcuts")
                        vm.shortcuts.forEach { path ->
                            DrawerItem(shortcutName(vm, path), Icons.Outlined.Folder) {
                                go { vm.goHome(); vm.navigate(Route.Folder(path)) }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                DrawerItem("About", Icons.Outlined.Info) { go { vm.goHome(); vm.navigate(Route.About) } }
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = vm.backStack.lastIndex to vm.backStack.last(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { (index, route) ->
                stateHolder.SaveableStateProvider("$index:${System.identityHashCode(route)}") {
                    Screen(vm, route, onOpenDrawer = { scope.launch { drawerState.open() } })
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
            )
        }
    }
}

@Composable
private fun Screen(vm: ExplorerViewModel, route: Route, onOpenDrawer: () -> Unit) {
                when (route) {
                    Route.Home -> HomeScreen(vm, onOpenDrawer)
                    Route.Recents -> RecentsScreen(vm)
                    Route.Starred -> StarredScreen(vm)
                    Route.Search -> SearchScreen(vm)
                    Route.OtherStorage -> OtherStorageScreen(vm)
                    Route.About -> AboutScreen(vm)
                    Route.Remote -> RemoteScreen(vm)
                    Route.Settings -> SettingsScreen(vm)
                    is Route.Folder -> FolderScreen(vm, route.path, route.highlight)
                    is Route.CategoryFiles -> CategoryScreen(vm, route.category)
                    is Route.Viewer -> MediaViewerScreen(vm, route.items, route.index)
                }
}

internal fun shortcutName(vm: ExplorerViewModel, path: String): String = vm.displayName(path)

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
