package com.steamdeck.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamdeck.launcher.R

/** Everything the main screen shows; the activity owns the values and the work behind them. */
class MainUiState(
    val installed: String?,
    val ready: Boolean,
    val available: String?,
    val availableSize: String?,
    val busy: Boolean,
    val stage: String,
    val percent: Int,
    val failed: Boolean,
    val frameGenLabel: String,
    val desktopInstalled: Boolean,
    /** The account the client would sign in as offline, or null if it has never signed in. */
    val offlineAccount: String?,
    val offline: Boolean,
    /** The folder every session shows at /root/ROMs, or null if none is chosen. */
    val romsDir: String?,
    val logsEnabled: Boolean,
    /** Installed emulators that can run under gamescope: label to path inside the runtime. */
    val emulators: List<Pair<String, String>> = emptyList(),
)

/**
 * The whole app outside a session, in one column that is sized to the window it has.
 *
 * Panels and densities vary too much for fixed dp: a 7" 1080p handheld in landscape has barely
 * 400dp of height, a foldable's inner screen has 900. So the column is designed at one size and
 * scaled by the height available — everything on it, text included, shrinks together until it
 * fits — with scrolling left as the last resort, and a width cap so the buttons never span a
 * wide panel. The system bars are kept clear of.
 *
 * Play and Desktop span the column; everything else is a tile in two columns, a title with the
 * setting's current value under it, so the menu is read at a glance and fits without scrolling
 * on a handheld.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onPlay: () -> Unit,
    onDesktop: () -> Unit,
    onApps: () -> Unit,
    onRuntime: () -> Unit,
    onFrameGen: () -> Unit,
    onProtons: () -> Unit,
    onSteamSettings: () -> Unit,
    onDesktopSettings: () -> Unit,
    onPerformance: () -> Unit,
    onOffline: () -> Unit,
    onRoms: () -> Unit,
    onFiles: () -> Unit,
    onLaunchEmulator: (path: String) -> Unit,
    onEmulatorHelp: () -> Unit,
    onLogs: () -> Unit,
    onCredits: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        // The column as designed needs about this much height; below it, scale everything.
        val designHeight = 480.dp
        val k = (maxHeight / designHeight).coerceIn(0.55f, 1f)
        val colors = MaterialTheme.colorScheme

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 420.dp * k)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp * k, vertical = 12.dp * k),
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp * k),
            )
            Spacer(Modifier.height(6.dp * k))
            Text("SteamDeck", fontSize = 22.sp * k, color = colors.onBackground)
            Text(
                "Valve's native ARM64 Steam client, under gamescope",
                fontSize = 12.sp * k, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp * k))

            val status = when {
                state.busy -> "Working…"
                !state.ready -> "Linux runtime not installed"
                else -> "Ready"
            }
            Text(status, fontSize = 15.sp * k, color = colors.onBackground)
            val detail = when {
                state.busy -> if (state.percent >= 0) "${state.stage} ${state.percent}%" else state.stage
                state.failed -> "Install failed — nothing was changed"
                !state.ready -> state.available?.let { "$it · ${state.availableSize} download, one time" }
                    ?: "Could not reach the runtime catalog"
                state.available != null && state.available != state.installed ->
                    "Runtime ${state.installed ?: "?"} installed · ${state.available} available"
                else -> "Runtime ${state.installed ?: "?"} installed"
            }
            Text(
                detail, fontSize = 12.sp * k, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp * k),
            )
            if (state.busy) {
                Spacer(Modifier.height(6.dp * k))
                if (state.percent >= 0) {
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(14.dp * k))

            val buttonHeight = 40.dp * k
            // Each launch button has its own cog: what only matters for that mode lives there.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp * k), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onPlay, enabled = state.ready && !state.busy,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                ) { Text("Play", fontSize = 14.sp * k) }
                CogButton(k, onSteamSettings)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp * k), modifier = Modifier.fillMaxWidth().padding(top = 5.dp * k)) {
                OutlinedButton(
                    onClick = onDesktop, enabled = state.ready && !state.busy && state.desktopInstalled,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                ) { Text(if (state.desktopInstalled) "Desktop" else "Desktop — install it under Desktop & apps", fontSize = 13.sp * k, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                CogButton(k, onDesktopSettings)
            }
            // The ROMs folder, then every installed emulator as a session of its own under
            // gamescope (where the GPU is); the ? says why they are here and not on the desktop.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp * k),
                verticalArrangement = Arrangement.spacedBy(4.dp * k),
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp * k),
            ) {
                    OutlinedButton(
                        onClick = onRoms,
                        contentPadding = PaddingValues(horizontal = 10.dp * k, vertical = 0.dp),
                        modifier = Modifier.height(30.dp * k),
                    ) {
                        Text(
                            "ROMs: " + (state.romsDir?.substringAfterLast('/')?.ifEmpty { state.romsDir } ?: "choose folder"),
                            fontSize = 11.sp * k, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    for ((label, path) in state.emulators) {
                        OutlinedButton(
                            onClick = { onLaunchEmulator(path) }, enabled = state.ready && !state.busy,
                            contentPadding = PaddingValues(horizontal = 10.dp * k, vertical = 0.dp),
                            modifier = Modifier.height(30.dp * k),
                        ) { Text("▶ $label", fontSize = 11.sp * k, maxLines = 1) }
                    }
                    OutlinedButton(
                        onClick = onEmulatorHelp,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(30.dp * k).height(30.dp * k),
                    ) { Text("?", fontSize = 12.sp * k) }
            }
            // Offline is read while the client starts, so it is decided here rather than in the
            // session's drawer, and it needs credentials from an earlier sign-in to be possible.
            OutlinedButton(
                onClick = onOffline, enabled = state.offlineAccount != null,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp * k).height(buttonHeight),
            ) {
                Text(
                    when {
                        state.offlineAccount == null -> "Start offline — sign in once first"
                        state.offline -> "Start offline: on · ${state.offlineAccount}"
                        else -> "Start offline: off"
                    },
                    fontSize = 13.sp * k,
                )
            }
            Spacer(Modifier.height(8.dp * k))

            val runtimeLabel = when {
                state.busy -> "Working…"
                !state.ready -> "Install Linux runtime"
                state.available != null && state.available != state.installed -> "Update Linux runtime"
                else -> "Remove Linux runtime"
            }
            val tileGap = Arrangement.spacedBy(6.dp * k)
            Row(horizontalArrangement = tileGap, modifier = Modifier.fillMaxWidth()) {
                MenuTile(runtimeLabel, null, k, Modifier.weight(1f), enabled = !state.busy, onClick = onRuntime)
                MenuTile("Desktop & apps", null, k, Modifier.weight(1f), enabled = state.ready && !state.busy, onClick = onApps)
            }
            Spacer(Modifier.height(6.dp * k))
            Row(horizontalArrangement = tileGap, modifier = Modifier.fillMaxWidth()) {
                MenuTile("Frame generation", state.frameGenLabel, k, Modifier.weight(1f), onClick = onFrameGen)
                MenuTile("Compatibility tools", null, k, Modifier.weight(1f), onClick = onProtons)
            }
            Spacer(Modifier.height(6.dp * k))
            Row(horizontalArrangement = tileGap, modifier = Modifier.fillMaxWidth()) {
                MenuTile("Performance", null, k, Modifier.weight(1f), onClick = onPerformance)
                MenuTile("Files", null, k, Modifier.weight(1f), onClick = onFiles)
            }
            Spacer(Modifier.height(6.dp * k))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuTile(
                    "Session logs", if (state.logsEnabled) "on · Download/SteamDeck" else "off",
                    k, Modifier.weight(1f), onClick = onLogs,
                )
            }
            Spacer(Modifier.height(10.dp * k))
            Text(
                "by The412Banner and maxjivi05 · credits",
                fontSize = 11.sp * k, color = colors.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onCredits).padding(4.dp),
            )
        }
    }
}

/** The cog beside a launch button. */
@Composable
private fun CogButton(k: Float, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.width(44.dp * k).height(40.dp * k),
        contentPadding = PaddingValues(0.dp),
    ) { Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp * k)) }
}

/** One tile of the two-column menu: what it is, and under it what it is set to. */
@Composable
private fun MenuTile(
    title: String,
    value: String?,
    k: Float,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(if (value != null) 52.dp * k else 44.dp * k),
        contentPadding = PaddingValues(horizontal = 8.dp * k, vertical = 4.dp * k),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 12.sp * k, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (value != null) {
                Text(
                    value, fontSize = 10.sp * k, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The ? beside the emulator row: what the ▶ buttons are, how to use them, and why. */
@Composable
fun EmulatorHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emulators") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("What the ▶ buttons are", style = MaterialTheme.typography.titleSmall)
                Text(
                    "One per emulator installed under Desktop & apps. Each starts that emulator fullscreen, as a " +
                        "session of its own under gamescope — the same way the Steam client runs — with the Steam " +
                        "cog's display, driver and HDR settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("How to use them", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Put your games in the folder the ROMs chip beside these buttons points at. In the emulator, open root › ROMs " +
                        "(/root/ROMs); the whole phone is at root › Storage. Firmware and BIOS files go in the " +
                        "same way (RPCS3: File › Install Firmware). Back opens the drawer; Stop session ends it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("Why not from the desktop", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The desktop draws in software (labwc on pixman) and cannot hand a program the GPU: a Vulkan " +
                        "emulator started there dies at its first frame (\"Surface lost\"). gamescope can, so an " +
                        "emulator that renders with Vulkan or OpenGL belongs here. The desktop stays for files, " +
                        "Firefox and anything that draws in software.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credits") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("The412Banner — the app, the Wayland compositor, the runtime and the Steam session.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("maxjivi05 (Max) — the gamescope runtime this is built on: the proot session, the session shim, the fake-evdev interposer and the controller work, from WinNative.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "GPL-3.0. Valve, Steam, Steam Deck and Proton are trademarks of Valve Corporation; this project is not affiliated with Valve. gamescope, Mesa, Turnip, Xwayland, PulseAudio, proot and Arch Linux ARM are their authors' own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
