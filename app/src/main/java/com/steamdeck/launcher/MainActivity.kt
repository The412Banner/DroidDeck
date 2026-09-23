package com.steamdeck.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.steamdeck.launcher.core.FileUtils
import com.steamdeck.launcher.gpu.FrameGen
import com.steamdeck.launcher.gpu.LinuxVulkanDriver
import com.steamdeck.launcher.gpu.LinuxVulkanDriverManager
import com.steamdeck.launcher.gpu.TurnipDriver
import com.steamdeck.launcher.gpu.LsfgNative
import com.steamdeck.launcher.runtime.LinuxRuntime
import com.steamdeck.launcher.runtime.DesktopCatalog
import com.steamdeck.launcher.runtime.LinuxRuntimeInstaller
import com.steamdeck.launcher.session.SessionService
import com.steamdeck.launcher.ui.DesktopAppsDialog
import com.steamdeck.launcher.ui.PackageRow
import com.steamdeck.launcher.session.OfflineMode
import com.steamdeck.launcher.session.ProtonExtras
import com.steamdeck.launcher.session.SessionPrefs
import com.steamdeck.launcher.ui.ProtonDialog
import com.steamdeck.launcher.ui.ProtonRow
import com.steamdeck.launcher.core.CpuCores
import com.steamdeck.launcher.ui.CoreRow
import com.steamdeck.launcher.ui.PerformancePage
import com.steamdeck.launcher.ui.ModeSettingsPage
import com.steamdeck.launcher.ui.ModeSettings
import com.steamdeck.launcher.ui.ModeSettingsActions
import com.steamdeck.launcher.ui.DriverRow
import com.steamdeck.launcher.ui.ConfirmDialog
import com.steamdeck.launcher.ui.CreditsDialog
import com.steamdeck.launcher.ui.FrontEndScreen
import com.steamdeck.launcher.ui.FrontEndState
import com.steamdeck.launcher.ui.FrontEndActions
import com.steamdeck.launcher.frontend.Library
import com.steamdeck.launcher.ui.SteamDeckTheme
import com.steamdeck.launcher.ui.RomsDialog
import com.steamdeck.launcher.files.InAppFilePicker
import com.steamdeck.launcher.session.SessionArtifacts
import com.steamdeck.launcher.session.SessionState
import com.steamdeck.launcher.session.GameStorage

/**
 * The whole app outside a session: is the runtime installed, is there a newer one, frame
 * generation, and one button that starts Steam. Everything a Steam client can do — the library,
 * the store, downloads, settings — is the client's own job once [SessionActivity] has it on screen.
 */
class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())

    // The screen's state. Compose redraws whatever reads these when they change.
    private var installed by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var available by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var percent by mutableIntStateOf(-1)
    private var failed by mutableStateOf(false)
    private var frameGenLabel by mutableStateOf("Off")
    private var showRemove by mutableStateOf(false)
    private var showNonAdreno by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var glThread by mutableStateOf(true)
    private var noGlError by mutableStateOf(true)
    private var steamDeckMode by mutableStateOf(false)
    private var showFrameGen by mutableStateOf(false)
    private var showCredits by mutableStateOf(false)
    private var showProtons by mutableStateOf(false)
    private var showApps by mutableStateOf(false)
    private var catalog by mutableStateOf<List<DesktopCatalog.Entry>?>(emptyList())
    private var packageRows by mutableStateOf<List<PackageRow>?>(emptyList())
    private var pkgStage by mutableStateOf<String?>(null)
    private var pkgPercent by mutableIntStateOf(-1)
    private var desktopInstalled by mutableStateOf(false)
    private var offlineAccount by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    private var showPerformance by mutableStateOf(false)
    private var clientOverride by mutableStateOf(false)
    private var clientCores by mutableStateOf<Set<Int>>(emptySet())
    private var gameCores by mutableStateOf<Set<Int>>(emptySet())
    private var tuSysmem by mutableStateOf(false)
    private var zinkLazy by mutableStateOf(false)
    private var noXalia by mutableStateOf(false)
    private var prootNoSeccomp by mutableStateOf(false)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var directAudio by mutableStateOf(false)
    private var mic by mutableStateOf(false)
    private var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    private var linuxSteam by mutableStateOf("")
    private var linuxDesktop by mutableStateOf("")
    private var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    private var androidSelected by mutableStateOf("")

    // The app's own picker (files/), once per kind of pick: the two driver lists validate
    // differently, and the reason a zip is refused names the list it belongs in.
    private val pickLinuxDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importDriver(it, linux = true) }
    }
    private val pickAndroidDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importDriver(it, linux = false) }
    }
    private val pickRomsDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setRomsDir(this, path)
            romsDir = path
        }
    }
    // The mode whose settings dialog is open, with what it shows; refreshed by openModeSettings().
    private var settingsMode by mutableStateOf<String?>(null)
    private var resolutionCap by mutableStateOf(1080)
    private var fexPreset by mutableStateOf("")
    private var shapeMode by mutableStateOf(SessionPrefs.SHAPE_AUTO)
    private var hdrOn by mutableStateOf(false)
    private var hdrReason by mutableStateOf<String?>(null)
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var renderer by mutableStateOf("pixman")
    private var gameStorage by mutableStateOf("")
    private var storageOptions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val pickGameStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path -> setGameStorage(path, GameStorage.labelFor(this, path)) }
    }
    private var emulators by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var showEmulatorHelp by mutableStateOf(false)
    private var romsDir by mutableStateOf<String?>(null)
    private var steamGames by mutableStateOf<List<Library.SteamGame>>(emptyList())
    private var emulatorList by mutableStateOf<List<Library.Emulator>>(emptyList())
    private var runningLabel by mutableStateOf<String?>(null)
    private var logsEnabled by mutableStateOf(true)
    private var showRoms by mutableStateOf(false)

    /** The session surface rises over the front end instead of cutting to it. */
    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        if (intent?.component?.className == SessionActivity::class.java.name) overridePendingTransition(R.anim.session_rise, R.anim.session_hold)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SteamDeckTheme {
                val sm = settingsMode
                val page: (@Composable () -> Unit)? = when {
                    sm != null -> { { ModeSettingsHost(sm) } }
                    showPerformance -> { { PerformanceHost() } }
                    else -> null
                }
                FrontEndScreen(
                    FrontEndState(
                        installed = installed, ready = ready, available = available?.version,
                        busy = busy, stage = stage, percent = percent,
                        desktopInstalled = desktopInstalled,
                        offlineAccount = offlineAccount, offline = offline,
                        frameGenLabel = frameGenLabel, romsDir = romsDir, logsEnabled = logsEnabled,
                        steamGames = steamGames, emulators = emulatorList, running = runningLabel,
                        frameGenEngine = FrameGen.engine(this), frameGenMultiplier = FrameGen.multiplier(this),
                        lsfgReady = LsfgNative.isInstalled(this),
                        pageKey = sm?.let { "settings:$it" } ?: if (showPerformance) "performance" else null,
                    ),
                    FrontEndActions(
                        onPlay = { startActivity(Intent(this, SessionActivity::class.java)) },
                        onPlayDesktopUi = {
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"))
                        },
                        onSteamGame = { g ->
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_STEAM_URL, "steam://rungameid/${g.appId}"))
                        },
                        onDesktop = {
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP))
                        },
                        onEmulator = { e -> launchProgram(e.program) },
                        onRom = { g ->
                            val e = emulatorList.first { it.id == g.emulatorId }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, e.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, Library.launchArgs(e.id, g.guestPath).toTypedArray()))
                        },
                        // The activity re-attaches to the session that is running; nothing restarts.
                        onResume = { startActivity(Intent(this, SessionActivity::class.java)) },
                        onSteamSettings = { openModeSettings(SessionService.MODE_STEAM) },
                        onDesktopSettings = { openModeSettings(SessionService.MODE_DESKTOP) },
                        onApps = { openApps() },
                        onRuntime = { onRuntimeButton() },
                        onFrameGenPick = { engine, multiplier ->
                            FrameGen.set(this, engine, multiplier)
                            frameGenLabel = FrameGen.label(this)
                        },
                        onProtons = { refreshProtons(); showProtons = true },
                        onPerformance = { refreshCores(); showPerformance = true },
                        onRoms = { showRoms = true },
                        onFiles = { startActivity(Intent(this, com.steamdeck.launcher.files.FileManagerActivity::class.java)) },
                        onLogs = {
                            SessionPrefs.setLogsEnabled(this, !SessionPrefs.logsEnabled(this))
                            logsEnabled = SessionPrefs.logsEnabled(this)
                        },
                        onOffline = {
                            OfflineMode.setEnabled(this, !OfflineMode.enabled(this))
                            offline = OfflineMode.enabled(this)
                        },
                        onEmulatorHelp = { showEmulatorHelp = true },
                        onCredits = { showCredits = true },
                        onPageBack = { settingsMode = null; showPerformance = false },
                    ),
                    page = page,
                )
                if (showRoms) RomsDialog(
                    path = romsDir,
                    onChoose = {
                        showRoms = false
                        pickRomsDir.launch(InAppFilePicker.buildDirIntent(this, "Choose the ROMs folder", romsDir))
                    },
                    onClear = { SessionPrefs.setRomsDir(this, ""); romsDir = null; showRoms = false },
                    onDismiss = { showRoms = false },
                )
                if (showApps) DesktopAppsDialog(
                    rows = packageRows, busyStage = pkgStage, busyPercent = pkgPercent,
                    onInstall = { id -> installPackage(id) },
                    onRemove = { id -> catalog?.firstOrNull { it.id == id }?.let { DesktopCatalog.remove(this, it) }; refreshPackages() },
                    onLaunch = { path -> showApps = false; launchProgram(path) },
                    onDismiss = { showApps = false },
                )
                if (showProtons) ProtonDialog(
                    rows = protonRows,
                    onInstall = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.queue(this, it) }; refreshProtons() },
                    onCancel = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.unqueue(this, it) }; refreshProtons() },
                    onRemove = { id -> ProtonExtras.tools.first { it.id == id }.let { ProtonExtras.remove(this, it) }; refreshProtons() },
                    onDismiss = { showProtons = false },
                )
                showNonAdreno?.let { release ->
                    ConfirmDialog(
                        title = "Not an Adreno GPU",
                        text = "The runtime draws with Turnip, an Adreno driver. On ${com.steamdeck.launcher.core.DeviceSupport.gpuName()} the compositor gets no usable Vulkan device and a session comes up as sound over a black screen. The download is ${"%.0f".format(release.size / 1e6)} MB.",
                        confirm = "Install anyway",
                        onConfirm = { showNonAdreno = null; install(release) },
                        onDismiss = { showNonAdreno = null },
                    )
                }
                if (showRemove) ConfirmDialog(
                    title = "Remove Linux runtime",
                    text = "This deletes the runtime, the Steam client inside it, and every game installed there.",
                    confirm = "Remove",
                    onConfirm = { Thread({ LinuxRuntimeInstaller.uninstall(this); ui.post { refresh() } }, "uninstall").start() },
                    onDismiss = { showRemove = false },
                )
                if (showCredits) CreditsDialog { showCredits = false }
                if (showEmulatorHelp) com.steamdeck.launcher.ui.EmulatorHelpDialog { showEmulatorHelp = false }
            }
        }

        // The session's logs land in Downloads so a failed run can be handed over as a folder
        // rather than dug out of app-private storage. targetSdk 28 means the old permission still
        // grants exactly that.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        // A session folder left without its ending - the process was killed - gets it now.
        if (!SessionState.running) Thread({ SessionArtifacts.finishAbandoned(this) }, "finish-abandoned").start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (!busy) Thread({ checkCatalog() }, "catalog").start()
    }

    private fun openApps() {
        showApps = true
        if (catalog.isNullOrEmpty()) Thread({
            val fetched = DesktopCatalog.fetch()
            ui.post { catalog = fetched; refreshPackages() }
        }, "catalog-desktop").start()
        else refreshPackages()
    }

    private fun refreshPackages() {
        packageRows = catalog?.sortedBy { it.tier }?.map {
            PackageRow(it.id, it.name, it.tier, it.version, FileUtils.sizeToString(it.size), it.notes,
                DesktopCatalog.installed(this, it.id), launchersOf(it))
        }
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        emulators = installedEmulators()
    }

    /** What a package can start on its own under gamescope; paths inside the runtime. */
    private fun launchersOf(entry: DesktopCatalog.Entry): List<Pair<String, String>> = launchersOf(entry.id)

    /**
     * The same, by package id alone, so the main screen's row is known from the install markers
     * before (or without) the catalog. The AppImage packages launch as /opt/appimages/<id>.AppImage.
     */
    private fun launchersOf(id: String): List<Pair<String, String>> = when (id) {
        "emulators" -> listOf("PPSSPP" to "/usr/bin/PPSSPPSDL", "RetroArch" to "/usr/bin/retroarch")
        "rpcs3" -> listOf("RPCS3" to "/opt/appimages/rpcs3.AppImage")
        "pcsx2" -> listOf("PCSX2" to "/opt/appimages/pcsx2.AppImage")
        "dolphin" -> listOf("Dolphin" to "/opt/appimages/dolphin.AppImage")
        "duckstation" -> listOf("DuckStation" to "/opt/appimages/duckstation.AppImage")
        "melonds" -> listOf("melonDS" to "/opt/appimages/melonds.AppImage")
        "cemu" -> listOf("Cemu" to "/opt/appimages/cemu.AppImage")
        else -> emptyList()
    }

    /** Every installed emulator's launcher, for the main screen. */
    private fun installedEmulators(): List<Pair<String, String>> =
        listOf("rpcs3", "pcsx2", "dolphin", "duckstation", "melonds", "cemu", "emulators")
            .filter { DesktopCatalog.installed(this, it) != null }
            .flatMap { launchersOf(it) }

    private fun launchProgram(path: String) {
        startActivity(Intent(this, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, path))
    }

    private fun installPackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (pkgStage != null) return
        pkgStage = "Starting…"; pkgPercent = -1
        Thread({
            val problem = DesktopCatalog.install(this, entry) { stage, percent ->
                ui.post { pkgStage = stage; pkgPercent = percent }
            }
            ui.post {
                pkgStage = null
                if (problem != null) android.widget.Toast.makeText(this, "${entry.name}: $problem", android.widget.Toast.LENGTH_LONG).show()
                refreshPackages()
            }
        }, "install-pkg").start()
    }

    /** Both driver lists as the dialog shows them, re-read from disk so an import or removal shows at once. */
    /** Everything the mode's cog shows, read fresh, then the dialog. */
    /** The Steam or Desktop settings page, in the front end's pane. */
    @Composable
    private fun ModeSettingsHost(mode: String) {
        ModeSettingsPage(
            ModeSettings(
                mode = mode, resolutionCap = resolutionCap, shapeMode = shapeMode,
                hdr = hdrOn, hdrReason = hdrReason,
                linuxRows = linuxRows,
                linuxSelected = if (mode == SessionService.MODE_STEAM) linuxSteam else linuxDesktop,
                androidRows = androidRows, androidSelected = androidSelected,
                touchMode = touchMode,
                oscMode = if (mode == SessionService.MODE_STEAM) oscMode else null,
                directAudio = if (mode == SessionService.MODE_STEAM) directAudio else null,
                mic = if (mode == SessionService.MODE_STEAM) mic else null,
                renderer = if (mode == SessionService.MODE_DESKTOP) renderer else null,
                gameStorage = if (mode == SessionService.MODE_STEAM) gameStorage else null,
                storageOptions = storageOptions,
                fexPreset = if (mode == SessionService.MODE_STEAM) fexPreset else null,
            ),
            ModeSettingsActions(
                onResolution = { cap -> SessionPrefs.setResolutionCap(this, mode, cap); resolutionCap = cap },
                onShape = { shape -> SessionPrefs.setShapeMode(this, shape); shapeMode = shape },
                onHdr = { on -> SessionPrefs.setHdr(this, mode, on); hdrOn = on },
                onSelectLinux = { id -> SessionPrefs.setLinuxDriver(this, mode, id); refreshDrivers() },
                onImportLinux = { pickLinuxDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a Linux runtime driver (-Linux zip)")) },
                onRemoveLinux = { id -> LinuxVulkanDriverManager(this).removeDriver(id); refreshDrivers() },
                onSelectAndroid = { id -> SessionPrefs.setAndroidDriver(this, id); refreshDrivers() },
                onImportAndroid = { pickAndroidDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a display driver (AdrenoTools zip)")) },
                onRemoveAndroid = { id -> TurnipDriver(this).remove(id); refreshDrivers() },
                onTouch = { t -> SessionPrefs.setTouchMode(this, t); touchMode = t },
                onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                onDirectAudio = { on -> SessionPrefs.setDirectAudio(this, on); directAudio = on },
                onMic = { on ->
                    SessionPrefs.setMicEnabled(this, on)
                    mic = on
                    // The session checks the grant itself at start; asking here means the
                    // answer is in before the first session that wants it.
                    if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    }
                },
                onRenderer = { r -> SessionPrefs.setDesktopRenderer(this, r); renderer = r },
                onGameStorage = { path, label -> setGameStorage(path, label) },
                onPickGameStorageFolder = {
                    pickGameStorage.launch(InAppFilePicker.buildDirIntent(this, "Choose the game storage folder", gameStorage.ifEmpty { null }))
                },
                onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                onDismiss = { settingsMode = null },
            ),
        )
    }

    /** The Performance page, in the front end's pane. */
    @Composable
    private fun PerformanceHost() {
        PerformancePage(
            cores = CpuCores.all.map { c -> CoreRow(c, "cpu$c" + (CpuCores.maxGhz(c)?.let { String.format(java.util.Locale.US, " · %.1f GHz", it) } ?: "")) },
            clientOverride = clientOverride, clientCores = clientCores, gameCores = gameCores,
            tuSysmem = tuSysmem, zinkLazy = zinkLazy, glThread = glThread, noGlError = noGlError, steamDeckMode = steamDeckMode, noXalia = noXalia,
            prootNoSeccomp = prootNoSeccomp, phantomWarning = phantomWarning,
            onClientOverride = { on -> SessionPrefs.setClientCpusOverride(this, on); clientOverride = on },
            onTuSysmem = { on -> SessionPrefs.setTuSysmem(this, on); tuSysmem = on },
            onZinkLazy = { on -> SessionPrefs.setZinkLazy(this, on); zinkLazy = on },
            onGlThread = { on -> SessionPrefs.setGlThread(this, on); glThread = on },
            onNoGlError = { on -> SessionPrefs.setNoGlError(this, on); noGlError = on },
            onSteamDeckMode = { on -> SessionPrefs.setSteamDeckMode(this, on); steamDeckMode = on },
            onNoXalia = { on -> SessionPrefs.setNoXalia(this, on); noXalia = on },
            onProotNoSeccomp = { on -> SessionPrefs.setProotNoSeccomp(this, on); prootNoSeccomp = on },
            onClientCore = { core, on ->
                clientCores = if (on) clientCores + core else clientCores - core
                SessionPrefs.setClientCpus(this, CpuCores.format(clientCores))
            },
            onGameCore = { core, on ->
                gameCores = if (on) gameCores + core else gameCores - core
                SessionPrefs.setGameCpus(this, CpuCores.format(gameCores))
            },
            onDismiss = { showPerformance = false },
        )
    }

    private fun openModeSettings(mode: String) {
        refreshDrivers()
        resolutionCap = SessionPrefs.resolutionCap(this, mode)
        fexPreset = SessionPrefs.fexPreset(this)
        shapeMode = SessionPrefs.shapeMode(this)
        hdrOn = SessionPrefs.hdr(this, mode)
        hdrReason = com.steamdeck.launcher.wayland.HdrSupport.probe(this).reason
        touchMode = SessionPrefs.touchMode(this)
        oscMode = SessionPrefs.oscMode(this)
        directAudio = SessionPrefs.directAudio(this)
        mic = SessionPrefs.micEnabled(this)
        renderer = SessionPrefs.desktopRenderer(this)
        gameStorage = SessionPrefs.gameStorage(this)
        storageOptions = GameStorage.options(this).map { it.label to it.path }
        settingsMode = mode
    }

    /** A second Steam library, proven writable first; "" = internal only. */
    private fun setGameStorage(path: String, label: String) {
        if (path.isNotEmpty() && path != SessionPrefs.GAME_STORAGE_OFF) {
            val problem = GameStorage.prepare(path)
            if (problem != null) {
                android.widget.Toast.makeText(this, "Not usable: $problem", android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        SessionPrefs.setGameStorage(this, path, label)
        gameStorage = path
    }

    private fun refreshDrivers() {
        val lm = LinuxVulkanDriverManager(this)
        linuxRows = LinuxVulkanDriver.optionValues(this).map { id ->
            if (id.isEmpty()) DriverRow("", "Runtime default", "the Turnip built into the runtime", false)
            else DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                ).joinToString(" · ").ifEmpty { "imported" },
                true,
            )
        }
        linuxSteam = SessionPrefs.linuxDriver(this, SessionService.MODE_STEAM)
        linuxDesktop = SessionPrefs.linuxDriver(this, SessionService.MODE_DESKTOP)
        val td = TurnipDriver(this)
        val auto = td.autoId()
        androidRows = buildList {
            add(DriverRow(
                TurnipDriver.AUTO, "Auto — picked by GPU",
                if (auto == "system") "system Vulkan: no bundled build for this GPU" else "${td.displayName(auto)} (bundled)",
                false,
            ))
            for (id in TurnipDriver.BUNDLED) add(DriverRow(id, td.displayName(id), "bundled" + td.driverVersion(id).let { if (it.isEmpty()) "" else " · $it" }, false))
            for (id in td.enumerateImported()) add(DriverRow(id, td.displayName(id), "imported" + td.driverVersion(id).let { if (it.isEmpty()) "" else " · $it" }, true))
        }
        androidSelected = SessionPrefs.androidDriver(this)
    }

    /**
     * Import off the main thread — a driver zip is a few MB and the glibc check reads the whole
     * library — then say what happened. A refusal's message is the user-facing reason.
     */
    private fun importDriver(uri: Uri, linux: Boolean) {
        val name = displayNameOf(uri)
        Thread({
            val problem = try {
                if (linux) LinuxVulkanDriverManager(this).installDriver(uri, name)
                else TurnipDriver(this).installFromZip(uri, name)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "driver import", e)
                "Import failed: ${e.message}"
            }
            ui.post {
                android.widget.Toast.makeText(
                    this, problem ?: "Imported ${name ?: "driver"}",
                    if (problem != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    private fun displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** The two masks as the dialog shows them; an empty stored list shows as every core ticked. */
    private fun refreshCores() {
        clientOverride = SessionPrefs.clientCpusOverride(this)
        glThread = SessionPrefs.glThread(this)
        noGlError = SessionPrefs.noGlError(this)
        steamDeckMode = SessionPrefs.steamDeckMode(this)
        clientCores = CpuCores.parse(SessionPrefs.clientCpus(this)).ifEmpty { CpuCores.all.toSet() }
        gameCores = CpuCores.parse(SessionPrefs.gameCpus(this)).ifEmpty { CpuCores.all.toSet() }
        tuSysmem = SessionPrefs.tuSysmem(this)
        zinkLazy = SessionPrefs.zinkLazy(this)
        noXalia = SessionPrefs.noXalia(this)
        prootNoSeccomp = SessionPrefs.prootNoSeccomp(this)
        // Android 12 kills the children an app forks itself once there are more than a handful.
        // A session is nothing but those, so where this is on the OS ends the session and no log
        // of ours says why. We cannot change a secure setting from here - only say so.
        phantomWarning = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull().let { v ->
            when (v?.lowercase()) {
                "false", "0" -> null
                else -> "Android 12 and later kill the extra processes an app starts for itself once there are more than a few, and a session is made of dozens: proot, gamescope, the client and its helpers, Wine. Where that is left on, the client dies with nothing in its log, because nothing in the session did it. Some phones have a \"restrict child processes\" switch in Developer options — turn it off. Otherwise, over adb:\n\n    adb shell settings put global settings_enable_monitor_phantom_procs false\n\nThis phone " + (if (v == null) "has not been set either way, so the ROM's default applies." else "currently reports it as on.")
            }
        }
    }

    private fun refreshProtons() {
        protonRows = ProtonExtras.tools.map { ProtonRow(it.id, it.name, ProtonExtras.installed(this, it), ProtonExtras.queued(this, it)) }
    }

    private fun refresh() {
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        offlineAccount = OfflineMode.account(this)
        offline = OfflineMode.enabled(this)
        installed = LinuxRuntimeInstaller.installedVersion(this)
        ready = LinuxRuntime.isInstalled(this)
        frameGenLabel = FrameGen.label(this)
        romsDir = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }
        logsEnabled = SessionPrefs.logsEnabled(this)
        emulators = if (ready) installedEmulators() else emptyList()
        runningLabel = if (SessionState.running) when (SessionState.mode) {
            SessionService.MODE_DESKTOP -> "Desktop"
            SessionService.MODE_RUN -> SessionState.program?.substringAfterLast('/')?.substringBefore('.') ?: "Program"
            else -> "Steam"
        } else null
        // The libraries, off the main thread: manifests and a folder scan.
        Thread({
            val games = if (ready) Library.steamGames(this) else emptyList()
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { steamGames = games; emulatorList = emus }
        }, "library").start()
    }

    private fun onRuntimeButton() {
        if (busy) return
        val release = available
        if (installed != null && release?.version == installed) {
            // Nothing to install: offer the one destructive thing this screen can do.
            showRemove = true
            return
        }
        if (release == null) {
            Thread({ checkCatalog() }, "catalog").start()
            return
        }
        // The runtime draws with Turnip, an Adreno driver: on Mali, Xclipse or PowerVR the
        // compositor gets no usable Vulkan device and a session is sound over a black screen.
        // Said before the download, not after it; the user may still go ahead.
        if (installed == null && !com.steamdeck.launcher.core.DeviceSupport.adreno()) { showNonAdreno = release; return }
        install(release)
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
        busy = true
        failed = false
        stage = "Starting…"
        percent = -1
        Thread({
            val ok = LinuxRuntimeInstaller.install(this, release) { s, p ->
                ui.post { stage = s; percent = p }
            }
            ui.post {
                busy = false
                failed = !ok
                refresh()
            }
        }, "install").start()
    }

    private fun checkCatalog() {
        val release = LinuxRuntimeInstaller.fetchRelease()
        Log.i(TAG, "catalog: " + (release?.version ?: "unreachable"))
        ui.post { if (release != null) available = release }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** What the picker offers for a driver zip; some file apps label a zip as a plain stream. */
        private val ZIP_EXT = listOf("zip")
    }
}
