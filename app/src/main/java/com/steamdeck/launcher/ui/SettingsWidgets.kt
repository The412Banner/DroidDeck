package com.steamdeck.launcher.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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

/*
 * Settings without a pop-up: a page of rows, each with its value in a chip on the right, and a
 * small faded menu that opens right under the chip when it is tapped. One menu at a time per
 * page; the rest of the page dims a little while it is open.
 */

private val RowShape = RoundedCornerShape(12.dp)
private val GroupShape = RoundedCornerShape(14.dp)
private val Line = Color(0xFF26303C)
private val Line2 = Color(0xFF33404F)

/** Which row's menu is open on a page, so the page can dim and only one menu shows. */
class MenuHost {
    var open by mutableStateOf<String?>(null)
}

@Composable
fun rememberMenuHost(): MenuHost = remember { MenuHost() }

/** Under the anchor, right edges aligned; above it when the bottom of the window is too close. */
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

/**
 * The menu itself, anchored to whatever composable it is placed inside (put it in the same Box as
 * the chip). It fades and scales in from its top-right corner and out again on dismiss; the popup
 * is only removed once the exit has finished.
 */
@Composable
fun AnchoredMenu(open: Boolean, onDismiss: () -> Unit, title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState && state.isIdle) return
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gap) { BelowEndProvider(gap) }
    val colors = MaterialTheme.colorScheme
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.sp(0.7f), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            label = "menu",
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 340.dp)
                    .shadow(24.dp, RowShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RowShape)
                    .background(Color(0xF21E2530))
                    .border(1.dp, colors.primary.copy(alpha = 0.22f), RowShape)
                    .padding(6.dp),
            ) {
                if (title != null) Text(
                    title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
                content()
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Text(note, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** One line of a menu: a check mark when it is the current value, a label, an optional second line. */
@Composable
fun MenuItem(
    label: String, checked: Boolean, enabled: Boolean = true, detail: String? = null,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shift by animateFloatAsState(if (hot) 2f else 0f, Motion.sp(0.5f), label = "miShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer { translationX = shift.dp.toPx() }
            .clip(RoundedCornerShape(8.dp))
            .background(if (hot && enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.4f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text("✓", fontSize = 12.sp, color = colors.primary, modifier = Modifier.width(18.dp).alpha(if (checked) 1f else 0f))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = if (checked) colors.primary else colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** The value on the right of a row: the current choice and a caret that turns while the menu is open. */
@Composable
fun ValueChip(text: String, open: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val edge by animateColorAsState(if (open || hot) colors.primary else Line2, Motion.tw(200), label = "chipEdge")
    val rot by animateFloatAsState(if (open) 180f else 0f, Motion.sp(0.6f), label = "chipCaret")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .widthIn(min = 150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
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

/** A group of rows on one surface, with a section label above it. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Line))
    }
    Column(modifier = Modifier.fillMaxWidth().clip(GroupShape).background(colors.surface).border(1.dp, Line, GroupShape)) { content() }
}

/** A row: label and hint on the left, whatever control on the right. */
@Composable
fun SettingsRow(label: String, hint: String?, highlighted: Boolean = false, control: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (highlighted) colors.primary.copy(alpha = 0.10f) else Color.Transparent, Motion.tw(200), label = "rowBg")
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** A row whose value is one of a list: the chip opens the menu, a pick closes it. */
@Composable
fun <T> ChoiceRow(
    host: MenuHost, key: String, label: String, hint: String?,
    options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, note: String? = null,
    onPick: (T) -> Unit,
) {
    val open = host.open == key
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(options.firstOrNull { it.first == selected }?.second ?: "—", open, enabled) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) {
                for ((value, text) in options) MenuItem(text, checked = value == selected) { onPick(value); host.open = null }
            }
        }
    }
}

/** On or off, as a two-line menu, so every row on the page behaves the same way under a pad. */
@Composable
fun ToggleRow(host: MenuHost, key: String, label: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    ChoiceRow(host, key, label, hint, listOf(true to "On", false to "Off"), checked, enabled, onPick = onChange)

/** Several of a list at once (the core masks): the menu stays open while boxes are ticked. */
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
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) {
                for ((value, text) in items) {
                    val on = value in selected
                    MenuItem(text, checked = on) { onToggle(value, !on) }
                }
            }
        }
    }
}

/** A row that does something (import, choose a folder) instead of holding a value. */
@Composable
fun ActionRow(label: String, hint: String?, button: String, onClick: () -> Unit) {
    SettingsRow(label, hint) { SecondaryButton(button, onClick = onClick) }
}

/** A page: eyebrow with a way back, title, lede, and the rows below it, dimmed while a menu is open. */
@Composable
fun SettingsPage(host: MenuHost, eyebrow: String, title: String, lede: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dim by animateFloatAsState(if (host.open != null) 0.6f else 1f, Motion.tw(220), label = "pageDim")
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Rise(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹  Back", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(10.dp))
                Eyebrow(eyebrow)
            }
        }
        Rise(1) { Title(title) }
        Rise(2) { Lede(lede) }
        Rise(3, Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim }.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { content() }
        }
    }
}
