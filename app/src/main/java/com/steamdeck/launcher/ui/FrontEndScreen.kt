package com.steamdeck.launcher.ui

import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.steamdeck.launcher.R
import com.steamdeck.launcher.frontend.Library
import com.steamdeck.launcher.gpu.FrameGen
import java.io.File
import kotlin.math.roundToInt

/** Everything the front end shows; the activity owns the values and the work behind them. */
class FrontEndState(
    val installed: String?,
    val ready: Boolean,
    val available: String?,
    val busy: Boolean,
    val stage: String,
    val percent: Int,
    val desktopInstalled: Boolean,
    val offlineAccount: String?,
    val offline: Boolean,
    val frameGenLabel: String,
    val romsDir: String?,
    val logsEnabled: Boolean,
    val steamGames: List<Library.SteamGame>,
    val emulators: List<Library.Emulator>,
    /** A session alive in the background: what it is, or null. */
    val running: String?,
    val frameGenEngine: String = FrameGen.ENGINE_OFF,
    val frameGenMultiplier: Int = 2,
    val lsfgReady: Boolean = false,
    /** A page shown in the pane instead of the selection ("settings:steam", "settings:lxqt", "performance"), or null. */
    val pageKey: String? = null,
)

class FrontEndActions(
    val onPlay: () -> Unit,
    val onPlayDesktopUi: () -> Unit,
    val onSteamGame: (Library.SteamGame) -> Unit,
    val onDesktop: () -> Unit,
    val onEmulator: (Library.Emulator) -> Unit,
    val onRom: (Library.Rom) -> Unit,
    val onResume: () -> Unit,
    val onSteamSettings: () -> Unit,
    val onDesktopSettings: () -> Unit,
    val onApps: () -> Unit,
    val onRuntime: () -> Unit,
    val onFrameGenPick: (engine: String, multiplier: Int) -> Unit,
    val onProtons: () -> Unit,
    val onPerformance: () -> Unit,
    val onRoms: () -> Unit,
    val onFiles: () -> Unit,
    val onLogs: () -> Unit,
    val onOffline: () -> Unit,
    val onEmulatorHelp: () -> Unit,
    val onCredits: () -> Unit,
    /** Leaves the page in the pane (back key, the rail, or the page's own Back). */
    val onPageBack: () -> Unit = {},
)

// ───────────────────────────── Motion ─────────────────────────────

/**
 * One place for every duration and spring on this screen. The durations honour the system
 * animator scale, so a device set to "no animations" in developer options collapses them to
 * an instant snap instead of ignoring the user's choice.
 */
internal object Motion {
    var scale = 1f
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    fun ms(base: Int) = (base * scale).roundToInt()
    fun <T> tw(base: Int, delay: Int = 0): FiniteAnimationSpec<T> = if (scale == 0f) snap() else tween(ms(base), ms(delay), Ease)
    fun <T> sp(damping: Float = 0.7f, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        if (scale == 0f) snap() else spring(damping, stiffness)
}

private val Accent2 = Color(0xFF7B4DFF)
private val Good = Color(0xFF7FD8A0)
private val Shape10 = RoundedCornerShape(10.dp)
private val Shape12 = RoundedCornerShape(12.dp)

/** A colour for a thing that has no art: stable per name, so it does not change between visits. */
private fun hueOf(name: String) = (name.hashCode().toUInt() % 360u).toFloat()
private fun tint(h: Float, s: Float = 0.7f, v: Float = 0.58f) = Color.hsv(h, s, v)
private fun artBrush(h: Float) = Brush.linearGradient(listOf(tint(h), tint((h + 32f) % 360f, 0.65f, 0.30f), tint((h + 64f) % 360f, 0.6f, 0.14f)))

/**
 * A row that rises into place: the i-th row of a page starts 60 ms after the one before it, so
 * a page change reads as one cascade rather than a cut.
 */
@Composable
internal fun Rise(i: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = state, modifier = modifier,
        enter = fadeIn(Motion.tw(450, i * 60)) + slideInVertically(Motion.tw(450, i * 60)) { it / 3 },
        exit = fadeOut(Motion.tw(120)),
        label = "rise",
    ) { content() }
}

/** Alpha + lift on first composition, delayed by the item's index: sub-list children stagger in. */
@Composable
private fun Modifier.staggerIn(i: Int): Modifier {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.tw(360, i * 30)) }
    return graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 10.dp.toPx() }
}

/** A diagonal band of light that sweeps across the content once, whenever [trigger] turns on. */
@Composable
private fun Modifier.shine(trigger: Boolean, strength: Float = 0.22f): Modifier {
    val x = remember { Animatable(-1f) }
    LaunchedEffect(trigger) { if (trigger) { x.snapTo(-1f); x.animateTo(1f, Motion.tw(800)) } }
    return drawWithContent {
        drawContent()
        val p = x.value
        if (p > -1f && p < 1f) {
            val w = size.width
            val c = w * 0.5f + p * w * 0.9f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                    start = Offset(c - w * 0.35f, 0f), end = Offset(c + w * 0.35f, size.height),
                ),
            )
        }
    }
}

// ───────────────────────────── Screen ─────────────────────────────

/**
 * The front end: a rail of what can be launched - Steam and its games, the desktop and its
 * emulators and their games - and the chosen thing on the right with its launch button. A
 * session running in the background is the first thing on the rail, and tapping it goes back
 * to it. Landscape puts the rail beside the content; a narrow screen puts it above.
 */
@Composable
fun FrontEndScreen(s: FrontEndState, a: FrontEndActions, page: (@Composable () -> Unit)? = null) {
    var selected by rememberSaveable { mutableStateOf("steam") }
    var openDesktop by rememberSaveable { mutableStateOf(true) }
    var openSteam by rememberSaveable { mutableStateOf(true) }
    var openEmu by rememberSaveable { mutableStateOf("") }
    // Setup starts open only while there is setting up to do (no runtime yet); otherwise it is
    // folded so the rail is the library.
    var openSetup by rememberSaveable { mutableStateOf(!s.ready) }
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    BackHandler(enabled = s.pageKey != null && page != null) { a.onPageBack() }
    remember { Motion.scale = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f); true }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        val wide = maxWidth >= 640.dp
        val rail: @Composable () -> Unit = {
            Rail(
                s, s.pageKey ?: selected, openSteam, openDesktop, openEmu, openSetup,
                onToggleSetup = { openSetup = !openSetup },
                onSelect = { key ->
                    if (s.pageKey != null) a.onPageBack()
                    if (key == "steam") openSteam = if (selected == "steam") !openSteam else true
                    if (key == "desktop") openDesktop = if (selected == "desktop") !openDesktop else true
                    if (key.startsWith("emu:")) openEmu = if (selected == key && openEmu == key) "" else key
                    selected = key
                },
                a,
                modifier = if (wide) Modifier.width(236.dp).fillMaxHeight() else Modifier.fillMaxWidth().height(maxHeight * 0.42f),
            )
        }
        val content: @Composable (Modifier) -> Unit = { m -> Pane(s, selected, a, page, m) }
        if (wide) Row(modifier = Modifier.fillMaxSize()) { rail(); content(Modifier.weight(1f).fillMaxHeight()) }
        else Column(modifier = Modifier.fillMaxSize()) { rail(); content(Modifier.weight(1f).fillMaxWidth()) }
    }
}

// ───────────────────────────── Rail ─────────────────────────────

@Composable
private fun Rail(
    s: FrontEndState, selected: String, openSteam: Boolean, openDesktop: Boolean, openEmu: String, openSetup: Boolean,
    onToggleSetup: () -> Unit, onSelect: (String) -> Unit, a: FrontEndActions, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.background(colors.surface).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)) {
            Image(painterResource(R.drawable.logo), null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("SteamDeck", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(
                    when {
                        s.busy -> if (s.percent >= 0) "${s.stage} ${s.percent}%" else s.stage
                        !s.ready -> "runtime not installed"
                        s.available != null && s.available != s.installed -> "runtime ${s.installed} · ${s.available} available"
                        s.running != null -> "${s.running} in the background"
                        else -> "runtime ${s.installed ?: "?"}"
                    },
                    fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(s.busy, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }

        // A session in the background: the way back to it, first.
        var lastRunning by remember { mutableStateOf("") }
        if (s.running != null) lastRunning = s.running
        AnimatedVisibility(
            s.running != null,
            enter = expandVertically(Motion.sp(0.75f)) + fadeIn(Motion.tw(300)) + slideInVertically(Motion.sp(0.6f)) { -it / 2 },
            exit = shrinkVertically(Motion.tw(220)) + fadeOut(Motion.tw(180)),
        ) { RunningTile(lastRunning, a.onResume) }

        // The rail's selection is one pill that glides between rows; the rows only recolour.
        var navOrigin by remember { mutableStateOf(Offset.Zero) }
        val positions = remember { mutableStateMapOf<String, Rect>() }
        Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { val o = it.positionInRoot(); if (o != navOrigin) navOrigin = o }) {
            val target = positions[selected]?.translate(-navOrigin)
            val y by animateFloatAsState(target?.top ?: 0f, Motion.sp(0.72f, Spring.StiffnessLow), label = "indY")
            val x by animateFloatAsState(target?.left ?: 0f, Motion.sp(0.9f), label = "indX")
            val h by animateFloatAsState(target?.height ?: 0f, Motion.sp(1f, Spring.StiffnessMediumLow), label = "indH")
            val w by animateFloatAsState(target?.width ?: 0f, Motion.sp(1f, Spring.StiffnessMediumLow), label = "indW")
            val alpha by animateFloatAsState(if (target != null) 1f else 0f, Motion.tw(250), label = "indA")
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(with(density) { w.toDp() }).height(with(density) { h.toDp() })
                    .alpha(alpha)
                    .graphicsLayer { shadowElevation = 6.dp.toPx(); shape = Shape10; clip = false; ambientShadowColor = colors.primary; spotShadowColor = colors.primary }
                    .clip(Shape10)
                    .background(Brush.linearGradient(listOf(colors.primary, Accent2))),
            )
            val register: (String, LayoutCoordinates) -> Unit = { key, c ->
                val o = c.positionInRoot(); val r = Rect(o.x, o.y, o.x + c.size.width, o.y + c.size.height)
                if (positions[key] != r) positions[key] = r
            }
            val unregister: (String) -> Unit = { positions.remove(it) }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NavItem("Steam", "steam", selected == "steam", caret = openSteam, count = s.steamGames.size, register = register, unregister = unregister) { onSelect("steam") }
                Sub(openSteam) {
                    for ((i, g) in s.steamGames.withIndex()) NavItem(g.name, "app:${g.appId}", selected == "app:${g.appId}", small = true, i = i, register = register, unregister = unregister) { onSelect("app:${g.appId}") }
                    if (s.steamGames.isEmpty()) NavItem("no games installed", "x", false, small = true, muted = true, register = register, unregister = unregister) {}
                    NavItem("Settings", "settings:steam", selected == "settings:steam", small = true, tiny = true, muted = true, i = s.steamGames.size, register = register, unregister = unregister) { a.onSteamSettings() }
                }
                NavItem("Desktop", "desktop", selected == "desktop", caret = openDesktop, count = s.emulators.count { it.installed }, register = register, unregister = unregister) { onSelect("desktop") }
                Sub(openDesktop) {
                    for ((i, e) in s.emulators.filter { it.installed }.withIndex()) {
                        val key = "emu:${e.id}"
                        NavItem(e.name, key, selected == key, small = true, caret = openEmu == key, count = e.games.size, i = i, register = register, unregister = unregister) { onSelect(key) }
                        Sub(openEmu == key) {
                            for ((j, g) in e.games.withIndex()) NavItem(g.name, "rom:${e.id}:$j", selected == "rom:${e.id}:$j", small = true, tiny = true, i = j, register = register, unregister = unregister) { onSelect("rom:${e.id}:$j") }
                            if (e.games.isEmpty()) NavItem(
                                if (e.id == "retroarch") "browses its own games" else if (s.romsDir == null) "choose a ROMs folder" else "nothing for ${e.system} in ROMs",
                                "x", false, small = true, tiny = true, muted = true, register = register, unregister = unregister,
                            ) { if (e.id != "retroarch") a.onRoms() }
                        }
                    }
                    if (s.emulators.none { it.installed }) NavItem("install emulators under Desktop & apps", "x", false, small = true, muted = true, register = register, unregister = unregister) { a.onApps() }
                    NavItem("Settings", "settings:lxqt", selected == "settings:lxqt", small = true, tiny = true, muted = true, i = s.emulators.count { it.installed }, register = register, unregister = unregister) { a.onDesktopSettings() }
                }
            Spacer(Modifier.height(6.dp))
            // Setup: the same fold as Steam and Desktop. A row that holds a value opens a small menu
            // in place; a row that opens a page lights up while the page is shown.
            val menus = remember { MenuHost() }
            val item: @Composable (String, String?, Int, String?, () -> Unit) -> Unit = { label, value, i, key, act ->
                NavItem(label, key ?: "x", key != null && selected == key, small = true, tiny = true, muted = true, value = value, i = i, register = register, unregister = unregister) { act() }
            }
            NavItem("Setup", "x", false, caret = openSetup, count = 9, onClick = onToggleSetup)
            Sub(openSetup) {
                item("Files", null, 0, null, a.onFiles)
                item("Desktop & apps", null, 1, null, a.onApps)
                item("Compatibility tools", null, 2, null, a.onProtons)
                Box {
                    item("Frame generation", s.frameGenLabel, 3, null) { menus.open = "fg" }
                    AnchoredMenu(
                        menus.open == "fg", onDismiss = { if (menus.open == "fg") menus.open = null }, title = "Frame generation",
                        note = "Extra frames between the real ones on the way to the screen, so 30 fps looks like 60. Takes effect at once, mid-game included.",
                    ) {
                        val need = if (s.lsfgReady) null else "install Lossless Scaling in Steam"
                        MenuItem("Off", checked = s.frameGenEngine == FrameGen.ENGINE_OFF) { a.onFrameGenPick(FrameGen.ENGINE_OFF, 2); menus.open = null }
                        for (m in 2..4) MenuItem("Win-FG ${m}×", checked = s.frameGenEngine == FrameGen.ENGINE_WINFG && s.frameGenMultiplier == m) { a.onFrameGenPick(FrameGen.ENGINE_WINFG, m); menus.open = null }
                        for (m in 2..4) MenuItem("LSFG ${m}×", checked = s.frameGenEngine == FrameGen.ENGINE_LSFG && s.frameGenMultiplier == m, enabled = s.lsfgReady, detail = need) { a.onFrameGenPick(FrameGen.ENGINE_LSFG, m); menus.open = null }
                    }
                }
                item("Performance", null, 4, "performance", a.onPerformance)
                item("ROMs folder", s.romsDir?.substringAfterLast('/')?.ifEmpty { s.romsDir } ?: "choose", 5, null, a.onRoms)
                Box {
                    item("Session logs", if (s.logsEnabled) "on" else "off", 6, null) { menus.open = "logs" }
                    AnchoredMenu(
                        menus.open == "logs", onDismiss = { if (menus.open == "logs") menus.open = null }, title = "Session logs",
                        note = "Each session leaves a folder in Downloads: the guest log, the device and network reports, the app's own log.",
                    ) {
                        MenuItem("On", checked = s.logsEnabled) { if (!s.logsEnabled) a.onLogs(); menus.open = null }
                        MenuItem("Off", checked = !s.logsEnabled) { if (s.logsEnabled) a.onLogs(); menus.open = null }
                    }
                }
                Box {
                    item("Start offline", when { s.offlineAccount == null -> "sign in first"; s.offline -> "on"; else -> "off" }, 7, null) { if (s.offlineAccount != null) menus.open = "offline" }
                    AnchoredMenu(
                        menus.open == "offline", onDismiss = { if (menus.open == "offline") menus.open = null }, title = "Start offline",
                        note = "The client starts as ${s.offlineAccount ?: "the saved account"} without the network: the library and installed games, no store.",
                    ) {
                        MenuItem("On", checked = s.offline) { if (!s.offline) a.onOffline(); menus.open = null }
                        MenuItem("Off", checked = !s.offline) { if (s.offline) a.onOffline(); menus.open = null }
                    }
                }
                item("Linux runtime", when {
                    s.busy -> "working…"
                    !s.ready -> "install"
                    s.available != null && s.available != s.installed -> "update"
                    else -> "remove"
                }, 8, null, a.onRuntime)
            }
            }
        }

        Text(
            "credits", fontSize = 11.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.clip(Shape10).clickable(onClick = a.onCredits).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RunningTile(name: String, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shift by animateFloatAsState(if (hot) 3f else 0f, Motion.sp(0.6f), label = "runShift")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(1600, easing = Motion.Ease), RepeatMode.Restart), label = "ring")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .graphicsLayer { translationX = shift.dp.toPx() }
            .clip(Shape12)
            .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.22f), Accent2.copy(alpha = 0.10f))))
            .border(1.dp, colors.primary.copy(alpha = if (hot) 0.8f else 0.35f), Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onResume)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(14.dp).graphicsLayer { scaleX = ring; scaleY = ring; alpha = (1.6f - ring) / 1.2f }.border(1.5.dp, Good, CircleShape))
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Good))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("running · tap to go back", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

/** A sub-list that unfolds; its children stagger in through [NavItem]'s index. */
@Composable
private fun Sub(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(Motion.tw(420)) + fadeIn(Motion.tw(300, 50)),
        exit = shrinkVertically(Motion.tw(300)) + fadeOut(Motion.tw(200)),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp).fillMaxWidth()
                .drawWithContent { drawContent(); drawRect(Color(0xFF26303C), size = size.copy(width = 1.dp.toPx())) }
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) { content() }
    }
}

@Composable
private fun NavItem(
    label: String, key: String, current: Boolean, small: Boolean = false, tiny: Boolean = false, muted: Boolean = false,
    caret: Boolean? = null, count: Int? = null, value: String? = null, i: Int = 0,
    register: ((String, LayoutCoordinates) -> Unit)? = null, unregister: ((String) -> Unit)? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val fg by animateColorAsState(if (current) colors.onPrimary else if (muted) colors.onSurfaceVariant else colors.onBackground, Motion.tw(280), label = "navFg")
    val sub by animateColorAsState(if (current) colors.onPrimary else colors.onSurfaceVariant, Motion.tw(280), label = "navSub")
    val ring by animateColorAsState(if (focused) colors.primary else Color.Transparent, Motion.tw(180), label = "navRing")
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "navScale")
    val rot by animateFloatAsState(if (caret == true) 90f else 0f, Motion.sp(0.6f), label = "caret")
    if (key != "x") DisposableEffect(key) { onDispose { unregister?.invoke(key) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (register != null && key != "x") Modifier.onGloballyPositioned { register(key, it) } else Modifier)
            .then(if (small && i >= 0 && register != null) Modifier.staggerIn(i) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape10)
            .background(if (hovered && !current) Color.White.copy(alpha = 0.04f) else if (current && hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.5.dp, ring, Shape10)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (small) 7.dp else 9.dp),
    ) {
        if (caret != null) {
            Text("▶", fontSize = 9.sp, color = sub, modifier = Modifier.width(14.dp).rotate(rot))
        }
        Text(
            label, fontSize = if (tiny) 13.sp else if (small) 14.sp else 15.sp,
            fontWeight = if (tiny) FontWeight.Normal else if (small) FontWeight.Medium else FontWeight.SemiBold,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (value != null) Text(value, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(
            count.toString(), fontSize = 11.sp, color = sub,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (current) Color.White.copy(alpha = 0.28f) else colors.background).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

// ───────────────────────────── Pane ─────────────────────────────

/** The chosen thing, behind a blurred wash of its own colour; a change sinks the old page out and cascades the new one in. */
@Composable
private fun Pane(s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier) {
    Box(modifier = modifier) {
        val wash: Pair<File?, Float> = when {
            s.pageKey != null && page != null -> null to 250f
            selected == "steam" -> null to 268f
            selected == "desktop" -> null to 200f
            selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }.let { it?.art to hueOf(it?.name ?: "") }
            selected.startsWith("emu:") -> null to hueOf(selected)
            selected.startsWith("rom:") -> romFor(s, selected).let { it?.second?.art to hueOf(it?.second?.name ?: "") }
            else -> null to 268f
        }
        Backdrop(wash)
        AnimatedContent(
            targetState = if (page != null && s.pageKey != null) s.pageKey else selected,
            transitionSpec = {
                (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                    .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                    .apply { targetContentZIndex = 1f }
            },
            label = "pane",
        ) { key -> if (page != null && key == s.pageKey) page() else Content(s, key, a, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Backdrop(wash: Pair<File?, Float>) {
    val colors = MaterialTheme.colorScheme
    Crossfade(targetState = wash, animationSpec = Motion.tw(700), label = "backdrop") { (art, hue) ->
        val canBlur = Build.VERSION.SDK_INT >= 31
        Box(modifier = Modifier.fillMaxSize().alpha(if (canBlur) 0.28f else 0.18f).then(if (canBlur) Modifier.blur(70.dp) else Modifier)) {
            if (art != null && canBlur) {
                AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint(hue, 0.8f, 0.55f), Color.Transparent), center = Offset(0.3f, 0.3f), radius = 900f)))
                Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint((hue + 60f) % 360f, 0.7f, 0.45f), Color.Transparent), center = Offset(1600f, 1400f), radius = 900f)))
            }
        }
    }
    Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.35f)))))
}

private fun romFor(s: FrontEndState, selected: String): Pair<Library.Emulator, Library.Rom>? {
    val parts = selected.split(":")
    val e = s.emulators.firstOrNull { it.id == parts.getOrNull(1) } ?: return null
    val g = e.games.getOrNull(parts.getOrNull(2)?.toIntOrNull() ?: -1) ?: return null
    return e to g
}

@Composable
private fun Content(s: FrontEndState, selected: String, a: FrontEndActions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
        when {
            selected == "steam" -> {
                Rise(0) { Eyebrow("Steam") }
                Rise(1) { Title("Valve's native client, under gamescope") }
                Rise(2) { Lede("Big Picture with a controller, or the client's desktop UI. Installed games launch straight from the rail or the shelf below.") }
                Rise(3) {
                    Actions {
                        PrimaryButton("Play", enabled = s.ready && !s.busy, onClick = a.onPlay)
                        SecondaryButton("Desktop UI", enabled = s.ready && !s.busy, onClick = a.onPlayDesktopUi)
                        Cog(a.onSteamSettings)
                    }
                }
                Rise(4) { SectionTitle("Installed", "${s.steamGames.size} game${if (s.steamGames.size == 1) "" else "s"}") }
                if (s.steamGames.isEmpty()) Rise(5) { Note("Nothing installed yet. Press Play, sign in, and install from the store; games appear here and on the left.") }
                else Rise(5, Modifier.weight(1f).fillMaxWidth()) { ArtGrid(s.steamGames.map { g -> Tile(g.name, g.library, g.art, "steam:${g.appId}", null) { a.onSteamGame(g) } }) }
            }
            selected.startsWith("app:") -> {
                val g = s.steamGames.firstOrNull { "app:${it.appId}" == selected }
                if (g == null) Note("That game is no longer installed.") else {
                    Rise(0) { Eyebrow("Steam · ${g.library}") }
                    Rise(1) { Title(g.name) }
                    Rise(2) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Lede("Starts the Steam session and launches the game straight away (steam://rungameid/${g.appId}).")
                                Actions {
                                    PrimaryButton("Launch", enabled = s.ready && !s.busy) { a.onSteamGame(g) }
                                    Cog(a.onSteamSettings)
                                    Chip(if (s.ready) "● ready" else "runtime missing", ok = s.ready)
                                }
                            }
                            Poster(g.art, g.name, Modifier.width(120.dp))
                        }
                    }
                    val others = s.steamGames.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle("More from the library", null) }
                        Rise(4, Modifier.weight(1f).fillMaxWidth()) { ArtGrid(others.map { x -> Tile(x.name, x.library, x.art, "steam:${x.appId}", null) { a.onSteamGame(x) } }) }
                    }
                }
            }
            selected == "desktop" -> {
                Rise(0) { Eyebrow("Desktop") }
                Rise(1) { Title("Linux desktop environment") }
                Rise(2) { Lede("Files, Firefox and the emulators' own windows. Emulators and their games launch from the rail, under gamescope, where the GPU is.") }
                Rise(3) {
                    Actions {
                        PrimaryButton(if (s.desktopInstalled) "Desktop" else "Install the desktop first", enabled = s.ready && !s.busy && s.desktopInstalled, onClick = a.onDesktop)
                        SecondaryButton("Desktop & apps", onClick = a.onApps)
                        Cog(a.onDesktopSettings)
                    }
                }
                Rise(4) { SectionTitle("Emulators", "${s.emulators.count { it.installed }} installed · ${s.emulators.count { !it.installed }} available") }
                Rise(5) {
                    Text("?  why emulators do not run on the desktop", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp).clip(Shape10).clickable(onClick = a.onEmulatorHelp).padding(horizontal = 6.dp, vertical = 4.dp))
                }
                Rise(6, Modifier.weight(1f).fillMaxWidth()) {
                    ArtGrid(s.emulators.map { e -> Tile(e.name, if (e.installed) (if (e.id == "retroarch") "browses its own games" else "${e.games.size} game${if (e.games.size == 1) "" else "s"}") else "not installed", null, "emu:${e.id}", e.iconRes, dim = !e.installed) { if (e.installed) a.onEmulator(e) else a.onApps() } })
                }
            }
            selected.startsWith("emu:") -> {
                val e = s.emulators.firstOrNull { "emu:${it.id}" == selected }
                if (e == null) Note("Not installed.") else {
                    Rise(0) { Eyebrow("Desktop · ${e.system}") }
                    Rise(1) { Title(e.name) }
                    Rise(2) { Lede("Opens fullscreen under gamescope. Games are the ${e.system} files in the ROMs folder; each launches straight in.") }
                    Rise(3) {
                        Actions {
                            Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                            PrimaryButton("Open ${e.name}", enabled = s.ready && !s.busy) { a.onEmulator(e) }
                            SecondaryButton("ROMs folder", onClick = a.onRoms)
                        }
                    }
                    Rise(4) { SectionTitle("Games", e.games.size.toString()) }
                    if (e.games.isEmpty()) Rise(5) {
                        Note(
                            if (s.romsDir == null) "Choose a ROMs folder first (left, or the button above)."
                            else if (e.id == "retroarch") "RetroArch loads its games itself: open it and browse to root › ROMs."
                            else "Put ${e.system} games in ROMs/${e.system.substringBefore(' ')} (or the ROMs folder itself); the list rebuilds when this screen opens.",
                        )
                    }
                    else Rise(5, Modifier.weight(1f).fillMaxWidth()) {
                        ArtGrid(e.games.map { g -> Tile(g.name, if (g.art != null) "installed" else g.hostPath.extension.uppercase().ifEmpty { "folder" }, g.art, "rom:${g.hostPath}", e.iconRes) { a.onRom(g) } }, wide = e.games.none { it.art != null })
                    }
                }
            }
            selected.startsWith("rom:") -> {
                val pair = romFor(s, selected)
                if (pair == null) Note("That game is gone from the ROMs folder.") else {
                    val (e, g) = pair
                    Rise(0) { Eyebrow("Desktop · ${e.name}") }
                    Rise(1) { Title(g.name) }
                    Rise(2) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.guestPath, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp))
                                Spacer(Modifier.height(14.dp))
                                Actions {
                                    Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                                    PrimaryButton("Launch in ${e.name}", enabled = s.ready && !s.busy) { a.onRom(g) }
                                    Chip(g.hostPath.extension.uppercase().ifEmpty { "folder" }, ok = false)
                                }
                            }
                            if (g.art != null) Poster(g.art, g.name, Modifier.width(120.dp))
                        }
                    }
                    val others = e.games.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle("Also in ${e.name}", null) }
                        Rise(4, Modifier.weight(1f).fillMaxWidth()) {
                            ArtGrid(others.map { x -> Tile(x.name, if (x.art != null) "installed" else x.hostPath.extension.uppercase().ifEmpty { "folder" }, x.art, "rom:${x.hostPath}", e.iconRes) { a.onRom(x) } }, wide = others.none { it.art != null })
                        }
                    }
                }
            }
            else -> Note("Pick something on the left.")
        }
    }
}

private class Tile(val title: String, val sub: String, val art: File?, val key: String, val iconRes: Int? = null, val dim: Boolean = false, val onClick: () -> Unit)

// ───────────────────────────── Type + small parts ─────────────────────────────

@Composable
internal fun Eyebrow(t: String) {
    val colors = MaterialTheme.colorScheme
    val rule = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rule.animateTo(1f, Motion.tw(600, 120)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.width(18.dp).height(1.5.dp).graphicsLayer { scaleX = rule.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(colors.primary))
        Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.primary)
    }
}
@Composable internal fun Title(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
@Composable internal fun Lede(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
@Composable
private fun SectionTitle(t: String, detail: String?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant)
        if (detail != null) Text(detail, fontSize = 11.sp, color = colors.onBackground)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFF26303C)))
    }
}
@Composable private fun Note(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().clip(Shape12).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0xFF33404F), Shape12).padding(12.dp))
@Composable private fun Actions(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
private fun Chip(t: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        t, fontSize = 11.sp, color = if (ok) Good else colors.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(colors.surfaceVariant).border(1.dp, if (ok) Good.copy(alpha = 0.3f) else Color(0xFF26303C), RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Hover/focus/press state shared by every control that lifts, glows or squashes. */
@Composable
private fun rememberHot(src: MutableInteractionSource): Boolean = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value

/** The launch button: gradient, a sheen that sweeps on focus, a squash on press. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "btnScale")
    val lift by animateFloatAsState(if (hot) 14f else 6f, Motion.tw(300), label = "btnLift")
    val nudge by animateFloatAsState(if (hot) 2f else 0f, Motion.sp(0.5f), label = "tri")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (enabled) lift.dp.toPx() else 0f; shape = Shape12; clip = false; ambientShadowColor = colors.primary; spotShadowColor = colors.primary }
            .clip(Shape12)
            .background(if (enabled) Brush.linearGradient(listOf(colors.primary, Accent2)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surfaceVariant)))
            .shine(hot, 0.45f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text("▶", fontSize = 11.sp, color = if (enabled) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.graphicsLayer { translationX = nudge.dp.toPx() })
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = if (enabled) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "secScale")
    val edge by animateColorAsState(if (hot) colors.primary else Color(0xFF33404F), Motion.tw(250), label = "secEdge")
    val fill by animateColorAsState(if (hot) colors.primary.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f), Motion.tw(250), label = "secFill")
    Box(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(Shape12).background(fill).border(1.dp, edge, Shape12)
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = colors.onBackground, maxLines = 1) }
}

@Composable
private fun Cog(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val rot by animateFloatAsState(if (hot) 90f else 0f, Motion.sp(0.55f), label = "cog")
    val edge by animateColorAsState(if (hot) colors.primary else Color(0xFF33404F), Motion.tw(250), label = "cogEdge")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(42.dp).clip(Shape12).background(Color.White.copy(alpha = 0.03f)).border(1.dp, edge, Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
    ) { Icon(Icons.Filled.Settings, "Settings", tint = if (hot) colors.primary else colors.onBackground, modifier = Modifier.size(18.dp).rotate(rot)) }
}

@Composable
private fun Poster(art: File?, name: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.sp(0.6f, Spring.StiffnessLow)) }
    Box(
        modifier = modifier.padding(start = 16.dp).aspectRatio(2f / 3f)
            .graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 16.dp.toPx(); rotationZ = (1f - t.value) * 2f; scaleX = 0.94f + 0.06f * t.value; scaleY = scaleX; shadowElevation = 22.dp.toPx(); shape = Shape12; clip = false }
            .clip(Shape12).background(artBrush(hueOf(name))),
    ) {
        if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────────── Grid + tiles ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtGrid(tiles: List<Tile>, wide: Boolean = false) {
    val square = tiles.isNotEmpty() && tiles.all { it.art == null && it.iconRes != null }
    LazyVerticalGrid(
        // Thumbnails to recognise a game by, not posters; icon tiles are squares.
        columns = GridCells.Adaptive(minSize = if (square) 64.dp else if (wide) 92.dp else 70.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp, start = 4.dp, end = 4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tiles, key = { it.key }) { t ->
            val src = remember { MutableInteractionSource() }
            val hot = rememberHot(src)
            Box(modifier = Modifier.animateItemPlacement().zIndex(if (hot) 1f else 0f)) { GameTile(t, wide, square, src, hot) }
        }
    }
}

/** A tile lifts, rings and shines when the pad lands on it; its play badge pops in. */
@Composable
private fun GameTile(t: Tile, wide: Boolean, square: Boolean, src: MutableInteractionSource, hot: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (hot) 1.04f else 1f, Motion.sp(0.55f, Spring.StiffnessMedium), label = "tileScale")
    val lift by animateFloatAsState(if (hot) -5f else 0f, Motion.sp(0.6f), label = "tileLift")
    val elev by animateFloatAsState(if (hot) 18f else 2f, Motion.tw(300), label = "tileElev")
    val ring by animateColorAsState(if (hot) colors.primary else Color.Transparent, Motion.tw(220), label = "tileRing")
    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift.dp.toPx(); shadowElevation = elev.dp.toPx(); shape = Shape12; clip = false; ambientShadowColor = if (hot) colors.primary else Color.Black; spotShadowColor = if (hot) colors.primary else Color.Black; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f) }
            .clip(Shape12)
            .background(colors.surface)
            .border(1.5.dp, ring, Shape12)
            .alpha(if (t.dim && !hot) 0.55f else 1f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = t.onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().shine(hot)) {
            Art(t.art, t.iconRes, t.title, Modifier.fillMaxWidth(), wide)
            // Qualified: the enclosing Column would otherwise pick its scoped overload.
            androidx.compose.animation.AnimatedVisibility(
                visible = hot, modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(Motion.sp(0.5f), initialScale = 0.5f) + fadeIn(Motion.tw(200)),
                exit = scaleOut(Motion.tw(150), targetScale = 0.6f) + fadeOut(Motion.tw(150)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(if (square) 26.dp else 32.dp).graphicsLayer { shadowElevation = 10.dp.toPx(); shape = CircleShape; clip = false; spotShadowColor = colors.primary }.clip(CircleShape).background(colors.primary),
                ) { Text("▶", fontSize = if (square) 9.sp else 11.sp, color = colors.onPrimary, modifier = Modifier.padding(start = 2.dp)) }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(t.title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.sub, fontSize = 8.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Art(art: File?, iconRes: Int?, label: String, modifier: Modifier, wide: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    // Game art is a poster (2:3) or a screenshot (16:9); an emulator's icon is a square.
    val ratio = if (art == null && iconRes != null) 1f else if (wide) 16f / 9f else 2f / 3f
    Box(modifier = modifier.aspectRatio(ratio).background(if (art == null && iconRes == null) artBrush(hueOf(label)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surface)))) {
        when {
            art != null -> AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            iconRes != null -> Image(painterResource(iconRes), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (wide) 10.dp else 8.dp))
            else -> {
                Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.align(Alignment.BottomStart).padding(5.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
