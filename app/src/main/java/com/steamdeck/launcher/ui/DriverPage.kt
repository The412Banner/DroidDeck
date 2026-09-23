package com.steamdeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One driver list as a full page - the Runtime driver or the Display driver - opened from the
 * session's settings page. Installed drivers are picked by tapping the row and each carries a trash
 * can (the "Auto"/"Runtime default" row is a setting, not a driver, so it has none); what the last
 * check of the release repos found is offered below; the refresh button beside the title is the
 * only thing that goes online.
 */
@Composable
fun DriverPage(
    title: String,
    hint: String,
    rows: List<DriverRow>,
    selected: String,
    downloads: List<DownloadRow>,
    status: String,
    checking: Boolean,
    importLabel: String,
    canRestore: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var confirm by remember { mutableStateOf<DriverRow?>(null) }
    SettingsPage(
        host, title = title, onBack = onBack, lede = "$hint\n$status",
        action = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, pal.line, RoundedCornerShape(10.dp))
                    .clickable(enabled = !checking, onClick = onRefresh),
            ) {
                if (checking) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Outlined.Refresh, contentDescription = "Check for new drivers", tint = colors.onBackground, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        SettingsGroup("Installed") {
            for (row in rows) InstalledRow(row, row.id == selected, onSelect = { onSelect(row.id) }, onDelete = { confirm = row })
        }
        SettingsGroup("Available to download") {
            if (downloads.isEmpty()) Text(
                "Nothing new from the last check. Tap the refresh button to look for newer releases.",
                fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            ) else for (d in downloads) {
                SettingsRow(d.label, d.detail) {
                    val p = d.progress
                    if (p == null) SecondaryButton("Download") { onDownload(d.key) }
                    else Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(120.dp)) {
                        Text("$p%", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            SecondaryButton(importLabel, onClick = onImport)
            if (canRestore) Text(
                "Restore built-in drivers", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRestore).padding(8.dp),
            )
        }
    }
    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${row.name}?") },
            text = {
                Text(
                    if (row.tag == DriverRow.BUNDLED) "It is built into the app, so this removes its unpacked files and hides it. \"Restore built-in drivers\" brings it back, and Auto still uses it on a GPU that needs it."
                    else "Its files are removed from the app." + if (row.id == selected) " It is the driver in use, so the default takes its place." else "",
                    fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onDelete(row.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InstalledRow(row: DriverRow, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(if (selected) pal.signal.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp).border(2.dp, if (selected) pal.signal else colors.onSurfaceVariant, CircleShape),
        ) { if (selected) Box(Modifier.size(10.dp).background(pal.signal, CircleShape)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                )
                if (row.tag.isNotEmpty()) Text(
                    row.tag, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (row.detail.isNotEmpty()) Text(row.detail, fontSize = 11.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (row.removable) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete),
        ) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${row.name}", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}
