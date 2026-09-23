package com.steamdeck.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamdeck.launcher.core.FexPreset
import com.steamdeck.launcher.session.SessionPrefs
import com.steamdeck.launcher.session.SessionService

/** One driver as the page shows it. [removable] is false for the runtime's own and the bundled builds. */
class DriverRow(val id: String, val name: String, val detail: String, val removable: Boolean)

/** Everything one mode's settings page shows; the activity owns the values. */
class ModeSettings(
    val mode: String,
    val resolutionCap: Int,
    val shapeMode: String,
    val hdr: Boolean,
    /** Why HDR cannot be offered on this display, or null when it can. */
    val hdrReason: String?,
    val linuxRows: List<DriverRow>,
    val linuxSelected: String,
    val androidRows: List<DriverRow>,
    val androidSelected: String,
    val touchMode: String,
    /** Steam only. */
    val oscMode: String?,
    val directAudio: Boolean?,
    val mic: Boolean?,
    /** Desktop only. */
    val renderer: String?,
    /** Steam only: the second library's root ("" = internal only) and what this device offers. */
    val gameStorage: String? = null,
    val storageOptions: List<Pair<String, String>> = emptyList(),
    /** Steam only: the FEXCore preset for the games the client launches. */
    val fexPreset: String? = null,
)

class ModeSettingsActions(
    val onResolution: (Int) -> Unit,
    val onShape: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    val onSelectLinux: (String) -> Unit,
    val onImportLinux: () -> Unit,
    val onRemoveLinux: (String) -> Unit,
    val onSelectAndroid: (String) -> Unit,
    val onImportAndroid: () -> Unit,
    val onRemoveAndroid: (String) -> Unit,
    val onTouch: (String) -> Unit,
    val onOsc: (String) -> Unit,
    val onDirectAudio: (Boolean) -> Unit,
    val onMic: (Boolean) -> Unit,
    val onRenderer: (String) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onDismiss: () -> Unit,
)

/**
 * The cog beside Play / Desktop: a page in the front end's pane, one row per setting with its
 * value in a chip, and a small menu under the chip to change it. What only matters for that one
 * mode lives here - the display the session is sized to, HDR, the driver inside the runtime,
 * the display driver, and the input and audio choices - so the rail keeps what applies to both.
 */
@Composable
fun ModeSettingsPage(s: ModeSettings, a: ModeSettingsActions) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    SettingsPage(
        host,
        eyebrow = if (steam) "Steam · settings" else "Desktop · settings",
        title = if (steam) "Steam session" else "Desktop session",
        lede = "Each value opens where it is. Most take effect at the next session start; the ones that need the app closed say so.",
        onBack = a.onDismiss,
    ) {
        SettingsGroup("Display") {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            ChoiceRow(
                host, "res", "Resolution", "Takes effect at the next session: gamescope sizes its display once, when it starts.",
                listOf(720 to "Up to 720p", 900 to "Up to 900p", 1080 to "Up to 1080p", 0 to "The panel's own")
                    .map { (cap, label) -> cap to (if (cap == default) "$label — the default" else label) },
                s.resolutionCap, note = "720p keeps the client's menus responsive; above 1080p costs frames for nothing a handheld can show.",
                onPick = a.onResolution,
            )
            ChoiceRow(
                host, "shape", "Shape", "16:9 is for a foldable: bars on either panel instead of a squashed picture.",
                listOf("auto" to "The panel's shape", "16:9" to "16:9 with bars"), s.shapeMode, onPick = a.onShape,
            )
        }
        SettingsGroup("HDR") {
            ToggleRow(
                host, "hdr", "HDR10 output",
                s.hdrReason?.let { "Not available: $it." }
                    ?: "Decided once when the compositor starts: a change applies after the app is fully closed and opened again.",
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup("Drivers") {
            DriverRowMenu(
                host, "rt", "Runtime driver",
                "What " + (if (steam) "the Steam client and its games" else "the desktop's programs") + " render with inside the runtime. Applies at the next session start.",
                s.linuxRows, s.linuxSelected, importLabel = "Import a \"-Linux\" Turnip zip…",
                onSelect = a.onSelectLinux, onRemove = a.onRemoveLinux, onImport = a.onImportLinux,
            )
            DriverRowMenu(
                host, "panel", "Display driver",
                "What the app's compositor puts frames on the screen with; shared by both modes. Applies after the app is fully closed and opened again.",
                s.androidRows, s.androidSelected, importLabel = "Import an AdrenoTools zip…",
                onSelect = a.onSelectAndroid, onRemove = a.onRemoveAndroid, onImport = a.onImportAndroid,
            )
        }
        SettingsGroup(if (steam) "Touch & controls" else "Touch") {
            ChoiceRow(
                host, "touch", "Touch", "How a finger drives the pointer. Also in the session's drawer.",
                listOf("auto" to "Auto", "touchpad" to "Touchpad", "direct" to "Direct"), s.touchMode,
                note = "Auto: touchpad on the desktop, direct in Steam. Touchpad: drag moves, tap clicks. Direct: the pointer jumps under the finger.",
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", "On-screen controls", "The virtual pad drawn over a game.",
                listOf("auto" to "Auto", "always" to "Always", "never" to "Never"), s.oscMode,
                note = "Auto shows it when no controller is attached.", onPick = a.onOsc,
            )
        }
        if (steam && s.fexPreset != null) SettingsGroup("Games") {
            ChoiceRow(
                host, "fex", "FEX preset", "How FEX translates the x86 games the client launches. Applies to the next game launch. Also in the session's drawer.",
                FexPreset.all.map { it.id to it.label }, s.fexPreset,
                note = FexPreset.byId(s.fexPreset).detail, onPick = a.onFexPreset,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup("Audio") {
            ToggleRow(host, "da", "DirectAudio for games", "Games play straight to the device, bypassing PulseAudio: lower latency. Off = PulseAudio for everything.", s.directAudio, onChange = a.onDirectAudio)
            ToggleRow(host, "mic", "Microphone", "The device's microphone for voice chat, as the client's input device. Asks for the permission once.", s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup("Game storage") {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to ("Automatic — the SD card when one is in" + (if (s.storageOptions.isEmpty()) " (none right now)" else "")))
                add("off" to "Internal only")
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to "Folder: ${s.gameStorage}")
            }
            val open = host.open == "storage"
            SettingsRow(
                "Second library",
                "With a second place registered, every Install in Steam asks which drive, Settings › Storage lists both, and Steam moves games between them. Applies at the next session start.",
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" —") ?: "—", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = "Second library",
                        note = "An SD card and shared storage go through Android's file layer: a game that streams big assets from there can stall. Keep such games internal.",
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
                host, "renderer", "Desktop renderer", "How labwc composites the desktop. pixman is software and works everywhere.",
                listOf("pixman" to "pixman — software", "gles2" to "gles2", "vulkan" to "vulkan"), s.renderer,
                note = "gles2 and vulkan need a real DRM render node, which the Adreno stand-in is not on most devices.", onPick = a.onRenderer,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Values are saved as they are picked; there is nothing to confirm.", fontSize = 11.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

/** A driver list as a menu: each build a line, imported ones with a remove control, an import at the end. */
@Composable
private fun DriverRowMenu(
    host: MenuHost, key: String, label: String, hint: String, rows: List<DriverRow>, selected: String, importLabel: String,
    onSelect: (String) -> Unit, onRemove: (String) -> Unit, onImport: () -> Unit,
) {
    val open = host.open == key
    val colors = MaterialTheme.colorScheme
    SettingsRow(label, hint, highlighted = open) {
        androidx.compose.foundation.layout.Box {
            ValueChip(rows.firstOrNull { it.id == selected }?.name ?: rows.firstOrNull()?.name ?: "—", open) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label) {
                for (row in rows) MenuItem(
                    row.name, checked = row.id == selected, detail = row.detail.ifEmpty { null },
                    trailing = if (row.removable) ({
                        Text(
                            "✕", fontSize = 12.sp, color = colors.onSurfaceVariant,
                            modifier = Modifier.size(24.dp).padding(4.dp).clickable { onRemove(row.id); host.open = null },
                        )
                    }) else null,
                ) { onSelect(row.id); host.open = null }
                MenuItem(importLabel, checked = false) { host.open = null; onImport() }
            }
        }
    }
}
