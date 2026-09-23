package com.steamdeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * A pad drawn on the screen for devices that have no controller attached.
 *
 * It writes into the same [PadBridge] a physical pad does, so the Steam client sees one Xbox pad
 * whichever is being used, and the two can be used at once. A d-pad, two sticks, the four face
 * buttons, the bumpers, and the three centre buttons: enough to drive Big Picture and to play,
 * and no control editor.
 *
 * Where the controls go follows the picture. The compositor letterboxes the session's output onto
 * the panel, and a foldable or a wide phone has bars beside or above and below it that are empty
 * black: the controls take the bars first, and only overlay the picture when there are none wide
 * enough. Only the controls themselves take touches; everything else falls through to the
 * activity, where it moves the mouse pointer.
 */
@SuppressLint("ViewConstructor")
class OnScreenControls(context: Context, private val pad: PadBridge) : View(context) {

    private class Control(
        val id: String,
        val label: String,
        /** Bit index in GamepadState, or -1. */
        val button: Int,
        /** Index into GamepadState.dpad, or -1. */
        val dpad: Int,
        /** 0 = left stick, 1 = right stick, -1 = a button. */
        val stick: Int,
        val radius: Float,
    ) {
        var cx = 0f
        var cy = 0f
        var pressedBy = -1     // the pointer id holding it, or -1
        /** A stick's knob offset from its centre, in [-radius, radius]. */
        var kx = 0f
        var ky = 0f
        /** A stick that moved or was let go: its axes are written once, then left alone. */
        var dirty = false
        fun contains(x: Float, y: Float): Boolean {
            val dx = x - cx
            val dy = y - cy
            // A generous hit area: a finger on glass is not a mouse, and a miss in Big Picture
            // means the user thinks the pad does not work.
            val r = radius * 1.25f
            return dx * dx + dy * dy <= r * r
        }
        /** Moves the knob towards the finger, clamped to the base. */
        fun drag(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius) { dx = dx / d * radius; dy = dy / d * radius }
            kx = dx; ky = dy; dirty = true
        }
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val controls = listOf(
        Control("up", "▲", -1, 0, -1, dp(26f)),
        Control("right", "▶", -1, 1, -1, dp(26f)),
        Control("down", "▼", -1, 2, -1, dp(26f)),
        Control("left", "◀", -1, 3, -1, dp(26f)),
        Control("ls", "L", -1, -1, 0, dp(48f)),
        Control("rs", "R", -1, -1, 1, dp(48f)),
        Control("a", "A", 0, -1, -1, dp(30f)),
        Control("b", "B", 1, -1, -1, dp(30f)),
        Control("x", "X", 2, -1, -1, dp(30f)),
        Control("y", "Y", 3, -1, -1, dp(30f)),
        Control("lb", "LB", 4, -1, -1, dp(24f)),
        Control("rb", "RB", 5, -1, -1, dp(24f)),
        Control("select", "⧉", 6, -1, -1, dp(20f)),
        Control("start", "☰", 7, -1, -1, dp(20f)),
        // The client's own in-game menu; the interposer publishes it as BTN_MODE.
        Control("guide", "◉", GamepadState.IDX_BUTTON_MODE.toInt(), -1, -1, dp(22f)),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Where the session's picture is drawn on this view, or null when the whole view is picture. */
    private var picture: RectF? = null

    /** The activity tells the controls where the picture lands; the layout follows it. */
    fun setPicture(rect: RectF?) {
        val same = (rect == null && picture == null) || (rect != null && picture != null && rect == picture)
        if (same) return
        picture = rect?.let { RectF(it) }
        if (width > 0 && height > 0) { layoutControls(width.toFloat(), height.toFloat()); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w.toFloat(), h.toFloat())
    }

    /**
     * Three layouts: side bars (a wide panel showing 16:9), bottom band (a squarer panel showing
     * a wide picture), and the overlay when neither bar is wide enough for a cluster. Thumbs reach
     * the bottom corners in every one; nothing is placed where a game's own HUD usually is.
     */
    private fun layoutControls(w: Float, h: Float) {
        val p = picture
        val sideBar = if (p != null) minOf(p.left, w - p.right) else 0f
        val bottomBand = if (p != null) h - p.bottom else 0f
        val margin = dp(34f)
        when {
            // A cluster (d-pad or face buttons) is ~156 dp across; a stick above it needs the height.
            sideBar >= dp(160f) && p != null -> {
                val lx = p.left / 2f
                val rx = p.right + (w - p.right) / 2f
                val bottom = h - margin - dp(74f)
                dpadAt(lx, bottom)
                faceAt(rx, bottom)
                place("ls", lx, bottom - dp(196f))
                place("rs", rx, bottom - dp(196f))
                place("lb", lx, bottom - dp(196f) - dp(96f))
                place("rb", rx, bottom - dp(196f) - dp(96f))
                place("select", lx, h - margin + dp(8f))
                place("start", rx, h - margin + dp(8f))
                place("guide", w / 2f, h - dp(22f))
            }
            bottomBand >= dp(190f) && p != null -> {
                val cy = p.bottom + bottomBand / 2f + dp(12f)
                val dx = margin + dp(74f)
                val fx = w - margin - dp(74f)
                dpadAt(dx, cy)
                faceAt(fx, cy)
                place("ls", dx + dp(150f), cy)
                place("rs", fx - dp(150f), cy)
                place("lb", dx, p.bottom + dp(30f))
                place("rb", fx, p.bottom + dp(30f))
                val centre = w / 2f
                place("select", centre - dp(60f), h - dp(30f))
                place("guide", centre, h - dp(30f))
                place("start", centre + dp(60f), h - dp(30f))
            }
            else -> {
                val dx = margin + dp(74f)
                val fx = w - margin - dp(74f)
                val bottom = h - margin - dp(74f)
                dpadAt(dx, bottom)
                faceAt(fx, bottom)
                place("ls", dx + dp(142f), bottom)
                place("rs", fx - dp(142f), bottom)
                place("lb", margin + dp(46f), h - margin - dp(196f))
                place("rb", w - margin - dp(46f), h - margin - dp(196f))
                val centre = w / 2f
                place("select", centre - dp(60f), h - margin - dp(26f))
                place("guide", centre, h - margin - dp(26f))
                place("start", centre + dp(60f), h - margin - dp(26f))
            }
        }
    }

    private fun dpadAt(x: Float, y: Float) {
        val step = dp(52f)
        place("up", x, y - step)
        place("down", x, y + step)
        place("left", x - step, y)
        place("right", x + step, y)
    }

    private fun faceAt(x: Float, y: Float) {
        val step = dp(50f)
        place("a", x, y + step)
        place("b", x + step, y)
        place("x", x - step, y)
        place("y", x, y - step)
    }

    private fun place(id: String, x: Float, y: Float) {
        val control = controls.firstOrNull { it.id == id } ?: return
        control.cx = x
        control.cy = y
    }

    override fun onDraw(canvas: Canvas) {
        for (control in controls) {
            val held = control.pressedBy != -1
            if (control.stick >= 0) {
                // The base, then the knob where the finger holds it.
                fill.color = if (held) Color.argb(60, 199, 125, 255) else Color.argb(50, 20, 12, 30)
                canvas.drawCircle(control.cx, control.cy, control.radius, fill)
                stroke.color = if (held) Color.argb(200, 235, 212, 255) else Color.argb(90, 201, 160, 255)
                canvas.drawCircle(control.cx, control.cy, control.radius, stroke)
                val knob = control.radius * 0.46f
                fill.color = if (held) Color.argb(170, 199, 125, 255) else Color.argb(110, 60, 40, 90)
                canvas.drawCircle(control.cx + control.kx, control.cy + control.ky, knob, fill)
                stroke.color = if (held) Color.argb(230, 245, 230, 255) else Color.argb(140, 201, 160, 255)
                canvas.drawCircle(control.cx + control.kx, control.cy + control.ky, knob, stroke)
                text.color = if (held) Color.WHITE else Color.argb(150, 225, 210, 245)
                text.textSize = knob * 0.8f
                canvas.drawText(control.label, control.cx + control.kx, control.cy + control.ky + text.textSize * 0.35f, text)
                continue
            }
            fill.color = if (held) Color.argb(150, 199, 125, 255) else Color.argb(70, 20, 12, 30)
            canvas.drawCircle(control.cx, control.cy, control.radius, fill)
            stroke.color = if (held) Color.argb(220, 235, 212, 255) else Color.argb(110, 201, 160, 255)
            canvas.drawCircle(control.cx, control.cy, control.radius, stroke)
            text.color = if (held) Color.WHITE else Color.argb(190, 225, 210, 245)
            text.textSize = control.radius * 0.85f
            canvas.drawText(control.label, control.cx, control.cy + text.textSize * 0.35f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val control = controlAt(event.getX(index), event.getY(index)) ?: return false
                control.pressedBy = event.getPointerId(index)
                if (control.stick >= 0) control.drag(event.getX(index), event.getY(index))
                apply()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    // A stick keeps its finger wherever it goes: the knob follows, clamped.
                    val stick = controls.firstOrNull { it.stick >= 0 && it.pressedBy == pointer }
                    if (stick != null) { stick.drag(x, y); changed = true; continue }
                    // A finger that slides off a button releases it, and one that slides onto
                    // another presses that - which is how a d-pad is used in practice.
                    val over = controlAt(x, y)?.takeIf { it.stick < 0 }
                    for (control in controls) {
                        if (control.stick < 0 && control.pressedBy == pointer && control !== over) {
                            control.pressedBy = -1
                            changed = true
                        }
                    }
                    if (over != null && over.pressedBy == -1) {
                        over.pressedBy = pointer
                        changed = true
                    }
                }
                if (changed) apply()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointer = event.getPointerId(event.actionIndex)
                var changed = false
                for (control in controls) {
                    if (control.pressedBy == pointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (control.pressedBy != -1) changed = true
                        control.pressedBy = -1
                        if (control.stick >= 0 && (control.kx != 0f || control.ky != 0f)) control.dirty = true
                        control.kx = 0f; control.ky = 0f
                    }
                }
                if (changed) apply()
                return true
            }
        }
        return false
    }

    private fun controlAt(x: Float, y: Float): Control? = controls.firstOrNull { it.contains(x, y) }

    private fun apply() {
        pad.applyTouch { state ->
            for (control in controls) {
                val held = control.pressedBy != -1
                when {
                    control.stick >= 0 && !control.dirty -> {}
                    control.stick == 0 -> { state.thumbLX = control.kx / control.radius; state.thumbLY = control.ky / control.radius; control.dirty = false }
                    control.stick == 1 -> { state.thumbRX = control.kx / control.radius; state.thumbRY = control.ky / control.radius; control.dirty = false }
                    control.dpad >= 0 -> state.dpad[control.dpad] = held
                    control.button >= 0 -> state.setPressed(control.button, held)
                }
            }
        }
        invalidate()
    }

    /** Everything up, for when the controls are hidden mid-press. */
    fun releaseAll() {
        if (controls.none { it.pressedBy != -1 }) return
        controls.forEach { it.pressedBy = -1; if (it.stick >= 0 && (it.kx != 0f || it.ky != 0f)) it.dirty = true; it.kx = 0f; it.ky = 0f }
        apply()
    }
}
