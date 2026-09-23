package com.steamdeck.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamdeck.launcher.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.steamdeck.launcher.core.FexPreset
import com.steamdeck.launcher.gpu.FrameGen
import com.steamdeck.launcher.session.SessionPrefs

/** A line of numbers in the top-right corner. It takes no touches: everything goes to the game. */
@Composable
fun HudText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0x8C000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * What the user sees until the client draws its first frame: the runtime's milestone, the
 * client's download progress lifted out of its log, a clock, and a hint. Opaque, so a stale
 * frame from an earlier session never shows through, and it swallows touches.
 */
@Composable
fun LoadingOverlay(step: String, percent: Int, elapsed: String, hint: String, ended: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = MutableInteractionSource(), indication = null) {}
            .padding(32.dp),
    ) {
        Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text(if (ended) "The session ended" else "Steam is loading", color = Color.White, fontSize = 17.sp)
        Text(step, color = Color(0xFFB8C4D0), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (!ended) {
            Spacer(Modifier.height(14.dp))
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.width(220.dp))
            else LinearProgressIndicator(modifier = Modifier.width(220.dp))
            Text(elapsed, color = Color(0xFF667788), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text(
                hint, color = Color(0xFF667788), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp).widthIn(max = 360.dp),
            )
        }
    }
}

/** Everything the drawer shows and does. */
class DrawerActions(
    val steam: Boolean,
    val hudOn: Boolean,
    val frameGenEngine: String,
    val frameGenMultiplier: Int,
    val lsfgReady: Boolean,
    val oscMode: String,
    val touchMode: String,
    /** What "auto" resolves to right now: "touchpad" or "direct". */
    val touchAuto: String,
    val shapeMode: String,
    val fexPreset: String,
    val onHud: (Boolean) -> Unit,
    val onFrameGenPick: (engine: String, multiplier: Int) -> Unit,
    val onKeyboard: () -> Unit,
    /** Sends the Guide button (the client's menu); null on the desktop, where there is none. */
    val onSteamMenu: (() -> Unit)?,
    val onProtons: () -> Unit,
    val onOsc: (String) -> Unit,
    val onTouch: (String) -> Unit,
    val onShape: (String) -> Unit,
    val onFexPreset: (String) -> Unit,
    val onBackground: () -> Unit,
    val onStop: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The drawer Back opens over a running session, in the front end's own dress: a panel that
 * slides in from the right with the switches a player reaches for mid-game as rows whose values
 * open in place, what only applies at the next session under its own heading, and the two ways
 * out at the end. Steam and the desktop share the rows that mean the same on both; the client's
 * own (its menu, the virtual pad, the FEX preset, the compatibility tools) show only there.
 */
@Composable
fun SessionDrawer(open: Boolean, a: DrawerActions) {
    val colors = MaterialTheme.colorScheme
    val host = rememberMenuHost()
    val veil by animateFloatAsState(if (open) 1f else 0f, Motion.tw(260), label = "veil")
    if (open || veil > 0.01f) Box(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color(0x8A000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { host.open = null; a.onClose() },
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            open,
            enter = slideInHorizontally(Motion.sp(0.8f, Spring.StiffnessLow)) { it } + fadeIn(Motion.tw(220)),
            exit = slideOutHorizontally(Motion.tw(240)) { it } + fadeOut(Motion.tw(200)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .background(Color(0xF7101418))
                    // The panel swallows its own touches so they do not close it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Brush.linearGradient(listOf(colors.primary, Color(0xFF7B4DFF)))))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("SteamDeck", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                        Text(if (a.steam) "Steam session" else "Desktop session", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                }

                SettingsGroup("Now") {
                    ToggleRow(host, "hud", "Performance HUD", "The frame counter in the corner.", a.hudOn, onChange = a.onHud)
                    val fgOpen = host.open == "fg"
                    val fgLabel = when (a.frameGenEngine) {
                        FrameGen.ENGINE_WINFG -> "Win-FG ${a.frameGenMultiplier}×"
                        FrameGen.ENGINE_LSFG -> "LSFG ${a.frameGenMultiplier}×"
                        else -> "Off"
                    }
                    SettingsRow("Frame generation", "Extra frames between the real ones; takes effect at once, mid-game included.", highlighted = fgOpen) {
                        Box {
                            ValueChip(fgLabel, fgOpen) { host.open = if (fgOpen) null else "fg" }
                            AnchoredMenu(fgOpen, onDismiss = { if (host.open == "fg") host.open = null }, title = "Frame generation") {
                                val need = if (a.lsfgReady) null else "install Lossless Scaling in Steam"
                                MenuItem("Off", checked = a.frameGenEngine == FrameGen.ENGINE_OFF) { a.onFrameGenPick(FrameGen.ENGINE_OFF, 2); host.open = null }
                                for (m in 2..4) MenuItem("Win-FG ${m}×", checked = a.frameGenEngine == FrameGen.ENGINE_WINFG && a.frameGenMultiplier == m) { a.onFrameGenPick(FrameGen.ENGINE_WINFG, m); host.open = null }
                                for (m in 2..4) MenuItem("LSFG ${m}×", checked = a.frameGenEngine == FrameGen.ENGINE_LSFG && a.frameGenMultiplier == m, enabled = a.lsfgReady, detail = need) { a.onFrameGenPick(FrameGen.ENGINE_LSFG, m); host.open = null }
                            }
                        }
                    }
                    ChoiceRow(
                        host, "touch", "Touch", "How a finger drives the pointer.",
                        listOf(SessionPrefs.TOUCH_AUTO to "Auto (${a.touchAuto})", SessionPrefs.TOUCH_PAD to "Touchpad", SessionPrefs.TOUCH_DIRECT to "Direct"), a.touchMode,
                        note = "Touchpad: drag moves, tap clicks. Direct: the pointer jumps under the finger.", onPick = a.onTouch,
                    )
                    if (a.steam) ChoiceRow(
                        host, "osc", "On-screen controls", "The virtual pad drawn over a game.",
                        listOf(SessionPrefs.OSC_AUTO to "Auto", SessionPrefs.OSC_ALWAYS to "Always", SessionPrefs.OSC_NEVER to "Never"), a.oscMode,
                        note = "Auto shows it when no controller is attached.", onPick = a.onOsc,
                    )
                    ActionRow("Keyboard", "The on-screen keyboard, for a field the client or a program is waiting on.", "Show") { host.open = null; a.onKeyboard() }
                    if (a.onSteamMenu != null) ActionRow("Steam menu", "The Guide button: the client's own overlay, for a pad without one.", "Open  ◉") { host.open = null; a.onSteamMenu.invoke() }
                }

                SettingsGroup("Next session") {
                    ChoiceRow(
                        host, "shape", "Display shape", "gamescope sizes its display once, when a session starts.",
                        listOf(SessionPrefs.SHAPE_AUTO to "The panel's shape", SessionPrefs.SHAPE_WIDE to "16:9 with bars"), a.shapeMode, onPick = a.onShape,
                    )
                    if (a.steam) ChoiceRow(
                        host, "fex", "FEX preset", "How FEX translates the x86 games the client launches. Applies to the next game launch.",
                        FexPreset.all.map { it.id to it.label }, a.fexPreset, note = FexPreset.byId(a.fexPreset).detail, onPick = a.onFexPreset,
                    )
                    if (a.steam) ActionRow("Compatibility tools", "The Proton builds the client can run games with.", "Manage") { host.open = null; a.onProtons() }
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Send to background") { host.open = null; a.onBackground() }
                    DangerButton("Stop session") { host.open = null; a.onStop() }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The one button that ends things: outlined in the error colour, filled on focus. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val fill by animateColorAsState(if (hot) colors.error.copy(alpha = 0.18f) else Color.Transparent, Motion.tw(220), label = "dangerFill")
    Box(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(fill).border(1.dp, colors.error.copy(alpha = if (hot) 0.9f else 0.5f), RoundedCornerShape(12.dp))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = colors.error, maxLines = 1) }
}
