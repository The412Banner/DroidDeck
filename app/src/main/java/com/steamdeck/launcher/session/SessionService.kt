package com.steamdeck.launcher.session

import com.steamdeck.launcher.gpu.LinuxVulkanDriver
import com.steamdeck.launcher.gpu.LinuxVulkanDriverManager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.steamdeck.launcher.R
import com.steamdeck.launcher.SessionActivity
import com.steamdeck.launcher.audio.DirectAudioRelayComponent
import com.steamdeck.launcher.audio.PulseAudioComponent
import com.steamdeck.launcher.core.CpuCores
import com.steamdeck.launcher.core.DeviceReport
import com.steamdeck.launcher.core.EnvVars
import com.steamdeck.launcher.core.SessionLogCapture
import com.steamdeck.launcher.core.NetworkReport
import com.steamdeck.launcher.core.EnvironmentComponent
import com.steamdeck.launcher.core.FileUtils
import com.steamdeck.launcher.core.ProcessHelper
import com.steamdeck.launcher.input.FakeInputWriter
import com.steamdeck.launcher.runtime.LinuxNetworkLinkComponent
import com.steamdeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the running session: the proot tree, the audio daemon, the network link and the locks that
 * keep all three alive while the app is not on screen.
 *
 * The session deliberately does **not** belong to the activity. Android demotes a process the
 * moment it loses its last visible activity, and the low-memory killer then reaps the guest — so
 * leaving Big Picture to answer a message would come back to a dead Steam. A foreground service
 * holds the process at perceptible priority, a partial wake lock keeps the CPU from dropping the
 * guest's threads, and a high-performance WiFi lock keeps the radio out of power-save so a
 * backgrounded download does not throttle to nothing. All three are Bannerlator's recipe, where
 * each was added to fix a failure seen on a device.
 *
 * The activity comes and goes on top of this; see [com.steamdeck.launcher.wayland.CompositorHost].
 */
class SessionService : Service() {
    private val components = ArrayList<EnvironmentComponent>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionPid = -1
    /** Counts sessions this service has started; a process exit from an earlier one is ignored. */
    private var sessionGen = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested from the notification")
            stopSession(0)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (SessionState.running) return START_NOT_STICKY
        SessionState.mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_STEAM
        SessionState.program = intent?.getStringExtra(EXTRA_PROGRAM)
        SessionState.programArgs = intent?.getStringArrayExtra(EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
        SessionState.steamUi = intent?.getStringExtra(EXTRA_STEAM_UI)
        SessionState.steamUrl = intent?.getStringExtra(EXTRA_STEAM_URL)
        // Another Steam client on the device signs ours out seconds after every login; the one that
        // does it here runs from boot without being opened. Only the Steam session signs in.
        if (SessionState.mode == MODE_STEAM) RivalClients.stopBeforeSession(this)
        SessionState.running = true
        SessionState.firstFrameSeen = false
        // This session's number, claimed here and not when its process starts: the session it
        // replaces can report its own exit in the gap between the two, and that exit must not
        // be taken as this one's.
        val gen = ++sessionGen
        acquireLocks()
        Thread({
            // A tree the last session left behind (the app was killed or crashed, so its teardown
            // never ran) would hold the rootfs, the GPU and Steam's lock: nothing of ours should
            // be alive between sessions outside this process.
            OrphanReaper.reap("session starting")
            runSession(gen)
        }, "session-start").start()
        // The activity or the notification stops us; the system must not resurrect a session whose
        // guest processes are long gone.
        return START_NOT_STICKY
    }

    /**
     * Finish the session's folder after the session has stopped, then let go of it. The guest
     * script copies Steam's logs too, at a clean exit; this runs whatever killed the session,
     * which is when they matter. The collecting itself is [SessionArtifacts], shared with the
     * crash handler and the next-start sweep so a folder is finished whichever way it ends.
     */
    private fun collectSessionArtifacts(dir: File) {
        try {
            SessionArtifacts.collect(this, dir, "session stopped")
        } finally {
            // Last, so everything above is in the file it is about - and only this session's:
            // a session that replaced this one may already own the capture and the folder.
            SessionLogCapture.stopFor(dir)
            SessionPaths.release(this, dir)
        }
    }

    private fun extraEnv(): List<String> {
        val file = File(Environment.getExternalStorageDirectory(), ENV_SWITCH).takeIf { it.isFile } ?: return emptyList()
        val lines = FileUtils.readString(file)?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') && !it.startsWith("=") }
        if (lines.isNotEmpty()) Log.i(TAG, "extra environment from $ENV_SWITCH: $lines")
        return lines
    }

    private fun tuDebug(linuxDriverId: String): String? {
        val override = File(Environment.getExternalStorageDirectory(), TU_DEBUG_SWITCH)
            .takeIf { it.isFile }?.let { FileUtils.readString(it)?.trim() }
        if (!override.isNullOrEmpty()) return override
        if (SessionPrefs.tuSysmem(this)) return "sysmem"
        if (linuxDriverId.isEmpty()) return null
        val name = LinuxVulkanDriverManager(this).getDriverName(linuxDriverId).lowercase()
        return if (name.contains("710-720") || name.contains("710_720")) "sysmem" else null
    }

    // ── The session ─────────────────────────────────────────────────────────────────────────

    private fun runSession(gen: Int) {
        try {
            LinuxRuntime.writeAccounts(this)
        } catch (e: Exception) {
            Log.e(TAG, "could not write the guest's passwd/group", e)
            stopSession(-1)
            return
        }

        val root = LinuxRuntime.rootDir(this)
        val sessionRoot = LinuxRuntime.sessionRoot(this).apply { mkdirs() }
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        killStragglers()
        SessionFiles.stage(this, root)

        // One folder per session, claimed by whoever started first - the activity starts the
        // compositor before this service runs - so the compositor's log lands in the same place.
        val sessionDir = SessionPaths.beginOrCurrent(this)
        val sessionLog = File(sessionDir, "session.log")
        SessionState.logFile = sessionLog
        // Written first, so a session that dies in its first second still says what it ran on.
        DeviceReport.write(this, File(sessionDir, "device.txt"), SessionState.mode)
        NetworkReport.write(this, File(sessionDir, "network.txt"))
        // Everything the app decides from here on - the driver it chose, the audio line, a rival
        // client stopped, the exit status - reaches logcat and nowhere a user can get at. Mirror it.
        SessionLogCapture.start(File(sessionDir, "app.log"))

        val size = SessionState.outputSize
        val guest = ArrayList<String>()
        guest.add("/usr/bin/env")
        guest.add("-i")
        guest.add("HOME=/root")
        guest.add("USER=root")
        guest.add("PATH=/usr/local/bin:/usr/bin:/bin")
        guest.add("TERM=xterm-256color")
        guest.add("LANG=C.UTF-8")
        // Without this the session is UTC: the client's clock, its logs and every timestamp in a
        // session bundle sit hours off the device's. Bannerlator carries the same line.
        guest.add("TZ=" + java.util.TimeZone.getDefault().id)
        guest.add("XDG_RUNTIME_DIR=" + runtimeDir.path)
        guest.add("XDG_SESSION_TYPE=wayland")
        guest.add("WAYLAND_DISPLAY=wayland-0")
        guest.add("GAMESCOPE_FORCE_GENERAL_QUEUE=1")
        // Steam's CEF needs GL and the rootfs ships no native GL driver: route it through Zink.
        guest.add("MESA_LOADER_DRIVER_OVERRIDE=zink")
        guest.add("GALLIUM_DRIVER=zink")
        guest.add("LIBGL_KOPPER_DRI2=true")
        LinuxRuntime.vulkanIcd(this)?.let { guest.add("VK_ICD_FILENAMES=" + it.path) }
        // An imported glibc Turnip for this mode, when the user chose one: the session script checks
        // the manifest and its library from inside and points the loader at it with VK_DRIVER_FILES,
        // so the runtime's own driver above stays untouched and is what a bad import falls back to.
        val linuxDriverId = SessionPrefs.linuxDriver(this, SessionPrefs.prefMode(SessionState.mode))
        LinuxVulkanDriver.resolveIcdPath(this, linuxDriverId)
            ?.let { guest.add(LinuxVulkanDriver.ENV + "=" + it) }
        // Turnip's own debug switches, for the runtime's driver and everything on it. The file in
        // Downloads holds the value verbatim ("sysmem", "sysmem,deck_emu"); with nothing there, an
        // imported driver from the A710/A720/A722 legs gets "sysmem" on its own, which is what both
        // its authors advise for those GPUs and what nothing else in the list needs.
        tuDebug(linuxDriverId)?.let { guest.add("TU_DEBUG=$it") }
        // Zink renders the client's UI (Chromium -> ANGLE -> Zink -> Turnip). Lazy descriptors is
        // the mode Zink recommends where the driver has no descriptor buffer, and what Ludashi ships
        // by default for its Zink path; a switch here because on one Fold the menus run at 14 fps.
        if (SessionPrefs.zinkLazy(this)) guest.add("ZINK_DESCRIPTORS=lazy")
        // The rest of the client-interface switches (SessionPrefs): GL marshalled off the calling
        // thread, no GL error checks, and the client run as SteamOS runs it (the script reads
        // BL_STEAMDECK; it is the one that builds the command line).
        if (SessionPrefs.glThread(this)) guest.add("mesa_glthread=true")
        if (SessionPrefs.noGlError(this)) guest.add("MESA_NO_ERROR=1")
        if (SessionState.mode == MODE_STEAM) guest.add("BL_STEAMDECK=" + (if (SessionPrefs.steamDeckMode(this)) "1" else "0"))
        // Proton's own gate for its xalia helper (its `proton` script reads this, and sets
        // XALIA_SUPPORTED_ONLY itself otherwise). Off by default: xalia is Valve's, and on a device
        // whose seccomp answers its syscalls normally there is no reason to take it away.
        if (SessionPrefs.noXalia(this)) guest.add("PROTON_USE_XALIA=0")
        // The FEXCore preset for the x86 games the client launches: its FEX_* variables go in
        // here, before the script, so every game process inherits them from the client. The
        // default preset sets nothing, which is what every session ran on before.
        if (SessionState.mode == MODE_STEAM) {
            val preset = SessionPrefs.fexPreset(this)
            val vars = com.steamdeck.launcher.core.FexPreset.env(preset)
            vars.forEach { guest.add(it) }
            if (vars.isNotEmpty()) Log.i(TAG, "fex preset $preset: ${vars.joinToString(" ")}")
        }
        // Anything else, for a device that cannot be reached with a debugger: Downloads/steamdeck-env
        // holds KEY=VALUE lines that go into the session's environment as written, after ours, so a
        // line here wins. Zink and Turnip tunables (ZINK_DESCRIPTORS=lazy, MESA_*), gamescope's,
        // the client's - whatever the experiment needs, without a build per attempt.
        extraEnv().forEach { guest.add(it) }
        // Core masks, Bannerlator's two (cfca3912). The client's is sent whenever the override is
        // on, even naming every core: it exists to undo the pin Steam applies to its own interface
        // renderer, and the scheduler's default is exactly what that pin takes away. A game's is
        // sent only when it is a real restriction - a game has no pin of its own to undo.
        if (SessionState.mode == MODE_STEAM) {
            if (SessionPrefs.clientCpusOverride(this)) {
                guest.add("BL_CLIENT_CPUS=" + CpuCores.listOrAll(SessionPrefs.clientCpus(this)))
            }
            CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(this))
                .takeIf { it.isNotEmpty() }?.let { guest.add("BL_GAME_CPUS=$it") }
        }

        // PulseAudio always: the client is a native Linux program and has no other way to make a
        // sound - its menus, its music and its voice chat all go through here. DirectAudio is not
        // an alternative to it on this path: it replaces the audio driver INSIDE Wine, so it
        // changes what games do and leaves the client alone. The microphone is its own opt-in on
        // top, and the helper only opens an input stream when asked - so a user who wants game
        // sound but no recording gets exactly that, and Android's recording indicator stays off.
        val wantsDirectAudio = SessionState.mode == MODE_STEAM && SessionPrefs.directAudio(this)
        val wantsMic = SessionPrefs.micEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Both paths sit under the app's files directory, which the session binds at its own path,
        // so the same string is valid on both sides and nothing has to be translated.
        val audioDir = File(filesDir, "directaudio").apply { mkdirs() }
        val relaySocket = File(audioDir, "relay.sock")
        val micFifo = if (wantsMic) File(audioDir, "mic.fifo") else null

        val audioLog = File(sessionDir, "audio.log")
        val pulse = PulseAudioComponent(this, micFifo?.absolutePath)
        pulse.setLogFile(audioLog)
        pulse.setContext(this)
        guest.add("PULSE_SERVER=unix:" + pulse.socket().absolutePath)
        components.add(pulse)
        if (wantsDirectAudio || wantsMic) {
            // After the daemon in the list, so it can wait for the pipe the daemon makes.
            val relay = DirectAudioRelayComponent(relaySocket, micFifo)
            relay.setLogFile(audioLog)
            relay.setContext(this)
            components.add(relay)
        }
        if (wantsDirectAudio) {
            // Read by the Proton wrappers, which point Wine at the driver and name it in the
            // prefix. Absent, they take an early return and the game uses Proton's own audio - so
            // this variable is the whole of the selection.
            guest.add("BL_DIRECTAUDIO=/" + SessionFiles.DIRECTAUDIO_DIR)
            guest.add("BANNER_AUDIO_DIRECT_RELAY=" + relaySocket.absolutePath)
        }
        Log.i(TAG, "audio: PulseAudio" + (if (wantsDirectAudio) " + DirectAudio for games" else "")
            + (if (wantsMic) " + microphone" else "") +
            (if (SessionPrefs.micEnabled(this) && !wantsMic) " (microphone wanted but RECORD_AUDIO not granted)" else ""))

        guest.add("BL_WIDTH=" + size.first)
        guest.add("BL_HEIGHT=" + size.second)
        if (SessionState.hdr) {
            // The activity opened the compositor's HDR gate: gamescope offers HDR to its clients
            // and DXVK takes the HDR10 swapchain when a game asks for one.
            guest.add("BL_HDR=1")
            guest.add("DXVK_HDR=1")
            Log.i(TAG, "hdr: gamescope --hdr-enabled, DXVK_HDR=1")
        }
        guest.add("BL_FPS=0")
        guest.add("BL_REFRESH=" + Math.round(SessionState.refreshHz))
        guest.add("BL_LOG=" + sessionLog.path)
        guest.add("BL_DEBUG_DIR=" + sessionDir.path)

        val fakeInputDir = File(sessionRoot, "dev/input").apply { mkdirs() }
        val controllersOn = !File(Environment.getExternalStorageDirectory(), NO_PAD_SWITCH).exists()
        if (controllersOn) {
            FakeInputWriter.prepareRingSlots(fakeInputDir, 4)
            guest.add("FAKE_EVDEV_DIR=" + fakeInputDir.path)
            val rings = FakeInputWriter.getRingEnv(fakeInputDir)
            if (!rings.isNullOrEmpty()) guest.add("FAKE_EVDEV_MEMFD_PATHS=$rings")
            // SDL and Steam key their mapping database on bus+vendor+product: only a known
            // identity gets the standard layout without the user configuring the pad by hand.
            guest.add("FAKE_EVDEV_IDENTITY=xbox360")
            guest.add("FAKE_EVDEV_VIBRATION=1")
            guest.add("FAKE_EVDEV_STEAM_VIRTUAL=1")
            guest.add("SDL_JOYSTICK_DISABLE_UDEV=1")
            guest.add("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1")
            guest.add("SDL_JOYSTICK_HIDAPI=0")
            if (File(Environment.getExternalStorageDirectory(), PAD_LOG_SWITCH).exists()) {
                guest.add("FAKE_EVDEV_LOG=1")
            }
            SessionState.fakeInputDir = fakeInputDir
        }
        // The desktop is wlroots (labwc), and wlroots allocates its buffers through gbm on a real
        // DRM render node. Ours is a KGSL stand-in that gbm cannot use — labwc dies at "unable to
        // create allocator" — so the desktop shell is composited by pixman (software, a shm
        // allocator, no DRM). Accelerated clients on it pay a CPU copy; a 2D emulator does not
        // notice, a demanding one does. steamdeck-wlr-renderer in Downloads (pixman/vulkan/gles2)
        // overrides it, for trying acceleration on a device that has a real node.
        if (SessionState.mode == MODE_DESKTOP) {
            val override = File(Environment.getExternalStorageDirectory(), "Download/steamdeck-wlr-renderer")
                .takeIf { it.isFile }?.let { FileUtils.readString(it)?.trim() }
            guest.add("BL_WLR_RENDERER=" + (override?.takeIf { it.isNotEmpty() } ?: SessionPrefs.desktopRenderer(this)))
        }
        // Where the guest leaves a request for another session (the desktop's Steam launchers).
        guest.add("BL_LAUNCH_DIR=" + sessionRoot.path)
        // The second library's name, for bannerlator-steam-library; the bind itself is made below.
        GameStorage.effective(this)?.let { guest.add("BL_LIBRARY_LABEL=" + it.label.replace('"', ' ')) }
        if (SessionState.mode == MODE_STEAM && SessionState.steamUi == "desktop") guest.add("BL_STEAM_UI=desktop")
        guest.add(LinuxRuntime.SESSION_SCRIPT)
        guest.add(SessionState.mode)
        if (SessionState.mode == MODE_STEAM) SessionState.steamUrl?.takeIf { it.startsWith("steam://") }?.let {
            guest.add(it)
            Log.i(TAG, "steam: handing the client $it")
        }
        // A program under gamescope: the script's run mode takes the path (an AppImage, a script
        // or a binary inside the runtime). This is how an emulator gets the GPU - the desktop's
        // labwc composites in software and offers no dma-buf, so a Vulkan swapchain cannot exist
        // there (RPCS3 died with VK_ERROR_SURFACE_LOST); gamescope's Xwayland is the path the
        // Steam games already render through.
        if (SessionState.mode == MODE_RUN) {
            val program = SessionState.program
            if (program.isNullOrEmpty()) {
                Log.e(TAG, "run mode without a program")
                stopSession(65)
                return
            }
            guest.add(program)
            guest.addAll(SessionState.programArgs)
            Log.i(TAG, "run: $program ${SessionState.programArgs.joinToString(" ")} under gamescope")
        }

        // Android has no /dev/shm; the cache stands in for it and, unlike the real thing, keeps
        // whatever a session leaves behind. The client abandons tens of megabytes of streams a run.
        FileUtils.clear(File(cacheDir, "shm"))

        val binds = ArrayList<String>()
        if (controllersOn) binds.add(fakeInputDir.path + ":/dev/input")
        // Where the device's files appear inside the session. Internal storage is bound at its own
        // path already, and every program's file dialog opens at home and lists "Computer" from
        // /proc/mounts, where a proot bind never shows - so a user saw only the runtime's own
        // tree and could not find the phone at all. The same storage is placed under home as
        // well, and the ROMs folder chosen on the main screen beside it; a bind rather than a
        // link, so a folder on an SD card works the same.
        val home = File(LinuxRuntime.rootDir(this), "root")
        File(home, "Storage").mkdirs()
        binds.add(Environment.getExternalStorageDirectory().path + ":/root/Storage")
        // A second Steam library: the storage chosen in the Steam cog, at the path the runtime's
        // bannerlator-steam-library registers with the client. Nothing bound = the script removes
        // the entry, so the client never offers a place that is not there.
        val library = GameStorage.effective(this)
        if (library != null) {
            val problem = GameStorage.prepare(library.path)
            if (problem == null) {
                File(LinuxRuntime.rootDir(this), "mnt/bannerlator-sd").mkdirs()
                binds.add("${library.path}:/mnt/bannerlator-sd")
                Log.i(TAG, "game storage: ${library.path} -> /mnt/bannerlator-sd (\"${library.label}\")")
            } else {
                Log.w(TAG, "game storage: $problem; internal only this session")
            }
        } else {
            Log.i(TAG, "game storage: internal only")
        }
        val roms = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }?.let { File(it) }
        if (roms != null && roms.isDirectory && roms.canRead()) {
            File(home, "ROMs").mkdirs()
            binds.add(roms.path + ":/root/ROMs")
            Log.i(TAG, "roms: $roms -> /root/ROMs")
        } else if (roms != null) {
            Log.w(TAG, "roms: $roms is not a readable folder; /root/ROMs not offered this session")
        }

        val command = LinuxRuntime.command(
            this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds, guest,
        )

        val hostEnv = EnvVars()
        hostEnv.put("PROOT_LOADER", LinuxRuntime.prootLoader(this).path)
        hostEnv.put("PROOT_TMP_DIR", cacheDir.path)
        // proot links against a libtalloc beside it, and Android's linker does not search an
        // executable's own directory: unnamed, the process dies before it starts and says so only
        // in `logcat -b crash`.
        // proot reads this itself, so it belongs in proot's own environment rather than the guest's.
        if (SessionPrefs.prootNoSeccomp(this)) {
            hostEnv.put("PROOT_NO_SECCOMP", "1")
            Log.i(TAG, "proot: seccomp acceleration off by request")
        }
        val prootLibs = LinuxRuntime.prootLibraryPath(this)
        if (prootLibs.isNotEmpty()) hostEnv.put("LD_LIBRARY_PATH", prootLibs)

        // Whether the client signs in to Valve or starts offline: read once, while it starts, and
        // rewritten by the client when it exits, so it is set again here at every session start.
        if (SessionState.mode == MODE_STEAM) OfflineMode.apply(this, root)

        val networkLink = LinuxNetworkLinkComponent(this, root)
        networkLink.setContext(this)
        networkLink.publish()
        components.add(networkLink)
        components.forEach { it.start() }

        val line = command.joinToString(" ") { it.replace(" ", "\\ ") }
        watchLaunchRequests(sessionRoot)
        // One session replacing another (the desktop's Steam launchers): the old proot is killed
        // by the teardown a second after the new one has started, and its exit used to arrive
        // here as "session ended: 137" and end the NEW session. An exit belongs to the session
        // that started it.
        sessionPid = ProcessHelper.exec(line, hostEnv.toStringArray(), root, { status ->
            if (gen != sessionGen) {
                Log.i(TAG, "an earlier session's process ended ($status); the current one carries on")
                return@exec
            }
            Log.i(TAG, "session ended: $status")
            stopSession(status ?: -1)
        }, null)
        Log.i(TAG, "session pid $sessionPid, log ${sessionLog.path}")
    }

    private fun teardown(prootPid: Int) {
        val tree = descendants(prootPid)
        android.os.Process.sendSignal(prootPid, 15) // SIGTERM
        if (!waitForExit(prootPid, GRACE_MS)) {
            Log.w(TAG, "proot $prootPid did not exit on SIGTERM; killing it")
            android.os.Process.killProcess(prootPid)
        }
        var killed = 0
        for ((pid, started) in tree) {
            val stat = readStat(pid) ?: continue       // already gone
            if (stat.second != started) continue        // same number, different process
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.i(TAG, "swept $killed process(es) proot left behind")
    }

    /** Every process under [root], as (pid, start time), from a single walk of /proc. */
    private fun descendants(root: Int): List<Pair<Int, Long>> {
        val started = HashMap<Int, Long>()
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = readStat(pid) ?: return@forEach
            started[pid] = stat.second
            children.getOrPut(stat.first) { ArrayList() }.add(pid)
        }
        val out = ArrayList<Pair<Int, Long>>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        val me = android.os.Process.myPid()
        while (queue.isNotEmpty()) {
            for (kid in children[queue.removeFirst()] ?: continue) {
                if (kid <= 1 || kid == me || kid == root) continue
                val when_ = started[kid] ?: continue
                out.add(Pair(kid, when_))
                queue.add(kid)
            }
        }
        return out
    }

    /** (parent pid, start time) from /proc/pid/stat, or null when the process is gone. */
    private fun readStat(pid: Int): Pair<Int, Long>? {
        val stat = try { File("/proc/$pid/stat").readText() } catch (e: Exception) { return null }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        if (fields.size < 20) return null
        return try { Pair(fields[1].toInt(), fields[19].toLong()) } catch (e: NumberFormatException) { null }
    }

    private fun waitForExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            try { Thread.sleep(50) } catch (e: InterruptedException) { return false }
        }
        return !File("/proc/$pid").exists()
    }

    /**
     * proot's --kill-on-exit takes its tracees down, but a session that died from the inside
     * (the client asserting, Xwayland going) leaves gamescopereaper and the session script
     * behind, still holding the Wayland socket and the audio server the next session needs. They
     * are our uid, so they are ours to kill.
     */
    private fun killStragglers() {
        val me = android.os.Process.myPid()
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val cmdline = try {
                File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            } catch (e: Exception) {
                continue
            }
            if (STRAGGLERS.none { cmdline.contains(it) }) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.w(TAG, "killed $killed leftover process(es) of a previous session")
    }

    /**
     * The desktop's Steam launchers cannot start the client where they are (no dma-buf on the
     * desktop), so they leave `steam-launch` in the session directory instead: which UI, and a
     * steam:// URL or nothing. This ends the session and hands the activity the one to start.
     */
    private var launchWatcher: android.os.FileObserver? = null

    private fun watchLaunchRequests(dir: File) {
        launchWatcher?.stopWatching()
        @Suppress("DEPRECATION")
        val watcher = object : android.os.FileObserver(dir.path, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != "steam-launch") return
                val file = File(dir, path)
                val text = try { file.readText() } catch (e: Exception) { return }
                file.delete()
                var ui = "desktop"
                var url = ""
                text.lineSequence().forEach { line ->
                    when {
                        line.startsWith("ui=") -> ui = line.removePrefix("ui=").trim()
                        line.startsWith("url=") -> url = line.removePrefix("url=").trim()
                    }
                }
                Log.i(TAG, "launch request from the session: Steam $ui" + (if (url.isNotEmpty()) " $url" else ""))
                val next = Intent(this@SessionService, com.steamdeck.launcher.SessionActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_STEAM)
                    .putExtra(EXTRA_STEAM_UI, ui)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url.isNotEmpty()) next.putExtra(EXTRA_STEAM_URL, url)
                SessionState.relaunch = next
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSession(0) }
            }
        }
        watcher.startWatching()
        launchWatcher = watcher
    }

    private fun stopSession(status: Int) {
        if (!SessionState.running) return
        SessionState.running = false
        launchWatcher?.stopWatching()
        launchWatcher = null
        // proot's --kill-on-exit takes the guest tree down only if proot gets to run it, and
        // SIGKILL never lets it. A SIGKILLed proot left its tracees alive with no tracer: every
        // seccomp-trapped syscall then failed with ENOSYS, they spun on retries at a full core
        // for over an hour, and the reaper could not even open /proc to kill them. So: SIGTERM,
        // a grace for proot's own cleanup, SIGKILL only if it will not go, then a sweep of the
        // tree it had — snapshotted first, each pid checked against its start time so a number
        // reused by a new process is never touched. Same shape as Bannerlator's fix (4509d788).
        if (sessionPid != -1) {
            val prootPid = sessionPid
            sessionPid = -1
            Thread({ teardown(prootPid) }, "session-teardown").start()
        }
        // On its own thread, never here: stopSession runs on the main thread (the notification's
        // Stop action arrives there), and collecting means copying the compositor's log, scrubbing
        // every Steam log line by line - 42 files on one measured run - and waiting for logcat to
        // dump the crash buffer. That was about four and a half seconds of blocked main thread,
        // and Android ANR'd the app for it: the desktop session that would not let go.
        val ended = SessionPaths.take()
        if (ended != null) Thread({ collectSessionArtifacts(ended) }, "session-collect").start()
        components.reversed().forEach {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stopping ${it.javaClass.simpleName}", e)
            }
        }
        components.clear()
        FakeInputWriter.releaseAllRingSlots()
        releaseLocks()
        SessionState.notifyEnded(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Swiped out of recents. The activity is destroyed without any of our teardown running, so the
     * guest would survive as an orphan holding the rootfs and the GPU. Treat the swipe as "quit".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed — ending the session")
        stopSession(0)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSession(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Keeping the process alive ───────────────────────────────────────────────────────────

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteamDeck:session")?.apply {
                setReferenceCounted(false)
                // Capped, so a crash on some path cannot pin the CPU awake for good. A session
                // longer than this re-acquires from the notification tap; nothing else needs it.
                acquire(12L * 60L * 60L * 1000L)
            }
            Log.i(TAG, "wake lock held=${wakeLock?.isHeld}")
        } catch (t: Throwable) {
            Log.w(TAG, "no wake lock (${t.message}) — the session may be killed in the background")
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION") // deprecated from API 29, still honoured; targetSdk is 28
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SteamDeck:session-wifi")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            Log.i(TAG, "wifi lock held=${wifiLock?.isHeld}")
        } catch (t: Throwable) {
            // A partial wake lock keeps the process alive but does not stop WiFi power-save from
            // throttling a backgrounded download to nothing, which is what this lock is for.
            Log.w(TAG, "no wifi lock (${t.message}) — a backgrounded download may stall")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wake lock", t)
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wifi lock", t)
        }
        wifiLock = null
    }

    // ── Notification ────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: it must never make a sound or push a heads-up over a game.
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.session_channel),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.session_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager?.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SessionActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.session_notification))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_session), stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    companion object {
        private const val TAG = "SessionService"
        /** Downloads file whose contents become TU_DEBUG inside the session, e.g. "sysmem". */
        private const val TU_DEBUG_SWITCH = "Download/steamdeck-tu-debug"
        /** Downloads file of KEY=VALUE lines added to the session environment verbatim. */
        private const val ENV_SWITCH = "Download/steamdeck-env"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.steamdeck.launcher.STOP_SESSION"
        /** Command lines that can only belong to a session of ours. */
        private val STRAGGLERS = listOf("bannerlator-session", "gamescope", "Xwayland", "steamrtarm64",
            "steamwebhelper", "linuxfs/opt/android-host/proot", "pulseaudio/libpulseaudio.so")
        /** How long proot gets to run its own cleanup before it is killed outright. */
        private const val GRACE_MS = 1200L
        private const val NO_PAD_SWITCH = "Download/steamdeck-no-pad"
        private const val PAD_LOG_SWITCH = "Download/steamdeck-pad-log"

        const val EXTRA_MODE = "mode"
        const val MODE_STEAM = "steam"
        const val MODE_DESKTOP = "lxqt"
        /** A program inside the runtime, fullscreen under gamescope (EXTRA_PROGRAM = its path). */
        const val MODE_RUN = "run"
        const val EXTRA_PROGRAM = "program"
        /** MODE_RUN: arguments after the program (a game to boot). */
        const val EXTRA_PROGRAM_ARGS = "programArgs"
        /** MODE_STEAM: "desktop" for the client's desktop UI (default Big Picture); a steam:// URL to hand it. */
        const val EXTRA_STEAM_UI = "steamUi"
        const val EXTRA_STEAM_URL = "steamUrl"

        fun start(
            context: Context, mode: String = MODE_STEAM, program: String? = null,
            steamUi: String? = null, steamUrl: String? = null, programArgs: Array<String>? = null,
        ) {
            val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_MODE, mode)
            if (program != null) intent.putExtra(EXTRA_PROGRAM, program)
            if (programArgs != null) intent.putExtra(EXTRA_PROGRAM_ARGS, programArgs)
            if (steamUi != null) intent.putExtra(EXTRA_STEAM_UI, steamUi)
            if (steamUrl != null) intent.putExtra(EXTRA_STEAM_URL, steamUrl)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
