package com.steamdeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One core as the page labels it: its number and, where known, its ceiling. */
class CoreRow(val core: Int, val label: String)

/**
 * The two core masks a Steam session carries, kept separate because they are wanted at the same
 * time and suit different things: the client's menus and a game each get their own. Both apply
 * at the next session start; the game's is applied by the Proton wrapper at each launch. Below
 * them, the session fixes for a device the runtime does not sit well on.
 */
@Composable
fun PerformancePage(
    cores: List<CoreRow>,
    clientOverride: Boolean,
    clientCores: Set<Int>,
    gameCores: Set<Int>,
    tuSysmem: Boolean,
    zinkLazy: Boolean,
    glThread: Boolean,
    noGlError: Boolean,
    steamDeckMode: Boolean,
    noXalia: Boolean,
    prootNoSeccomp: Boolean,
    phantomWarning: String?,
    onClientOverride: (Boolean) -> Unit,
    onTuSysmem: (Boolean) -> Unit,
    onZinkLazy: (Boolean) -> Unit,
    onGlThread: (Boolean) -> Unit,
    onNoGlError: (Boolean) -> Unit,
    onSteamDeckMode: (Boolean) -> Unit,
    onNoXalia: (Boolean) -> Unit,
    onProotNoSeccomp: (Boolean) -> Unit,
    onClientCore: (Int, Boolean) -> Unit,
    onGameCore: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val coreItems = cores.map { it.core to it.label }
    SettingsPage(
        host, eyebrow = "Setup · performance", title = "Performance",
        lede = "Which cores the client and its games may run on, and the fixes for a device the runtime does not sit well on. All apply at the next session start.",
        onBack = onDismiss,
    ) {
        SettingsGroup("Steam client cores") {
            ToggleRow(
                host, "override", "Override Steam's own core choice",
                "Steam pins its interface to a subset of cores it picks — on one device 5 of 8, leaving out the fastest — which suits a running game and makes the menus sluggish when the client is all there is. On: the client, its UI helper and gamescope are pinned to the cores below instead, re-applied every few seconds.",
                clientOverride, onChange = onClientOverride,
            )
            MultiRow(
                host, "clientCores", "Client cores", if (clientOverride) "Every core ticked is the usual fix." else "Turn the override on to choose.",
                coreItems, clientCores, enabled = clientOverride, note = "Tick or untick as many as you like; the menu stays open.", onToggle = onClientCore,
            )
        }
        SettingsGroup("Game cores") {
            MultiRow(
                host, "gameCores", "Game cores",
                "Applied by exec'ing the game through taskset, so every thread inherits the mask. Every core ticked sends nothing — that is what the scheduler does unaided. Untick the small cores to keep a heavy game off them.",
                coreItems, gameCores, note = "Tick or untick as many as you like; the menu stays open.", onToggle = onGameCore,
            )
        }
        SettingsGroup("Client interface") {
            ToggleRow(
                host, "glthread", "Threaded GL",
                "The client's menus are drawn Chromium → ANGLE → Zink → Turnip, and that chain is what limits them. mesa_glthread marshals GL off the calling thread, the shape of this bottleneck. On by default; none of these four is device-proven yet.",
                glThread, onChange = onGlThread,
            )
            ToggleRow(
                host, "zink", "Zink: lazy descriptors",
                "Lazy descriptor updates is the mode Zink recommends on drivers without descriptor buffers. On by default.",
                zinkLazy, onChange = onZinkLazy,
            )
            ToggleRow(
                host, "noglerror", "Skip GL error checks",
                "MESA_NO_ERROR: the driver stops validating every GL call. On by default.",
                noGlError, onChange = onNoGlError,
            )
            ToggleRow(
                host, "deck", "Steam Deck mode",
                "Runs the client as SteamOS runs its own session (-steamdeck -steamos3), the shape Valve tunes Big Picture for. Off by default: the client then expects Deck hardware that is not here, and that cost is untested.",
                steamDeckMode, onChange = onSteamDeckMode,
            )
        }
        SettingsGroup("Session fixes") {
            ToggleRow(
                host, "sysmem", "Turnip: sysmem rendering",
                "Renders without the GPU's tile memory (TU_DEBUG=sysmem). A fix for an Adreno 8xx that shows corruption, and required on a 710/720/722 (set on its own there). Not a speed setting: elsewhere it COSTS frames — leave it off unless the picture is wrong.",
                tuSysmem, onChange = onTuSysmem,
            )
            ToggleRow(
                host, "xalia", "Skip Steam's xalia helper",
                "xalia is a Windows program Proton starts for gamepad navigation in Windows programs. On some devices its system calls are refused in a way it cannot cope with and the session dies seconds after Big Picture appears. Turn on if a session will not stay up.",
                noXalia, onChange = onNoXalia,
            )
            ToggleRow(
                host, "seccomp", "Run proot without seccomp",
                "proot normally lets most system calls run untraced, which is most of its speed. Some kernels handle that badly and refuse calls that plainly exist (\"Function not implemented\" in the log). This traces everything: slower, but correct.",
                prootNoSeccomp, onChange = onProotNoSeccomp,
            )
        }
        if (phantomWarning != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text("Android is set to kill this session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                Text(phantomWarning, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
