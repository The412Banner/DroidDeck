package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties


private val RowShape = RoundedCornerShape(12.dp)
private val GroupShape = RoundedCornerShape(14.dp)

internal fun Modifier.controllerConfirm(enabled: Boolean = true, onClick: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val keyEvent = event.nativeKeyEvent
    if (keyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_A) {
        false
    } else {
        if (enabled && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) onClick()
        true
    }
}

internal fun Modifier.controllerBack(onBack: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val keyEvent = event.nativeKeyEvent
    if (keyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_B && keyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
        false
    } else {
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) onBack()
        true
    }
}

class MenuHost {
    var open by mutableStateOf<String?>(null)
}

@Composable
fun rememberMenuHost(): MenuHost = remember { MenuHost() }

private class BelowEndProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = anchorBounds.right - popupContentSize.width
        if (x < 8) x = 8
        var y = anchorBounds.bottom + gap
        if (y + popupContentSize.height > windowSize.height - 8) y = anchorBounds.top - gap - popupContentSize.height
        if (y < 8) y = 8
        return IntOffset(x, y)
    }
}

@Composable
fun AnchoredMenu(open: Boolean, onDismiss: () -> Unit, title: String? = null, note: String? = null, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState && state.isIdle) return
    val firstItemFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(open, inputMode) {
        if (open && inputMode == InputMode.Keyboard) {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { firstItemFocus.requestFocus() }
            android.util.Log.i("AnchoredMenu", "requested first option focus")
        }
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gap) { BelowEndProvider(gap) }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.sp(0.7f), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            label = "menu",
        ) {
            // A long list (a driver menu with its downloads) must not run off the screen: the menu is
            // capped at most of the window's height and its entries scroll under the fixed title.
            val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 340.dp)
                    .heightIn(max = maxHeight)
                    .shadow(24.dp, RowShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RowShape)
                    .background(pal.surfaceVariant.copy(alpha = 0.95f))
                    .border(1.dp, pal.signal.copy(alpha = 0.22f), RowShape)
                    .controllerBack(onDismiss)
                    .padding(6.dp),
            ) {
                if (title != null) Text(
                    title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content(firstItemFocus) }
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                    Text(note, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
fun MenuItem(
    label: String, checked: Boolean, enabled: Boolean = true, detail: String? = null,
    trailing: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shift by animateFloatAsState(if (hot) 2f else 0f, Motion.sp(0.5f), label = "miShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .graphicsLayer { translationX = shift.dp.toPx() }
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hot && enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.4f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text("✓", fontSize = 12.sp, color = pal.signal, modifier = Modifier.width(18.dp).alpha(if (checked) 1f else 0f))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = if (checked) pal.signal else colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun ValueChip(text: String, open: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val pal = LocalPalette.current
    val edge by animateColorAsState(if (open || hot) pal.signal else pal.line2, Motion.tw(200), label = "chipEdge")
    val rot by animateFloatAsState(if (open) 180f else 0f, Motion.sp(0.6f), label = "chipCaret")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .widthIn(min = 150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn(Motion.tw(260)) + slideInVertically(Motion.tw(260)) { it / 2 }) togetherWith (fadeOut(Motion.tw(160)) + slideOutVertically(Motion.tw(160)) { -it / 2 }) },
            label = "chipText",
        ) { t -> Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.width(8.dp))
        Text("▾", fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.rotate(rot))
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(pal.line))
    }
    Column(modifier = Modifier.fillMaxWidth().clip(GroupShape).background(colors.surface).border(1.dp, pal.line, GroupShape)) { content() }
}

@Composable
fun SettingsRow(label: String, hint: String?, highlighted: Boolean = false, control: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val bg by animateColorAsState(if (highlighted) pal.signal.copy(alpha = 0.10f) else Color.Transparent, Motion.tw(200), label = "rowBg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (hint != null) Text(hint, fontSize = 11.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
fun <T> ChoiceRow(
    host: MenuHost, key: String, label: String, hint: String?,
    options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, note: String? = null,
    /** For the box itself - a page's FocusRequester for its first control. */
    chipModifier: Modifier = Modifier,
    onPick: (T) -> Unit,
) {
    val open = host.open == key
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(options.firstOrNull { it.first == selected }?.second ?: "-", open, enabled, modifier = chipModifier) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) { firstItemFocus ->
                options.forEachIndexed { index, (value, text) ->
                    MenuItem(text, checked = value == selected, focusRequester = if (index == 0) firstItemFocus else null) {
                        onPick(value)
                        host.open = null
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(host: MenuHost, key: String, label: String, hint: String?, checked: Boolean, enabled: Boolean = true, chipModifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    ChoiceRow(host, key, label, hint, listOf(true to "On", false to "Off"), checked, enabled, chipModifier = chipModifier, onPick = onChange)

@Composable
fun MultiRow(
    host: MenuHost, key: String, label: String, hint: String?,
    items: List<Pair<Int, String>>, selected: Set<Int>, enabled: Boolean = true, note: String? = null,
    onToggle: (Int, Boolean) -> Unit,
) {
    val open = host.open == key
    val summary = when {
        selected.size >= items.size -> "All ${items.size}"
        selected.isEmpty() -> "None"
        else -> "${selected.size} of ${items.size}"
    }
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(summary, open, enabled) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) { firstItemFocus ->
                items.forEachIndexed { index, (value, text) ->
                    val on = value in selected
                    MenuItem(text, checked = on, focusRequester = if (index == 0) firstItemFocus else null) { onToggle(value, !on) }
                }
            }
        }
    }
}

@Composable
fun ActionRow(label: String, hint: String?, button: String, onClick: () -> Unit) {
    SettingsRow(label, hint) { SecondaryButton(button, onClick = onClick) }
}

@Composable
fun SettingsPage(
    host: MenuHost,
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    lede: String? = null,
    /** A small control at the right of the title (the driver pages' refresh button). */
    action: (@Composable () -> Unit)? = null,
    /** Pass one held above the page to keep its scroll position across a page opened over it. */
    scroll: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = scroll ?: rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val dim by animateFloatAsState(if (host.open != null) 0.6f else 1f, Motion.tw(220), label = "pageDim")
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Rise(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Outlined when a controller's focus is on it, like every other control here.
                val backSrc = remember { MutableInteractionSource() }
                val backHot = backSrc.collectIsFocusedAsState().value || backSrc.collectIsHoveredAsState().value
                Text(
                    "‹  Back", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (backHot) LocalPalette.current.signal else colors.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .border(2.dp, if (backHot) LocalPalette.current.signal else Color.Transparent, RoundedCornerShape(8.dp))
                        .hoverable(backSrc).clickable(interactionSource = backSrc, indication = null, onClick = onBack)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (eyebrow != null) {
                    Spacer(Modifier.width(10.dp))
                    Eyebrow(eyebrow)
                }
            }
        }
        Rise(1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Title(title) }
                if (action != null) action()
            }
        }
        if (lede != null) Rise(2) { Lede(lede) }
        Rise(3, Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim }.verticalScroll(scrollState).padding(bottom = 24.dp)) { content() }
        }
    }
}
