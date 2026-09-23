package com.steamdeck.launcher.session

import android.content.Context

/** The in-session switches: the HUD and how the on-screen controls decide to appear. */
object SessionPrefs {
    const val OSC_AUTO = "auto"
    const val OSC_ALWAYS = "always"
    const val OSC_NEVER = "never"

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun hudEnabled(context: Context): Boolean = prefs(context).getBoolean("hud", true)

    fun setHudEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("hud", on).apply()
    }

    const val TOUCH_AUTO = "auto"
    const val TOUCH_PAD = "touchpad"
    const val TOUCH_DIRECT = "direct"

    /** How touch drives the pointer: a touchpad (drag moves it from where it is) or direct
     *  (it jumps under the finger). Auto = touchpad on the desktop, direct in Steam. */
    fun touchMode(context: Context): String = prefs(context).getString("touch", TOUCH_AUTO) ?: TOUCH_AUTO

    fun setTouchMode(context: Context, mode: String) {
        prefs(context).edit().putString("touch", mode).apply()
    }

    const val SHAPE_AUTO = "auto"
    const val SHAPE_WIDE = "16:9"

    /**
     * The shape of the display the session presents: the panel's own (never narrower than 16:9)
     * or a fixed 16:9. A foldable defaults to 16:9, which sits with modest bars on either of its
     * panels; the panel's own shape would fit one and leave a strip on the other, and gamescope's
     * display cannot change size once the session is up.
     */
    fun shapeMode(context: Context): String =
        prefs(context).getString("shape", null)
            ?: if (context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) SHAPE_WIDE else SHAPE_AUTO

    fun setShapeMode(context: Context, mode: String) {
        prefs(context).edit().putString("shape", mode).apply()
    }

    fun oscMode(context: Context): String = prefs(context).getString("osc", OSC_AUTO) ?: OSC_AUTO

    fun setOscMode(context: Context, mode: String) {
        prefs(context).edit().putString("osc", mode).apply()
    }

    /**
     * The imported glibc Turnip a mode draws with inside the runtime, keyed by
     * SessionService.MODE_STEAM / MODE_DESKTOP so Steam and the desktop can differ; "" = the
     * driver built into the runtime. Resolved by LinuxVulkanDriver at session start.
     */
    fun linuxDriver(context: Context, mode: String): String =
        prefs(context).getString("linuxDriver.$mode", "") ?: ""

    fun setLinuxDriver(context: Context, mode: String, id: String) {
        prefs(context).edit().putString("linuxDriver.$mode", id).apply()
    }

    /**
     * DirectAudio for the games the client launches: Wine's audio driver inside them replaced by
     * ours, which talks to a helper on this side. The client itself keeps PulseAudio either way.
     */
    fun directAudio(context: Context): Boolean = prefs(context).getBoolean("directAudio", false)

    fun setDirectAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("directAudio", on).apply()
    }

    /** The microphone, its own opt-in: the helper opens an input stream only when asked. */
    fun micEnabled(context: Context): Boolean = prefs(context).getBoolean("mic", false)

    fun setMicEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("mic", on).apply()
    }

    /**
     * Whether the client's own core pick is overridden. When on, BL_CLIENT_CPUS is sent even when
     * it names every core - unlike a game mask, the point here is to undo a pin Steam applies to
     * itself, and the scheduler's default is exactly what Steam's choice takes away.
     */
    fun clientCpusOverride(context: Context): Boolean = prefs(context).getBoolean("clientCpusOverride", false)

    fun setClientCpusOverride(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clientCpusOverride", on).apply()
    }

    /** Comma-separated core list for the client (taskset -c syntax); "" = every core. */
    fun clientCpus(context: Context): String = prefs(context).getString("clientCpus", "") ?: ""

    fun setClientCpus(context: Context, list: String) {
        prefs(context).edit().putString("clientCpus", list).apply()
    }

    /** Comma-separated core list for games (taskset -c syntax); "" = every core = nothing sent. */
    fun gameCpus(context: Context): String = prefs(context).getString("gameCpus", "") ?: ""

    fun setGameCpus(context: Context, list: String) {
        prefs(context).edit().putString("gameCpus", list).apply()
    }

    /**
     * Whether Steam's xalia helper is kept out of the session (PROTON_USE_XALIA=0).
     *
     * xalia is an x86 Windows program Proton launches to give Windows programs gamepad navigation.
     * Under FEX it cannot load the session's aarch64 preload shim, so its socket() and memfd calls
     * reach the vendor's seccomp filter raw; where that answers ENOSYS - a Galaxy Fold, measured -
     * it storms, and the session dies seconds after Big Picture appears.
     */
    fun noXalia(context: Context): Boolean = prefs(context).getBoolean("noXalia", false)

    fun setNoXalia(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("noXalia", on).apply()
    }

    /**
     * Whether proot runs without its seccomp acceleration (PROOT_NO_SECCOMP=1).
     *
     * proot normally installs a seccomp filter so only the syscalls it must rewrite stop in the
     * tracer; everything else runs untraced, which is most of proot's speed. Where a vendor kernel
     * handles that filter badly the wrong calls are trapped or refused - ENOSYS from calls that
     * plainly exist is the signature - and the fallback is to trace everything instead: slower,
     * but correct. Max's advice for devices whose kernels "don't work well with it".
     */
    fun prootNoSeccomp(context: Context): Boolean = prefs(context).getBoolean("prootNoSeccomp", false)

    fun setProotNoSeccomp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootNoSeccomp", on).apply()
    }

    /** Turnip's sysmem rendering (TU_DEBUG=sysmem) for the runtime's driver: bypasses GMEM tiling. */
    fun tuSysmem(context: Context): Boolean = prefs(context).getBoolean("tuSysmem", false)

    fun setTuSysmem(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tuSysmem", on).apply()
    }

    /** Zink's lazy descriptor mode (ZINK_DESCRIPTORS=lazy) for the client's GL-on-Vulkan UI. */
    fun zinkLazy(context: Context): Boolean = prefs(context).getBoolean("zinkLazy", false)

    fun setZinkLazy(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zinkLazy", on).apply()
    }

    /** The Android driver the compositor loads: "" = pick by GPU, else a bundled or imported id. */
    @JvmStatic
    fun androidDriver(context: Context): String = prefs(context).getString("androidDriver", "") ?: ""

    @JvmStatic
    fun setAndroidDriver(context: Context, id: String) {
        prefs(context).edit().putString("androidDriver", id).apply()
    }

    // ── Storage the session can see ─────────────────────────────────────────────────────────────

    /**
     * The folder on this device that every session shows at `/root/ROMs`, for the emulators on the
     * desktop. "" = none chosen. All of internal storage is at `/root/Storage` regardless.
     */
    fun romsDir(context: Context): String =
        prefs(context).getString("romsDir", "") ?: ""

    fun setRomsDir(context: Context, path: String) {
        prefs(context).edit().putString("romsDir", path).apply()
    }

    /**
     * Whether a session writes its folder under Download/SteamDeck. Off, the same logs are kept in
     * the app's cache for the session's lifetime (the scripts need somewhere to write) and thrown
     * away at the end, so nothing accumulates in Downloads.
     */
    fun logsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("logs", true)

    fun setLogsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("logs", on).apply()
    }

    // ── Per-mode display ────────────────────────────────────────────────────────────────────

    /**
     * The tallest the session's display may be, in pixels, for MODE_STEAM / MODE_DESKTOP:
     * 0 = the panel's own height, otherwise a cap. 1080 is the default the app always had - the
     * client's CEF is the heaviest thing in a session and above 1080p it costs frames for nothing
     * a handheld panel can show. Read once, when the session's display is sized.
     */
    fun resolutionCap(context: Context, mode: String): Int = prefs(context).getInt("resolutionCap.$mode", 900)

    fun setResolutionCap(context: Context, mode: String, cap: Int) {
        prefs(context).edit().putInt("resolutionCap.$mode", cap).apply()
    }

    /**
     * What the desktop shell composites with: pixman (software, the default - the Adreno stand-in
     * is not a DRM render node, so labwc's gbm allocator cannot use it), or gles2 / vulkan for a
     * device that has a real node. `Download/steamdeck-wlr-renderer` still overrides it.
     */
    fun desktopRenderer(context: Context): String = prefs(context).getString("desktopRenderer", "pixman") ?: "pixman"

    fun setDesktopRenderer(context: Context, renderer: String) {
        prefs(context).edit().putString("desktopRenderer", renderer).apply()
    }

    /**
     * HDR10 output for MODE_STEAM / MODE_DESKTOP. Off by default. Honoured only when the panel
     * lists HDR10 (HdrSupport), and decided when the compositor starts, which is once per app
     * process: a change applies after the app is fully closed and opened again.
     */
    fun hdr(context: Context, mode: String): Boolean = prefs(context).getBoolean("hdr.$mode", false)

    fun setHdr(context: Context, mode: String, on: Boolean) {
        prefs(context).edit().putBoolean("hdr.$mode", on).apply()
    }

    /**
     * The mode whose per-mode settings apply: a program run under gamescope (MODE_RUN) is a
     * fullscreen session like Steam's, so it takes Steam's display, driver and HDR choices.
     */
    fun prefMode(mode: String): String = if (mode == SessionService.MODE_RUN) SessionService.MODE_STEAM else mode

    // ── Game storage ────────────────────────────────────────────────────────────────────────

    /**
     * A second Steam library on this device: the folder bound at /mnt/bannerlator-sd and
     * registered with the client, which then asks where to install every game and shows both
     * on its Storage page. "" = automatic: the SD card when one is in the phone (the default,
     * so the choice is made inside the client like anywhere else); GAME_STORAGE_OFF = internal
     * only; otherwise the folder chosen.
     */
    fun gameStorage(context: Context): String = prefs(context).getString("gameStorage", "") ?: ""

    const val GAME_STORAGE_OFF = "off"

    fun gameStorageLabel(context: Context): String = prefs(context).getString("gameStorageLabel", "SD Card") ?: "SD Card"

    fun setGameStorage(context: Context, path: String, label: String) {
        prefs(context).edit().putString("gameStorage", path).putString("gameStorageLabel", label).apply()
    }
}
