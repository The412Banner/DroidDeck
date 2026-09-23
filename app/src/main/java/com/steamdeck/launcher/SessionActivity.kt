package com.steamdeck.launcher

import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.steamdeck.launcher.core.FileUtils
import com.steamdeck.launcher.gpu.FrameGen
import com.steamdeck.launcher.gpu.LsfgNative
import com.steamdeck.launcher.gpu.TurnipDriver
import com.steamdeck.launcher.input.EvdevKeys
import com.steamdeck.launcher.input.KeyboardHost
import com.steamdeck.launcher.input.OnScreenControls
import com.steamdeck.launcher.input.PadBridge
import com.steamdeck.launcher.input.PointerGestures
import com.steamdeck.launcher.input.TouchpadGestures
import com.steamdeck.launcher.runtime.LinuxRuntime
import com.steamdeck.launcher.session.LoadingState
import com.steamdeck.launcher.session.PerfHints
import com.steamdeck.launcher.session.PerfHud
import com.steamdeck.launcher.session.PerfMode
import com.steamdeck.launcher.session.ProtonExtras
import com.steamdeck.launcher.session.SessionPrefs
import com.steamdeck.launcher.session.SessionPaths
import com.steamdeck.launcher.wayland.HdrSupport
import com.steamdeck.launcher.session.SessionService
import com.steamdeck.launcher.session.SessionState
import com.steamdeck.launcher.ui.CursorOverlay
import com.steamdeck.launcher.ui.DrawerActions
import com.steamdeck.launcher.ui.HudText
import com.steamdeck.launcher.ui.LoadingOverlay
import com.steamdeck.launcher.ui.ProtonDialog
import com.steamdeck.launcher.ui.ProtonRow
import com.steamdeck.launcher.ui.SessionDrawer
import com.steamdeck.launcher.ui.SteamDeckTheme
import com.steamdeck.launcher.wayland.CompositorHost
import com.steamdeck.launcher.wayland.WaylandCompositor
import java.io.File

/**
 * The session's screen: our Wayland compositor presenting onto this activity's Surface, and the
 * input that reaches it. The session itself — gamescope, the Steam client, audio — belongs to
 * [SessionService] and keeps running when this activity does not exist, which is what lets the
 * user leave Big Picture for another app and come back to it still signed in and still
 * downloading.
 *
 * Three layers: the SurfaceView the compositor draws into, the on-screen pad (a canvas View,
 * since it is input rather than a menu), and one Compose layer on top for everything else —
 * the HUD line, the loading overlay, the drawer and its dialogs.
 */
class SessionActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var loading: LoadingState
    private lateinit var hud: PerfHud
    private var padBridge: PadBridge? = null
    private var onScreenControls: OnScreenControls? = null
    private var keyboard: KeyboardHost? = null
    private var watching = true
    private lateinit var gestures: PointerGestures
    private lateinit var touchpad: TouchpadGestures
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var cursorPos by mutableStateOf(androidx.compose.ui.geometry.Offset(-100f, -100f))
    private var cursorVisible by mutableStateOf(false)
    private val cursorHide = Runnable { cursorVisible = false }
    private val uiHandler = Handler(Looper.getMainLooper())

    // Compose reads these; the activity writes them.
    private var drawerOpen by mutableStateOf(false)
    private var showProtons by mutableStateOf(false)
    private var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    private var hudOn by mutableStateOf(true)
    private var frameGenLabel by mutableStateOf("Off")
    private var frameGenEngine by mutableStateOf(FrameGen.ENGINE_OFF)
    private var frameGenMultiplier by mutableStateOf(2)
    private var fexPreset by mutableStateOf("")
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var shapeMode by mutableStateOf(SessionPrefs.SHAPE_AUTO)

    /**
     * Shows the on-screen pad when nothing is plugged in and takes it away the moment something
     * is — a user with a controller in their hands should not be looking at buttons they cannot
     * press, and a user without one must not be left with no way to answer Big Picture.
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateOnScreenControls()
        override fun onInputDeviceRemoved(deviceId: Int) = updateOnScreenControls()
        override fun onInputDeviceChanged(deviceId: Int) = updateOnScreenControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Game-tier power policy for the whole session: a sustained clock floor, the panel's
        // fastest mode, and the OS told it is in gameplay. Logged so a slow device says why.
        Log.i(TAG, "perf: " + PerfMode.apply(this))
        goFullscreen()
        // The device's volume keys change the stream the session plays on (the relay and
        // PulseAudio are media playback); they are never forwarded to the guest.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        if (!LinuxRuntime.isInstalled(this)) {
            Log.e(TAG, "the Linux runtime is not installed")
            finish()
            return
        }

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView)

        val bridge = PadBridge(File(LinuxRuntime.sessionRoot(this), "dev/input"))
        padBridge = bridge
        onScreenControls = OnScreenControls(this, bridge).also { root.addView(it) }
        keyboard = KeyboardHost(this).also { root.addView(it) }
        gestures = PointerGestures(PointerGestures.slop(this), pointerListener)
        touchpad = TouchpadGestures(PointerGestures.slop(this), pointerListener)
        // One arrow, ours: Android draws a system pointer for a mouse over any window, and the
        // session already draws the pointer it is sent.
        val noCursor = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        root.pointerIcon = noCursor
        surfaceView.pointerIcon = noCursor

        loading = LoadingState(this)
        hud = PerfHud(this)
        hud.onPresentingWindowChanged = {
            if (FrameGen.engine(this) != FrameGen.ENGINE_OFF) CompositorHost.rearmFrameGen { applyFrameGen() }
        }
        // A session that has already drawn is past its milestones; do not cover its picture.
        if (SessionState.running && SessionState.firstFrameSeen) {
            loading.visible = false
            hud.start()
        }
        readPrefs()

        // One Compose layer for every menu and overlay. Touches nothing in it consumes fall
        // through to the pad and the game underneath.
        root.addView(ComposeView(this).apply {
            setContent {
                SteamDeckTheme {
                    CursorOverlay(cursorPos, cursorVisible, resources.displayMetrics.density)
                    if (hud.text.isNotEmpty()) HudText(hud.text)
                    if (loading.visible) LoadingOverlay(loading.step, loading.percent, loading.elapsed, loading.hint, loading.ended)
                    SessionDrawer(drawerOpen, DrawerActions(
                        steam = SessionState.mode == SessionService.MODE_STEAM,
                        hudOn = hudOn,
                        frameGenEngine = frameGenEngine, frameGenMultiplier = frameGenMultiplier,
                        lsfgReady = LsfgNative.isInstalled(this@SessionActivity),
                        oscMode = oscMode, touchMode = touchMode,
                        touchAuto = if (usingTouchpad()) "touchpad" else "direct",
                        shapeMode = shapeMode, fexPreset = fexPreset,
                        onHud = { on -> SessionPrefs.setHudEnabled(this@SessionActivity, on); hudOn = on; hud.refresh() },
                        onFrameGenPick = { engine, multiplier ->
                            FrameGen.set(this@SessionActivity, engine, multiplier)
                            readPrefs()
                            applyFrameGen()
                        },
                        onKeyboard = { drawerOpen = false; keyboard?.toggle() },
                        onSteamMenu = if (SessionState.mode == SessionService.MODE_STEAM) ({
                            // The Guide button, the way the on-screen ◉ sends it: a device with no
                            // Xbox button, or a pad the client hides the controls for, has no other
                            // way to open the client's menu in a game.
                            drawerOpen = false
                            padBridge?.applyTouch { st -> st.setPressed(com.steamdeck.launcher.input.GamepadState.IDX_BUTTON_MODE.toInt(), true) }
                            Handler(Looper.getMainLooper()).postDelayed({
                                padBridge?.applyTouch { st -> st.setPressed(com.steamdeck.launcher.input.GamepadState.IDX_BUTTON_MODE.toInt(), false) }
                            }, 90)
                        }) else null,
                        onProtons = { refreshProtons(); showProtons = true },
                        onOsc = { v -> SessionPrefs.setOscMode(this@SessionActivity, v); readPrefs(); updateOnScreenControls() },
                        onTouch = { v -> SessionPrefs.setTouchMode(this@SessionActivity, v); readPrefs() },
                        onShape = { v -> SessionPrefs.setShapeMode(this@SessionActivity, v); readPrefs() },
                        onFexPreset = { v -> SessionPrefs.setFexPreset(this@SessionActivity, v); readPrefs() },
                        onBackground = { drawerOpen = false; moveTaskToBack(true) },
                        onStop = { drawerOpen = false; SessionService.stop(this@SessionActivity); finish() },
                        onClose = { drawerOpen = false },
                    ))
                    if (showProtons) ProtonDialog(
                        rows = protonRows,
                        onInstall = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.queue(this@SessionActivity, it) }; refreshProtons() },
                        onCancel = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.unqueue(this@SessionActivity, it) }; refreshProtons() },
                        onRemove = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.remove(this@SessionActivity, it) }; refreshProtons() },
                        onDismiss = { showProtons = false },
                    )
                }
            }
        })
        setContentView(root)

        updateOnScreenControls()
        WaylandCompositor.setFirstFrameListener {
            SessionState.firstFrameSeen = true
            runOnUiThread {
                loading.visible = false
                hud.start()
            }
        }
        SessionState.endListener = endListener
        // Back opens the drawer (and closes it again). Leaving the session running in the
        // background and ending it are both actions in there, so neither can happen by accident
        // from a button a game might also be reading.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showProtons -> showProtons = false
                    else -> drawerOpen = !drawerOpen
                }
            }
        })
        watchSession()
    }

    private fun refreshProtons() {
        protonRows = ProtonExtras.tools.map {
            ProtonRow(it.id, it.name, ProtonExtras.installed(this, it), ProtonExtras.queued(this, it))
        }
    }

    private fun readPrefs() {
        hudOn = SessionPrefs.hudEnabled(this)
        touchMode = SessionPrefs.touchMode(this)
        frameGenLabel = FrameGen.label(this)
        frameGenEngine = FrameGen.engine(this)
        frameGenMultiplier = FrameGen.multiplier(this)
        fexPreset = SessionPrefs.fexPreset(this)
        oscMode = SessionPrefs.oscMode(this)
        shapeMode = SessionPrefs.shapeMode(this)
    }

    // ── Compositor ──────────────────────────────────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        // The compositor hands this keymap to wl_keyboard clients, which is how the guest reads
        // the evdev codes we inject.
        FileUtils.copyAsset(this, "wayland/keymap.xkb", File(runtimeDir, "keymap.xkb"))

        // Turnip, not the system Adreno driver: importing the dma-bufs gamescope commits needs
        // VK_EXT_image_drm_format_modifier, which the system driver does not implement.
        val turnip = TurnipDriver(this)
        val driverId = if (CompositorHost.isStarted) null else turnip.install()

        // The output size belongs to the session, not to the Surface: gamescope's display is
        // sized once when the session starts and cannot change. A foldable recreates the Surface
        // on the other panel, and recomputing the size there told the compositor a 21:9 buffer
        // was 16:9 — the picture came back squashed sideways. While a session runs, keep its size.
        val size = if (SessionState.running) SessionState.outputSize else outputSize()
        SessionState.outputSize = size
        onScreenControls?.setPicture(drawnRect())
        if (!SessionState.running) SessionState.refreshHz = refreshHz()
        // Letterbox, never stretch or crop: the output can be a different shape from the panel,
        // and a game's picture must keep its proportions with bars, not lose its edges.
        WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        // The session's folder, claimed here because the compositor starts before the service and
        // opens its log once. The compositor reads the path from its environment; setting it after
        // it has started changes nothing, which is why the service copies the file in at teardown.
        if (!SessionState.running) {
            val waylandLog = File(SessionPaths.beginOrCurrent(this), "wayland.log")
            WaylandCompositor.setSessionLogFile(waylandLog)
            try {
                android.system.Os.setenv("BL_WAYLAND_LOG", waylandLog.path, true)
            } catch (e: Exception) {
                Log.w(TAG, "could not point the compositor's log at $waylandLog", e)
            }
        }
        // HDR10: the gate is decided once, when the compositor starts (it lives for the whole app
        // process), from the panel's own word and the mode's setting. Zero-copy presentation is
        // what puts an HDR frame on a display layer tagged BT2020_PQ, so it is turned on with it.
        if (!CompositorHost.isStarted) {
            val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
            val probe = HdrSupport.probe(this)
            WaylandCompositor.nativeSetHdrDisplay(
                probe.displayId, probe.name, probe.formats, probe.hdr10,
                probe.maxLuminance, probe.maxAverageLuminance, probe.minLuminance,
                probe.ratioAvailable, probe.ratio, Build.VERSION.SDK_INT,
            )
            val wanted = SessionPrefs.hdr(this, mode)
            val on = wanted && probe.reason == null
            SessionState.hdr = on
            if (on) {
                try { android.system.Os.setenv("BANNER_WAYLAND_HDR", "1", true) } catch (e: Exception) { Log.w(TAG, "BANNER_WAYLAND_HDR", e) }
                WaylandCompositor.nativeSetZeroCopy(true)
            }
            WaylandCompositor.nativeSetHdrRequest(
                if (on) WaylandCompositor.HDR_MODE_ON else WaylandCompositor.HDR_MODE_OFF,
                "$mode session settings" + (if (wanted && !on) " (refused: ${probe.reason})" else ""),
                on, on,
            )
            Log.i(TAG, "hdr: " + (if (on) "on" else if (wanted) "wanted but ${probe.reason}" else "off") + " · display ${probe.formats.ifEmpty { "SDR" }}")
        }
        CompositorHost.startOrAttach(
            holder.surface, runtimeDir.path,
            driverId?.let { turnip.driverPath(it) }, driverId?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir, size.first, size.second, refreshHz(),
        )
        // ADPF: the compositor thread's frame intervals go to the power HAL against the panel's
        // period, so a long frame raises CPU clocks now rather than after the load averages up.
        PerfHints.arm(this, refreshHz())
        // The service owns everything below the compositor. It is started whenever no session is
        // running — NOT only when the compositor was just started: the compositor lives for the
        // whole process, so the second Play after a session ended used to re-attach the Surface,
        // start nothing, and leave the loading panel counting up over a dead session.
        if (!SessionState.running) {
            CompositorHost.newSession()
            SessionService.start(
                this, intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM,
                intent.getStringExtra(SessionService.EXTRA_PROGRAM),
                intent.getStringExtra(SessionService.EXTRA_STEAM_UI),
                intent.getStringExtra(SessionService.EXTRA_STEAM_URL),
                intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS),
            )
        }
        applyFrameGen()
    }

    private var surfaceW = 0
    private var surfaceH = 0

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val resized = surfaceW != 0 && (width != surfaceW || height != surfaceH)
        surfaceW = width
        surfaceH = height
        // The on-screen controls follow the picture: into the bars beside or under it when there are any.
        onScreenControls?.setPicture(drawnRect())
        if (resized) {
            Log.i(TAG, "surface resized to ${width}x$height — rebinding the compositor")
            CompositorHost.resize(holder.surface) { applyFrameGen() }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceW = 0
        surfaceH = 0
        CompositorHost.detach(holder.surface)
    }

    /**
     * The size the session renders at. gamescope is told this and scales its output onto whatever
     * the panel is, so a 1440p phone can run the client at 1080p without the client knowing.
     */
    private fun outputSize(): Pair<Int, Int> {
        // The panel, not the window: resources.displayMetrics is what is left after the system
        // bars and the cutout are taken out.
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val panelW = maxOf(bounds.width(), bounds.height()).toFloat()
        val panelH = minOf(bounds.width(), bounds.height()).toFloat()
        // Never narrower than 16:9. A foldable's inner panel is nearly square, and a game handed a
        // square display draws for the frame it was made for and cuts the sides off itself.
        // Wider than 16:9 is fine — games and the client cope with a phone's 20:9 — so the
        // panel's aspect is kept above that, unless the user pinned 16:9 for a foldable, and the
        // compositor letterboxes onto a squarer panel.
        val aspect = if (SessionPrefs.shapeMode(this) == SessionPrefs.SHAPE_WIDE) 16f / 9f
                     else maxOf(panelW / panelH, 16f / 9f)
        // 720 tall at most by default, client and desktop alike: the client's CEF is the heaviest
        // thing in the session, and pixels above that cost frames for nothing anyone can see on a
        // handheld panel. The mode's settings (the cog beside Play / Desktop) can change
        // the cap or lift it to the panel.
        val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
        val cap = SessionPrefs.resolutionCap(this, mode)
        val height = (if (cap <= 0) panelH else minOf(panelH, cap.toFloat())).toInt()
        val width = (height * aspect).toInt()
        // Odd sizes upset the scaler; both dimensions even is what every mode here would be.
        return Pair(width and 1.inv(), height and 1.inv())
    }

    private fun refreshHz(): Float {
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val hz = display?.refreshRate ?: 60f
        return if (hz > 1f) hz else 60f
    }

    /**
     * The saved frame-generation setting, pushed to the compositor. Off the main thread: LSFG's
     * first use translates the shader chain out of Lossless.dll, which takes seconds.
     */
    private fun applyFrameGen() {
        val hz = refreshHz()
        Thread({
            val problem = FrameGen.apply(this, hz)
            if (problem != null) runOnUiThread { Toast.makeText(this, problem, Toast.LENGTH_LONG).show() }
        }, "frame-gen").start()
    }

    // ── Session state ───────────────────────────────────────────────────────────────────────

    /** Keeps the loading overlay current from the session log, and its clock moving. */
    private fun watchSession() {
        val handler = Handler(Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!watching) return
                if (loading.visible && !loading.ended) {
                    loading.update(this@SessionActivity, SessionState.logFile)
                    if (ticks++ % 2 == 0) loading.tick()
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
    }

    private fun onSessionEnded(status: Int) {
        if (status == 0) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // A session the guest asked for (the desktop's Steam launchers) takes this one's
                // place: the compositor stays, a new activity attaches and starts the service.
                SessionState.relaunch?.let { next ->
                    SessionState.relaunch = null
                    startActivity(next)
                }
                finish()
            }
            return
        }
        // What the log says about why, read off the main thread (notifyEnded arrives on it).
        Thread({
            val hint = sessionEndHint(SessionState.logFile)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showEnded(status, hint)
            }
        }, "session-end-hint").start()
    }

    private fun showEnded(status: Int, hint: String?) {
        run {
            if (status != 0) {
                loading.showEnded(
                    "The session ended ($status)\n${SessionState.logFile?.path ?: "-"}" +
                        (if (hint != null) "\n\n$hint" else "")
                )
                // A moment on screen, so a failure is readable rather than a flash of black;
                // longer when there is advice to read.
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (hint != null) 9000 else 4000)
            } else {
                finish()
            }
        }
    }

    /**
     * One line of advice for a failure the log identifies, or null. The one this recognises: the
     * ENOSYS storm - dozens of `socket(): Function not implemented` / `shared memfd open() failed`
     * lines - which is proot's seccomp acceleration failing an x86 helper (Steam's xalia under
     * FEX) on some devices (a Fold 5, twice). The switch that answers it is in Performance, and a
     * user who never opens the log would not know.
     */
    private fun sessionEndHint(log: File?): String? {
        if (log == null || !log.isFile) return null
        return try {
            var enosys = 0
            // The tail is where a dying session says why; 512 KB covers the storm without reading a 1 GB log.
            val size = log.length()
            java.io.RandomAccessFile(log, "r").use { f ->
                val start = maxOf(0L, size - 512L * 1024)
                f.seek(start)
                val bytes = ByteArray((size - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.ISO_8859_1).lineSequence().forEach { line ->
                    if (line.contains("Function not implemented")) enosys++
                }
            }
            if (enosys >= 8) {
                "The log shows $enosys \"Function not implemented\" errors: proot's seccomp acceleration is failing a helper on this device. " +
                    "Try Performance \u2192 \"Run proot without seccomp\" (or \"Skip Steam's xalia helper\") and start again."
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The device's own volume keys belong to Android: forwarded to the guest as keys they
        // changed nothing anyone could hear (a Pocket FIT report, on the desktop).
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.dispatchKeyEvent(event)
        }
        if (padBridge?.onKeyEvent(event) == true) return true
        // A hardware keyboard, forwarded to the compositor's wl_keyboard. Back is left to the
        // activity, which opens the drawer.
        val fromPad = event.device != null && PadBridge.isFromController(event.device)
        if (CompositorHost.isStarted && event.keyCode != KeyEvent.KEYCODE_BACK && !fromPad) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (down || event.action == KeyEvent.ACTION_UP) {
                var evdev = EvdevKeys.fromKeyCode(event.keyCode)
                // What the key stands for, and the key it would sit on without Shift — non-zero
                // only for a character Shift puts there: every capital and the symbol row.
                val ch = event.unicodeChar
                val plain = if (ch > 0) EvdevKeys.unshiftedChar(ch) else 0
                // A soft keyboard's symbol keys are in neither the table nor a scan code, so they
                // reached the session as nothing at all — a sign-in took an address without its @.
                // Work back from the character instead: which key carries it.
                if (evdev <= 0 && ch > 0) {
                    val code = EvdevKeys.fromKeyCode(EvdevKeys.keycodeForChar(if (plain != 0) plain else ch))
                    if (code > 0) evdev = code
                }
                // The modifier has to be made here. A soft keyboard reports Shift in the event's
                // meta state and sends no Shift key of its own, so a capital arrives as a key this
                // side already knows and came out lowercase. A hardware keyboard sends its own
                // Shift, and a second one would release the modifier while the key is still held.
                val shiftEvdev = if (evdev > 0 && plain != 0 && event.deviceId <= 0) 42 else 0
                if (evdev <= 0 && event.scanCode > 0) evdev = event.scanCode
                if (evdev > 0) {
                    if (down) {
                        if (shiftEvdev != 0) WaylandCompositor.nativeSendKey(shiftEvdev, 1)
                        WaylandCompositor.nativeSendKey(evdev, 1)
                    } else {
                        WaylandCompositor.nativeSendKey(evdev, 0)
                        if (shiftEvdev != 0) WaylandCompositor.nativeSendKey(shiftEvdev, 0)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (padBridge?.onMotionEvent(event) == true) return true
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && !drawerOpen && onMouse(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * The pointer. Touch goes through [PointerGestures] (tap, hold, drag, two-finger scroll); a
     * mouse arrives with real buttons and a wheel and is forwarded as it is. Every position is
     * mapped through the letterboxed rectangle into the compositor's fixed 1920x1080 pointer
     * space, and the arrow is drawn where the app last sent the pointer.
     */
    private val pointerListener = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) = movePointer(x, y)
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            movePointer(x, y)
            WaylandCompositor.nativeSendSceneInput(3, button, if (pressed) 1 else 0)
        }
        override fun onWheel(steps: Int) { WaylandCompositor.nativeSendSceneInput(4, steps, 0) }
        override fun onLongPress() {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(15)
        }
    }

    private fun movePointer(x: Float, y: Float) {
        val width = surfaceView.width.takeIf { it > 0 } ?: return
        val height = surfaceView.height.takeIf { it > 0 } ?: return
        val out = SessionState.outputSize
        val scale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        val drawnW = out.first * scale
        val drawnH = out.second * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        val px = ((x - left) / drawnW * 1920f).toInt().coerceIn(0, 1919)
        val py = ((y - top) / drawnH * 1080f).toInt().coerceIn(0, 1079)
        WaylandCompositor.nativeSendPointer(1, px, py)
        showCursor(x.coerceIn(left, left + drawnW), y.coerceIn(top, top + drawnH))
    }

    /** The arrow stays on a desktop; in a Steam session it shows for a moment after each move. */
    private fun showCursor(x: Float, y: Float) {
        cursorPos = androidx.compose.ui.geometry.Offset(x, y)
        cursorVisible = true
        uiHandler.removeCallbacks(cursorHide)
        if (SessionState.mode == SessionService.MODE_STEAM && SessionState.steamUi != "desktop") uiHandler.postDelayed(cursorHide, 2500)
    }

    /** Touchpad on the desktop, direct in Steam, unless the drawer says otherwise. */
    private fun usingTouchpad(): Boolean = when (SessionPrefs.touchMode(this)) {
        SessionPrefs.TOUCH_PAD -> true
        SessionPrefs.TOUCH_DIRECT -> false
        else -> SessionState.mode != SessionService.MODE_STEAM || SessionState.steamUi == "desktop"
    }

    /** The picture's rectangle inside the view: where the pointer may go. */
    private fun drawnRect(): android.graphics.RectF? {
        val width = surfaceView.width.takeIf { it > 0 } ?: return null
        val height = surfaceView.height.takeIf { it > 0 } ?: return null
        val out = SessionState.outputSize
        val scale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        val drawnW = out.first * scale
        val drawnH = out.second * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        return android.graphics.RectF(left, top, left + drawnW, top + drawnH)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return onMouse(event)
        if (usingTouchpad()) {
            val rect = drawnRect() ?: return false
            if (touchpad.bounds != rect) {
                val fresh = touchpad.bounds.width() <= 1f
                touchpad.bounds = rect
                if (fresh) touchpad.place(rect.centerX(), rect.centerY())
            }
            return touchpad.onTouch(event)
        }
        return gestures.onTouch(event)
    }

    /** A mouse: hover moves, buttons press, the wheel scrolls. Android sends buttons as touch
     *  actions and hover as generic motion, so both paths land here. */
    private fun onMouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                movePointer(event.x, event.y)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                movePointer(event.x, event.y)
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
                    else -> PointerGestures.BTN_LEFT
                }
                WaylandCompositor.nativeSendSceneInput(3, button, if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) 1 else 0)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) WaylandCompositor.nativeSendSceneInput(4, -Math.round(v), 0)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {} // the BUTTON_* events carry these
            else -> return false
        }
        return true
    }

    /**
     * The drawer's mode ("always" / "never") decides outright; on "auto" the controls follow
     * what is attached. `steamdeck-osc` in Downloads still overrides, for a device we cannot reach.
     */
    private fun updateOnScreenControls() {
        val forced = File(Environment.getExternalStorageDirectory(), "Download/steamdeck-osc")
            .takeIf { it.isFile }
            ?.let { FileUtils.readString(it)?.trim()?.lowercase() }
            ?: SessionPrefs.oscMode(this)
        val show = when (forced) {
            SessionPrefs.OSC_ALWAYS -> true
            SessionPrefs.OSC_NEVER -> false
            else -> !PadBridge.anyControllerConnected()
        }
        val controls = onScreenControls ?: return
        if (show == (controls.visibility == View.VISIBLE)) return
        if (!show) controls.releaseAll()
        controls.visibility = if (show) View.VISIBLE else View.GONE
        Log.i(TAG, "on-screen controls " + (if (show) "shown" else "hidden"))
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        (getSystemService(INPUT_SERVICE) as? InputManager)
            ?.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        updateOnScreenControls()
        readPrefs()
        if (CompositorHost.isStarted) applyFrameGen()
    }

    override fun onPause() {
        (getSystemService(INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(deviceListener)
        // A button held when the app goes away would stay held in the ring for the whole session.
        onScreenControls?.releaseAll()
        keyboard?.takeIf { it.shown }?.hide()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Ours, so a finishing activity never unhooks the one that replaced it. */
    private val endListener: (Int) -> Unit = { status -> onSessionEnded(status) }

    override fun onDestroy() {
        // Deliberately does NOT end the session: this activity can be destroyed while the user is
        // in another app, and the whole point of the service is that Steam survives that.
        watching = false
        if (::hud.isInitialized) hud.stop()
        padBridge?.stop()
        if (SessionState.endListener === endListener) SessionState.endListener = null
        WaylandCompositor.setFirstFrameListener(null)
        super.onDestroy()
    }

    /** Leaving the session sinks its surface back down onto the front end. */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.session_hold, R.anim.session_sink)
    }

    companion object {
        private const val TAG = "SessionActivity"
        /** Compositor scale modes (Container.FULLSCREEN_* values): 1 = fit with bars, centred. */
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
    }
}
