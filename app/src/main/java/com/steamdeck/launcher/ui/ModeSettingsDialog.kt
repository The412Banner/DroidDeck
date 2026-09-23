package com.steamdeck.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamdeck.launcher.core.FexPreset
import com.steamdeck.launcher.session.SessionPrefs
import com.steamdeck.launcher.session.SessionService

class DriverRow(val id: String, val name: String, val detail: String, val removable: Boolean)

/** A release driver (Banners-Turnip, WinNative) that is not installed yet; [key] is its asset name. */
class DownloadRow(val key: String, val label: String, val detail: String)

class ModeSettings(
    val mode: String,
    val resolutionCap: Int,
    /** A fixed session size, or null for the cap and shape. */
    val customResolution: Pair<Int, Int>? = null,
    val shapeMode: String,
    val hdr: Boolean,
    val hdrReason: String?,
    val linuxRows: List<DriverRow>,
    val linuxSelected: String,
    val androidRows: List<DriverRow>,
    val androidSelected: String,
    val touchMode: String,
    val suspendPolicy: String,
    /** Steam only. */
    val oscMode: String?,
    val directAudio: Boolean?,
    val clientDirectAudio: Boolean = false,
    val mic: Boolean?,
    val renderer: String?,
    val gameStorage: String? = null,
    val storageOptions: List<Pair<String, String>> = emptyList(),
    val fexPreset: String? = null,
    /** Steam only: the client branch forced on the command line. */
    val steamChannel: String? = null,
    /** Steam only: the user's own games folder and what was found in it. */
    /** The chosen Games folders (null = not a Steam page). */
    val addedGamesDirs: List<String>? = null,
    val addedGames: List<AddedGameRow> = emptyList(),
    val addedGamesArt: Boolean = true,
    /** Latest Banners-Turnip release: what each driver menu offers to download, and the refresh line. */
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val releaseStatus: String = "Check for new drivers (Banners-Turnip, WinNative)",
)

/** One added game as the settings page shows it: its folder, the chosen .exe, the other .exe files it could be. */
class AddedGameRow(val folderPath: String, val folderName: String, val exePath: String, val exeName: String, val candidates: List<Pair<String, String>>)

class ModeSettingsActions(
    val onResolution: (Int) -> Unit,
    /** Null clears it. */
    val onCustomResolution: (Pair<Int, Int>?) -> Unit = {},
    val onShape: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    val onSelectLinux: (String) -> Unit,
    val onImportLinux: () -> Unit,
    val onRemoveLinux: (String) -> Unit,
    val onRefreshReleases: () -> Unit = {},
    /** Asset name of the release driver to download. */
    val onDownloadDriver: (String) -> Unit = {},
    val onSelectAndroid: (String) -> Unit,
    val onImportAndroid: () -> Unit,
    val onRemoveAndroid: (String) -> Unit,
    val onTouch: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onOsc: (String) -> Unit,
    val onDirectAudio: (Boolean) -> Unit,
    val onClientDirectAudio: (Boolean) -> Unit = {},
    val onMic: (Boolean) -> Unit,
    val onRenderer: (String) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onPickAddedGamesDir: () -> Unit = {},
    val onForgetAddedGamesDir: (path: String) -> Unit = {},
    val onAddedGamesArt: (Boolean) -> Unit = {},
    val onAddedGameExe: (folderPath: String, path: String) -> Unit = { _, _ -> },
    val onPickAddedGameExe: (folderPath: String) -> Unit = {},
    val onDismiss: () -> Unit,
)

@Composable
fun ModeSettingsPage(s: ModeSettings, a: ModeSettingsActions) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    SettingsPage(
        host,
        title = if (steam) "Steam session" else "Desktop session",
        onBack = a.onDismiss,
    ) {
        SettingsGroup("Display") {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            var editCustom by remember { mutableStateOf(false) }
            val custom = s.customResolution
            ChoiceRow(
                host, "res", "Resolution", "Applies next session.",
                listOf(720 to "Up to 720p", 900 to "Up to 900p", 1080 to "Up to 1080p", 0 to "The panel's own")
                    .map { (cap, label) -> cap to (if (cap == default) "$label - the default" else label) } +
                    (CUSTOM to (custom?.let { "Custom · ${it.first}×${it.second}" } ?: "Custom…")),
                if (custom != null) CUSTOM else s.resolutionCap, note = "720p can improve menu responsiveness.",
                onPick = { v -> if (v == CUSTOM) editCustom = true else { a.onCustomResolution(null); a.onResolution(v) } },
            )
            ChoiceRow(
                host, "shape", "Shape",
                if (custom != null) "Set by the custom resolution." else "The panel's shape stays at 16:9 or wider; pick Exactly this panel for a 4:3 or 3:2 screen.",
                com.steamdeck.launcher.session.SessionPrefs.shapeChoices, s.shapeMode, enabled = custom == null, onPick = a.onShape,
            )
            if (editCustom) CustomResolutionDialog(
                initial = custom,
                onSave = { size -> editCustom = false; a.onCustomResolution(size) },
                onDismiss = { editCustom = false },
            )
        }
        SettingsGroup("HDR") {
            ToggleRow(
                host, "hdr", "HDR10 output",
                s.hdrReason?.let { "Not available: $it." }
                    ?: "Restart the app to apply.",
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup("Drivers") {
            DriverRowMenu(
                host, "rt", "Runtime driver",
                (if (steam) "Used by Steam and games." else "Used by desktop apps.") + " Applies next session.",
                s.linuxRows, s.linuxSelected, importLabel = "Import Turnip zip…",
                onSelect = a.onSelectLinux, onRemove = a.onRemoveLinux, onImport = a.onImportLinux,
                downloads = s.linuxDownloads, releaseStatus = s.releaseStatus,
                onRefresh = a.onRefreshReleases, onDownload = a.onDownloadDriver,
            )
            DriverRowMenu(
                host, "panel", "Display driver",
                "Used by the compositor in both modes. Restart the app to apply.",
                s.androidRows, s.androidSelected, importLabel = "Import an AdrenoTools zip…",
                onSelect = a.onSelectAndroid, onRemove = a.onRemoveAndroid, onImport = a.onImportAndroid,
                downloads = s.androidDownloads, releaseStatus = s.releaseStatus,
                onRefresh = a.onRefreshReleases, onDownload = a.onDownloadDriver,
            )
        }
        SettingsGroup(if (steam) "Touch & controls" else "Touch") {
            ChoiceRow(
                host, "touch", "Touch", null,
                listOf("auto" to "Auto", "touchpad" to "Touchpad", "direct" to "Direct"), s.touchMode,
                note = "Auto uses touchpad on desktop and direct input in Steam. Touchpad: drag to move, tap to click.",
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", "On-screen controls", null,
                listOf(
                    SessionPrefs.OSC_AUTO to "Auto",
                    SessionPrefs.OSC_ALWAYS to "Always",
                    SessionPrefs.OSC_STEAM_QAM to "Steam + QAM",
                    SessionPrefs.OSC_NEVER to "Never",
                ), s.oscMode,
                note = "Auto shows all controls without a controller. Steam + QAM shows only those buttons.", onPick = a.onOsc,
            )
        }
        SettingsGroup("Session") {
            ChoiceRow(
                host, "suspend", "Background behavior",
                "How this session behaves when the app leaves the screen or the display turns off.",
                listOf(
                    SessionPrefs.SUSPEND_AUTO to "Auto",
                    SessionPrefs.SUSPEND_MANUAL to "Manual",
                    SessionPrefs.SUSPEND_NEVER to "Never",
                ),
                s.suspendPolicy,
                note = "Auto pauses in the background and resumes when visible. Manual pauses there and waits for Resume. Never keeps the session running.",
                onPick = a.onSuspendPolicy,
            )
        }
        if (steam && s.steamChannel != null) SettingsGroup("Client") {
            ChoiceRow(
                host, "channel", "Client branch", "The Steam client build the session forces. Applies at the next session start; the client may update itself once.",
                listOf("publicbeta" to "Public beta", "steamdeck_publicbeta" to "Steam Deck public beta"), s.steamChannel,
                note = "Public beta is what every session ran on before. Steam Deck public beta is the channel Deck mode needs (on public beta it reinstalls the same client at every start) and the one Armada bootstraps from; Deck mode picks it unless you choose here.",
                onPick = a.onSteamChannel,
            )
        }
        if (steam && s.addedGamesDirs != null) SettingsGroup("Added games") {
            for (dir in s.addedGamesDirs) {
                val n = s.addedGames.count { it.folderPath.startsWith("$dir/") }
                ActionRow(
                    dir.substringAfterLast('/').ifEmpty { dir }, dir + " · " + (if (n == 0) "no game folders with a .exe found" else "$n game${if (n == 1) "" else "s"}") + ". Forget: the games leave the client's library at the next session start; nothing on disk is touched.",
                    "Forget", onClick = { a.onForgetAddedGamesDir(dir) },
                )
            }
            ActionRow(
                if (s.addedGamesDirs.isEmpty()) "Games folder" else "Another games folder",
                "Your own Windows games, one subfolder each, anywhere: internal storage, the SD card, a USB drive. As many folders as you like. Each game goes into the client's library as a non-Steam game under the ARM64 Proton, at the next session start.",
                "Add…", onClick = a.onPickAddedGamesDir,
            )
            ToggleRow(
                host, "addedArt", "Artwork from Steam",
                "A game with no art of its own gets the store's capsule, header, hero and logo for the same title, looked up by folder name. Your own art wins: drop cover.jpg (or poster, boxart, folder, the folder's name), header.jpg, hero.jpg, logo.png or icon.png into the game's folder or its art subfolder.",
                s.addedGamesArt, onChange = a.onAddedGamesArt,
            )
            for (g in s.addedGames) ChoiceRow(
                host, "added:" + g.folderPath, g.folderName, "Launches ${g.exeName}" + (if (s.addedGamesDirs.size > 1) " · in " + g.folderPath.substringBeforeLast('/').substringAfterLast('/') else ""),
                g.candidates + ("__pick__" to "Choose another file…"), g.exePath,
                note = "The .exe files found in the game's folder; the one named after the folder, else the largest, is picked unless you choose.",
                onPick = { path -> if (path == "__pick__") a.onPickAddedGameExe(g.folderPath) else a.onAddedGameExe(g.folderPath, path) },
            )
        }
        if (steam && s.fexPreset != null) SettingsGroup("Games") {
            ChoiceRow(
                host, "fex", "FEX preset", "Applies on next game launch.",
                FexPreset.all.map { it.id to it.label }, s.fexPreset,
                note = FexPreset.byId(s.fexPreset).detail, onPick = a.onFexPreset,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup("Audio") {
            ToggleRow(host, "da", "DirectAudio for games", "Bypasses PulseAudio for lower latency in games.", s.directAudio, onChange = a.onDirectAudio)
            ChoiceRow(
                host, "clientAudio", "Steam client audio", "Classic is the AAudio sink from 0.1.5. DirectAudio goes through the relay. Applies next session.",
                listOf("classic" to "Classic", "directaudio" to "DirectAudio"), if (s.clientDirectAudio) "directaudio" else "classic",
                onPick = { id -> a.onClientDirectAudio(id == "directaudio") },
            )
            ToggleRow(host, "mic", "Microphone", "Uses the device microphone for voice chat.", s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup("Game storage") {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to ("Automatic - the SD card when one is in" + (if (s.storageOptions.isEmpty()) " (none right now)" else "")))
                add("off" to "Internal only")
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to "Folder: ${s.gameStorage}")
            }
            val open = host.open == "storage"
            SettingsRow(
                "Second library",
                "Adds a library location in Steam. Applies next session.",
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" -") ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = "Second library",
                        note = "Games that stream assets from SD or shared storage may stutter. Keep them internal.",
                    ) {
                        for ((path, label) in options) MenuItem(label, checked = path == s.gameStorage) {
                            a.onGameStorage(path, if (path.isEmpty() || path == "off") "" else label.substringBefore(" ·"))
                            host.open = null
                        }
                        MenuItem("Choose a folder…", checked = false) { host.open = null; a.onPickGameStorageFolder() }
                    }
                }
            }
        }
        if (!steam && s.renderer != null) SettingsGroup("Renderer") {
            ChoiceRow(
                host, "renderer", "Desktop renderer", "Composites the desktop.",
                listOf("pixman" to "pixman - software", "gles2" to "gles2", "vulkan" to "vulkan"), s.renderer,
                note = "GLES2 and Vulkan require a DRM render node, unavailable on most devices.", onPick = a.onRenderer,
            )
        }
    }
}

@Composable
private fun DriverRowMenu(
    host: MenuHost, key: String, label: String, hint: String, rows: List<DriverRow>, selected: String, importLabel: String,
    onSelect: (String) -> Unit, onRemove: (String) -> Unit, onImport: () -> Unit,
    downloads: List<DownloadRow> = emptyList(), releaseStatus: String? = null,
    onRefresh: () -> Unit = {}, onDownload: (String) -> Unit = {},
) {
    val open = host.open == key
    val colors = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf<DriverRow?>(null) }
    SettingsRow(label, hint, highlighted = open) {
        androidx.compose.foundation.layout.Box {
            ValueChip(rows.firstOrNull { it.id == selected }?.name ?: rows.firstOrNull()?.name ?: "-", open) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label) {
                for (row in rows) MenuItem(
                    row.name, checked = row.id == selected, detail = row.detail.ifEmpty { null },
                    trailing = if (row.removable) ({
                        androidx.compose.material3.Icon(
                            Icons.Outlined.Delete, contentDescription = "Delete ${row.name}",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(32.dp).clickable { confirmDelete = row }.padding(6.dp),
                        )
                    }) else null,
                ) { onSelect(row.id); host.open = null }
                // The latest Banners-Turnip release: what is not installed yet, then the refresh line.
                // Tapping either keeps the menu open, so the progress and the result show in place.
                for (d in downloads) MenuItem(
                    d.label, checked = false, detail = d.detail,
                    leading = { androidx.compose.material3.Icon(Icons.Outlined.Download, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                ) { onDownload(d.key) }
                if (releaseStatus != null) MenuItem(
                    releaseStatus, checked = false,
                    leading = { androidx.compose.material3.Icon(Icons.Outlined.Refresh, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                ) { onRefresh() }
                MenuItem(importLabel, checked = false) { host.open = null; onImport() }
            }
        }
    }
    confirmDelete?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${row.name}?") },
            text = { Text("Its files are removed from the app. If it is the driver in use, the default takes its place.", fontSize = 13.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null; onRemove(row.id) }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The Resolution menu's "Custom…" entry. */
private const val CUSTOM = -1

/** Width × height for the session, with the common handheld shapes one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomResolutionDialog(initial: Pair<Int, Int>?, onSave: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(initial?.first?.toString() ?: "") }
    var h by remember { mutableStateOf(initial?.second?.toString() ?: "") }
    val parsed = SessionPrefs.parseResolution("${w}x$h")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom resolution") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "The session's display size. It replaces the cap and the shape; a size that does not match the panel's shape gets bars.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    androidx.compose.material3.OutlinedTextField(
                        w, { v -> w = v.filter(Char::isDigit).take(4) }, label = { Text("Width") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                    Text("×", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        h, { v -> h = v.filter(Char::isDigit).take(4) }, label = { Text("Height") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    for ((pw, ph, tag) in listOf(Triple(960, 720, "4:3"), Triple(1024, 768, "4:3"), Triple(1280, 960, "4:3"), Triple(1280, 800, "16:10"), Triple(1152, 648, "16:9"), Triple(1280, 720, "16:9"))) {
                        androidx.compose.material3.AssistChip(
                            onClick = { w = pw.toString(); h = ph.toString() },
                            label = { Text("$pw×$ph · $tag", fontSize = 12.sp) },
                        )
                    }
                }
                if (parsed == null && (w.isNotEmpty() || h.isNotEmpty())) Text(
                    "Between 320×240 and 3840×2160.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("Use") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
