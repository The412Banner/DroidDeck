package com.steamdeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.steamdeck.launcher.runtime.DeckyManager

@Composable
fun DeckyManagerPage(
    status: DeckyManager.Status,
    stable: DeckyManager.Release?,
    prerelease: DeckyManager.Release?,
    checking: Boolean,
    busy: Boolean,
    stage: String?,
    percent: Int,
    error: String?,
    runtimeReady: Boolean,
    fexRootfsReady: Boolean,
    sessionRunning: Boolean,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    onUninstall: (Boolean) -> Unit,
) {
    var channel by rememberSaveable { mutableStateOf(DeckyManager.STABLE) }
    var confirmKeep by rememberSaveable { mutableStateOf(false) }
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    val latest = if (channel == DeckyManager.STABLE) stable else prerelease
    val upToDate = status.installed && status.channel == channel && latest?.version == status.version
    val colors = MaterialTheme.colorScheme
    val blocked = busy || !runtimeReady || sessionRunning
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Decky Loader", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Manage Decky Loader for the Steam session.", color = colors.onSurfaceVariant)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (status.installed) "Installed" else "Not installed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (status.installed) {
                    Text("Version: ${status.version ?: "unknown (installed outside this manager)"}")
                    Text("Channel: ${status.channel?.let(::channelLabel) ?: "unknown"}", color = colors.onSurfaceVariant)
                } else {
                    Text("Plugins and settings are kept when you install or update Decky.", color = colors.onSurfaceVariant)
                }
                if (!runtimeReady) Text("Install the Linux runtime before managing Decky.", color = colors.error)
                if (sessionRunning) Text("Close the Steam session before changing Decky.", color = colors.error)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Release channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = channel == DeckyManager.STABLE, onClick = { channel = DeckyManager.STABLE }, enabled = !busy)
                    Text("Stable", modifier = Modifier.weight(1f))
                    RadioButton(selected = channel == DeckyManager.PRERELEASE, onClick = { channel = DeckyManager.PRERELEASE }, enabled = !busy)
                    Text("Prerelease", modifier = Modifier.weight(1f))
                }
                when {
                    checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking GitHub releases…", color = colors.onSurfaceVariant)
                    }
                    latest != null -> Text("Latest ${channelLabel(channel)}: ${latest.version}", color = colors.onSurfaceVariant)
                    else -> Text("Release information is unavailable. Try checking again.", color = colors.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = !busy && !checking) { Text("Check again") }
                    Button(
                        onClick = { onInstall(channel) },
                        enabled = !blocked && !checking && latest != null && !upToDate,
                    ) {
                        Text(when {
                            upToDate -> "Up to date"
                            !status.installed -> "Install ${channelLabel(channel)}"
                            status.channel != channel -> "Switch to ${channelLabel(channel)}"
                            latest != null && status.version != latest.version -> "Update to ${latest.version}"
                            status.version == null -> "Reinstall ${channelLabel(channel)}"
                            else -> "Reinstall ${channelLabel(channel)}"
                        })
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FEX compatibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Decky’s official x86-64 loader runs through Steam’s FEX component. First install downloads FEX’s " +
                        "official Ubuntu 24.04 guest filesystem (about 500 MB). Steam fetches its FEX tool the first " +
                        "time you start a Steam session.",
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "FEX guest filesystem: ${if (fexRootfsReady) "Ready" else "Not installed"}",
                    color = if (fexRootfsReady) colors.onSurfaceVariant else colors.error,
                )
                Text("Decky and Steam run together only for the lifetime of that session.", color = colors.onSurfaceVariant)
            }
        }

        if (busy) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stage ?: "Working…")
                if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (!error.isNullOrBlank()) Text(error, color = colors.error)

        if (status.installed) {
            Text("Uninstall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("By default, uninstall removes Decky Loader and keeps plugins and settings.", color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { confirmKeep = true }, enabled = !blocked) { Text("Uninstall, keep data") }
                TextButton(onClick = { confirmWipe = true }, enabled = !blocked) { Text("Uninstall and wipe data") }
            }
        }
    }

    if (confirmKeep) AlertDialog(
        onDismissRequest = { confirmKeep = false },
        title = { Text("Uninstall Decky Loader?") },
        text = { Text("This removes the loader and keeps your plugins and Decky settings so they are available if you reinstall later.") },
        confirmButton = { TextButton(onClick = { confirmKeep = false; onUninstall(false) }) { Text("Uninstall") } },
        dismissButton = { TextButton(onClick = { confirmKeep = false }) { Text("Cancel") } },
    )
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Remove all Decky data?") },
        text = { Text("This removes the entire /root/homebrew directory, including Decky plugins, settings, and other files stored there. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmWipe = false; onUninstall(true) }) { Text("Remove all data") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
    )
}

private fun channelLabel(channel: String) = if (channel == DeckyManager.PRERELEASE) "prerelease" else "stable"
