package com.singularity_universe.explorer.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.R

private const val WEBSITE = "https://singularity-universe.com/"
private const val SOURCE_CODE = "http://github.com/SingularityIndonesia/Explorer"

private val MIT_LICENSE = """
MIT License

Copyright (c) 2026 Singularity Universe

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(vm: ExplorerViewModel) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            vm.message("No browser found")
        }
    }
    val version = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Same look as the launcher icon: the folder on a white circle.
            Box(
                Modifier.size(112.dp).background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.folder_orange_f58627), null, Modifier.size(80.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "File explorer without Spyware (guaranteed)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = { vm.navigate(Route.Settings) }) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
            Spacer(Modifier.height(28.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    AboutRow(Icons.Outlined.Info, "Version", version)
                    AboutRow(Icons.Outlined.Shield, "Powered by", "Singularity Universe")
                    AboutRow(Icons.Outlined.Language, "Website", WEBSITE) { openUrl(WEBSITE) }
                    AboutRow(Icons.Outlined.Code, "Source code", SOURCE_CODE) { openUrl(SOURCE_CODE) }
                    AboutRow(Icons.Outlined.Gavel, "License", "MIT License") { showLicense = true }
                }
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("MIT License") },
            text = {
                Text(
                    MIT_LICENSE.substringAfter("MIT License").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                value,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}
