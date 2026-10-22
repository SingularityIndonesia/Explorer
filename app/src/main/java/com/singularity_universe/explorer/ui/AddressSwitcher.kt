package com.singularity_universe.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The address a server or device is reached through, shown as a subtitle. With more than one
 * address saved, tapping it lists them all to switch route (e.g. home Wi-Fi ↔ VPN).
 */
@Composable
fun AddressSwitcher(
    addresses: List<String>,
    active: Int,
    onSelect: (Int) -> Unit,
    color: Color = LocalContentColor.current.copy(alpha = 0.7f),
) {
    var open by remember { mutableStateOf(false) }
    val switchable = addresses.size > 1
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (switchable) Modifier.clickable { open = true } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                addresses.getOrElse(active) { addresses.firstOrNull().orEmpty() },
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (switchable) Icon(Icons.Default.ArrowDropDown, "Switch address", Modifier.size(18.dp), tint = color)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            addresses.forEachIndexed { index, address ->
                DropdownMenuItem(
                    text = { Text(address) },
                    leadingIcon = {
                        if (index == active) Icon(Icons.Default.Check, "In use") else Box(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        if (index != active) onSelect(index)
                    },
                )
            }
        }
    }
}
