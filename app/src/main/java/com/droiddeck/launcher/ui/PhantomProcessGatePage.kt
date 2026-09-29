package com.droiddeck.launcher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.WirelessAdbPairingService.Stage

@Composable
fun PhantomProcessGatePage(
    status: PhantomProcessStatus,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val stage by WirelessAdbPairingService.stage.collectAsState()
    val environment by produceState(GateEnvironment.read(context)) {
        while (true) {
            value = GateEnvironment.read(context)
            kotlinx.coroutines.delay(2_000)
        }
    }
    val hasToggle = PhantomProcessLimit.hasDeveloperToggle()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp && maxHeight < 500.dp
        SettingsPage(
            host = rememberMenuHost(),
            title = "One Android setting to change",
            eyebrow = "Steam",
            lede = if (compact) null else "Android stops apps that start lots of background processes, and Steam starts dozens. " +
                "Until this is changed, games close with no error.",
            onBack = onDismiss,
            scrollContent = true,
            compactLayout = compact,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                val fix: @Composable ColumnScope.() -> Unit = {
                    FixGroup(hasToggle, stage, environment, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging,
                        onOpenNotificationSettings, onCancel = { WirelessAdbPairingService.cancel(context) })
                }
                val side: @Composable ColumnScope.() -> Unit = {
                    StatusGroup(status, compact)
                    OtherWays(hasToggle, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging, onEnterAddressManually, onCopyCommand)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                        Column(Modifier.weight(1.2f), content = fix)
                        Column(Modifier.weight(1f), content = side)
                    }
                } else {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        fix()
                        side()
                    }
                }
            }
        }
    }
}

private data class GateEnvironment(val onWifi: Boolean, val notifications: Boolean) {
    companion object {
        fun read(context: Context): GateEnvironment {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val wifi = runCatching {
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(true)
            return GateEnvironment(wifi, WirelessAdbPairingService.notificationsEnabled(context))
        }
    }
}

@Composable
private fun FixGroup(
    hasToggle: Boolean,
    stage: Stage,
    environment: GateEnvironment,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    val pairing = stage is Stage.Waiting || stage is Stage.CodeNeeded || stage is Stage.Working
    SettingsGroup(if (hasToggle) "Developer options" else "Fix it for me", compact = compact) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                pairing -> PairingProgress(stage, onCancel)
                hasToggle -> {
                    Body("Turn on “Disable child process restrictions”, then come back. Keep Developer options on afterwards: turning them off turns the switch back off.")
                    PrimaryButton("Open Developer options", compact = compact, onClick = onOpenDeveloperOptions)
                }
                else -> {
                    Body("This Android version has no switch for it, so DroidDeck changes it over Wireless debugging on this device. About a minute, no computer needed.")
                    (stage as? Stage.Failed)?.let { Body(it.error, error = true) }
                    PrimaryButton(
                        if (stage is Stage.Failed) "Try again" else "Fix it for me",
                        compact = compact,
                        enabled = environment.onWifi && environment.notifications,
                        onClick = onFixWithWirelessDebugging,
                    )
                }
            }
            if (!hasToggle && !pairing) {
                if (!environment.onWifi) {
                    Body("Wireless debugging needs Wi-Fi. Connect to any network first; it doesn't need internet.", warn = true)
                }
                if (!environment.notifications) {
                    Body("The pairing code is entered in a notification. Allow notifications for DroidDeck, or use Other ways.", warn = true)
                    SecondaryButton("Allow notifications", compact = compact, onClick = onOpenNotificationSettings)
                }
            }
        }
    }
}

@Composable
private fun PairingProgress(stage: Stage, onCancel: () -> Unit) {
    val steps = listOf(
        "Open Settings → Developer options → Wireless debugging and turn it on",
        "Tap “Pair device with pairing code”",
        "Type the code into the DroidDeck notification",
    )
    val active = when (stage) {
        Stage.Waiting -> 1
        is Stage.CodeNeeded -> 2
        else -> 3
    }
    steps.forEachIndexed { index, step ->
        val done = index < active
        val current = index == active
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (done) "✓" else "${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (done) LocalPalette.current.good else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(step, style = MaterialTheme.typography.bodySmall,
                color = if (current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            when (stage) {
                Stage.Waiting -> "Waiting for the pairing pop-up…"
                is Stage.CodeNeeded -> stage.error ?: "Pop-up found. Enter its code in the notification."
                is Stage.Working -> stage.step
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (stage is Stage.CodeNeeded && stage.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton("Cancel", compact = true, onClick = onCancel)
    }
}

@Composable
private fun StatusGroup(status: PhantomProcessStatus, compact: Boolean) {
    SettingsGroup("Status", compact = compact) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(PhantomProcessLimit.title(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Body("Checking every 2 seconds. This page closes when it's off.")
        }
    }
}

@Composable
private fun OtherWays(
    hasToggle: Boolean,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsGroup("Other ways", compact = compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Body(if (hasToggle) "Wireless debugging, manual address, computer ADB" else "Manual address, computer ADB, Developer options", modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasToggle) {
                SecondaryButton("Use Wireless debugging instead", compact = true, onClick = onFixWithWirelessDebugging)
            } else {
                Body("Some ROMs add the switch anyway. Look for “child process” in Developer options.")
                SecondaryButton("Open Developer options", compact = true, onClick = onOpenDeveloperOptions)
            }
            Body("If the notification doesn't work, enter the pop-up's address and code yourself.")
            SecondaryButton("Enter address manually", compact = true, onClick = onEnterAddressManually)
            Body("From a computer with ADB:")
            Text(
                PhantomProcessLimit.adbCommand(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("Copy command", compact = true, onClick = onCopyCommand)
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, error: Boolean = false, warn: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            error -> MaterialTheme.colorScheme.error
            warn -> androidx.compose.ui.graphics.Color(0xFFFFB86B)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
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
