package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus

@Composable
fun PhantomProcessGatePage(
    status: PhantomProcessStatus,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onSetUpWirelessAdb: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp && maxHeight < 500.dp
        SettingsPage(
            host = rememberMenuHost(),
            title = if (compact) "Steam paused" else "Steam cannot start yet",
            eyebrow = "Steam",
            lede = if (compact) compactStatus(status) else "${PhantomProcessLimit.title(status)} · checking again every two seconds",
            onBack = onDismiss,
            // Scrolls, so the larger text never pushes the ADB command off a short screen.
            scrollContent = true,
            compactLayout = compact,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                Column(
                    modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when {
                        compact -> CompactGateInstructions(status, onOpenDeveloperOptions, onSetUpWirelessAdb)
                        wide -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                GateInstructions(status, onOpenDeveloperOptions, onSetUpWirelessAdb)
                            }
                            Column(Modifier.weight(1f)) { AdbFallback() }
                        }
                        else -> {
                            GateInstructions(status, onOpenDeveloperOptions, onSetUpWirelessAdb)
                            AdbFallback()
                        }
                    }
                }
            }
        }
    }
}

private fun compactStatus(status: PhantomProcessStatus): String {
    val state = when (status) {
        PhantomProcessStatus.ENABLED -> "Child-process limit is on"
        PhantomProcessStatus.UNSET -> "Child-process limit is unset"
        PhantomProcessStatus.UNREADABLE -> "Child-process limit is unreadable"
        else -> PhantomProcessLimit.title(status)
    }
    return "$state · retrying every 2 sec"
}

@Composable
private fun CompactGateInstructions(
    status: PhantomProcessStatus,
    onOpenDeveloperOptions: () -> Unit,
    onSetUpWirelessAdb: () -> Unit,
) {
    SettingsGroup("1 · Developer options", compact = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                PhantomProcessLimit.gateInstructions(status),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton("Open Developer options", compact = true, onClick = onOpenDeveloperOptions)
                SecondaryButton("Use Wireless debugging", compact = true, onClick = onSetUpWirelessAdb)
            }
        }
    }
    SettingsGroup("3 · Computer ADB last resort", compact = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "If the setting is missing and Wireless debugging is unavailable, run this from a computer:",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                PhantomProcessLimit.ADB_COMMAND,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GateInstructions(
    status: PhantomProcessStatus,
    onOpenDeveloperOptions: () -> Unit,
    onSetUpWirelessAdb: () -> Unit,
) {
    SettingsGroup("1 · Developer options first") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                PhantomProcessLimit.gateInstructions(status),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Open Developer options", onClick = onOpenDeveloperOptions)
            SecondaryButton("Use Wireless debugging", onClick = onSetUpWirelessAdb)
        }
    }
}

@Composable
private fun AdbFallback() {
    SettingsGroup("3 · Computer ADB last resort") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Use this only if Developer options has no setting and Wireless debugging is unavailable. Connect this device to a computer with ADB and run:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                PhantomProcessLimit.ADB_COMMAND,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeveloperDisplayChoiceDialog(
    displays: List<Pair<Int, String>>,
    onMainScreen: () -> Unit,
    onSecondaryScreen: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Developer options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Choose which display to open Android Settings on.")
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = onMainScreen) { Text("Main screen") }
                displays.forEach { (id, label) ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onSecondaryScreen(id) }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
