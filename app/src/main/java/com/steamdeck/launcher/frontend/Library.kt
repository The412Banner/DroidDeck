package com.steamdeck.launcher.frontend

import android.content.Context
import com.steamdeck.launcher.R
import com.steamdeck.launcher.runtime.LinuxRuntime
import com.steamdeck.launcher.session.GameStorage
import com.steamdeck.launcher.session.SessionPrefs
import java.io.File

/**
 * What the front end lists: the Steam client's installed games (from its own appmanifests, both
 * libraries) and each emulator's games (files in the ROMs folder, by extension, with a system
 * folder as a hint). Read on a worker thread; nothing here is cached beyond one screen refresh.
 */
object Library {
    /** [gameId] is what steam://rungameid/ takes: the appid for a Steam title, the shortcut id for an added game. */
    class SteamGame(val appId: Int, val name: String, val art: File?, val library: String, val gameId: Long = appId.toLong())
    class Rom(val name: String, val hostPath: File, val guestPath: String, val emulatorId: String, val art: File? = null)
    class Emulator(val id: String, val name: String, val system: String, val program: String, val installed: Boolean, val games: List<Rom>) {
        /** The emulator's own icon, bundled (the runtime keeps them as theme SVGs the app cannot draw). */
        val iconRes: Int get() = when (id) {
            "rpcs3" -> R.drawable.emu_rpcs3; "pcsx2" -> R.drawable.emu_pcsx2; "dolphin" -> R.drawable.emu_dolphin
            "duckstation" -> R.drawable.emu_duckstation; "melonds" -> R.drawable.emu_melonds; "cemu" -> R.drawable.emu_cemu
            "ppsspp" -> R.drawable.emu_ppsspp; else -> R.drawable.emu_retroarch
        }
    }

    /** The client's own tools and runtimes live in steamapps beside the games; they are not titles. */
    private val NOT_GAMES = setOf(228980, 1493710, 3127680, 4183110, 4427310, 4185400)
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)

    fun steamGames(context: Context): List<SteamGame> {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        val cache = File(root, "appcache/librarycache")
        val libraries = listOfNotNull(
            root to "internal",
            GameStorage.effective(context)?.let { File(it.path) to it.label },
        )
        val out = LinkedHashMap<Int, SteamGame>()
        for ((library, label) in libraries) {
            val steamapps = File(library, "steamapps")
            steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
                ?.sortedBy { it.name }?.forEach { manifest ->
                    val appId = manifest.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@forEach
                    if (appId in NOT_GAMES || out.containsKey(appId)) return@forEach
                    val text = try { manifest.readText() } catch (e: Exception) { return@forEach }
                    val name = NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val flags = STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    // StateFlags 4 = fully installed; anything else is downloading, updating or broken.
                    if (name.isEmpty() || flags and 4 == 0) return@forEach
                    val dir = File(cache, appId.toString())
                    val art = listOf("library_600x900.jpg", "logo.png", "library_header.jpg", "header.jpg")
                        .map { File(dir, it) }.firstOrNull { it.isFile }
                    out[appId] = SteamGame(appId, name, art, label)
                }
        }
        return out.values.toList()
    }

    private class Spec(val id: String, val name: String, val system: String, val program: String, val folders: List<String>, val exts: Set<String>)
    private val specs = listOf(
        Spec("rpcs3", "RPCS3", "PS3", "/opt/appimages/rpcs3.AppImage", listOf("ps3"), setOf("iso")),
        Spec("pcsx2", "PCSX2", "PS2", "/opt/appimages/pcsx2.AppImage", listOf("ps2"), setOf("iso", "chd", "cso", "gz")),
        Spec("dolphin", "Dolphin", "GameCube / Wii", "/opt/appimages/dolphin.AppImage", listOf("gc", "gamecube", "wii"), setOf("iso", "rvz", "gcz", "wbfs", "ciso")),
        Spec("duckstation", "DuckStation", "PS1", "/opt/appimages/duckstation.AppImage", listOf("ps1", "psx"), setOf("cue", "chd", "pbp", "iso", "bin", "img", "ecm", "m3u")),
        Spec("melonds", "melonDS", "DS", "/opt/appimages/melonds.AppImage", listOf("ds", "nds"), setOf("nds", "dsi")),
        Spec("cemu", "Cemu", "Wii U", "/opt/appimages/cemu.AppImage", listOf("wiiu", "wii u"), setOf("wua", "wud", "wux", "rpx")),
        Spec("ppsspp", "PPSSPP", "PSP", "/usr/bin/PPSSPPSDL", listOf("psp"), setOf("iso", "cso", "pbp", "chd")),
        Spec("retroarch", "RetroArch", "many systems", "/usr/bin/retroarch", emptyList(), emptySet()),
    )
    private val installedIds = mapOf(
        "rpcs3" to "rpcs3", "pcsx2" to "pcsx2", "dolphin" to "dolphin", "duckstation" to "duckstation",
        "melonds" to "melonds", "cemu" to "cemu", "ppsspp" to "emulators", "retroarch" to "emulators",
    )

    /** Every emulator the app knows, installed or not, with the games its system folder holds. */
    fun emulators(context: Context, installedPackage: (String) -> Boolean): List<Emulator> {
        val romsRoot = SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
        return specs.map { spec ->
            val games = ArrayList<Rom>()
            if (romsRoot != null && spec.exts.isNotEmpty()) {
                // The system's folder(s), matched without regard to case, then the root itself for
                // a file left loose there - and one folder deeper, since a dump usually comes as a
                // folder named for the game with the image inside it.
                val systemDirs = romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in spec.folders }.orEmpty().toList()
                val dirs = LinkedHashSet<File>()
                for (top in systemDirs + romsRoot) {
                    dirs.add(top)
                    top.listFiles { f -> f.isDirectory }?.forEach { dirs.add(it) }
                }
                for (dir in dirs) {
                    // A PS3 disc dump is a folder with PS3_GAME in it; RPCS3 boots the folder.
                    if (spec.id == "rpcs3" && File(dir, "PS3_GAME").isDirectory) {
                        val rel = dir.relativeTo(romsRoot).path
                        games.add(Rom(dir.name, dir, "/root/ROMs/$rel", spec.id))
                        continue
                    }
                    dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                        val ext = f.extension.lowercase()
                        // A .bin beside a .cue is a track, not a game.
                        if (f.isFile && ext in spec.exts && !(ext == "bin" && File(dir, f.nameWithoutExtension + ".cue").isFile)) {
                            val rel = f.relativeTo(romsRoot).path
                            games.add(Rom(f.nameWithoutExtension.removeSuffix(".dec"), f, "/root/ROMs/$rel", spec.id))
                        }
                    }
                }
            }
            if (spec.id == "rpcs3") games.addAll(rpcs3Installed(context))
            Emulator(spec.id, spec.name, spec.system, spec.program, installedPackage(installedIds.getValue(spec.id)), games)
        }
    }

    /**
     * What RPCS3 has installed on its own HDD - packages (PSN games) land in dev_hdd0/game/<ID>
     * with a PARAM.SFO for the title and an EBOOT to boot - as RPCS3's own file list would show
     * them. Not in the ROMs folder, so listed from RPCS3's home in the runtime.
     */
    private fun rpcs3Installed(context: Context): List<Rom> {
        val hdd = File(LinuxRuntime.rootDir(context), "root/.config/rpcs3/dev_hdd0/game")
        return hdd.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull { dir ->
            val eboot = File(dir, "USRDIR/EBOOT.BIN")
            val sfo = File(dir, "PARAM.SFO")
            if (!eboot.isFile || !sfo.isFile) return@mapNotNull null
            val fields = readSfo(sfo)
            // Only games: patches, DLC and save data live here too, with their own categories.
            if (fields["CATEGORY"]?.let { it == "HG" || it == "DG" || it == "GD" } == false) return@mapNotNull null
            val title = fields["TITLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
            Rom(title, eboot, "/root/.config/rpcs3/dev_hdd0/game/${dir.name}/USRDIR/EBOOT.BIN", "rpcs3",
                art = File(dir, "ICON0.PNG").takeIf { it.isFile })
        } ?: emptyList()
    }

    /** The string fields of a PARAM.SFO (the PSP/PS3 metadata file): a small binary table. */
    private fun readSfo(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val b = file.readBytes()
            if (b.size < 20 || b[0] != 0.toByte() || b[1] != 'P'.code.toByte()) return out
            fun u32(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
            fun u16(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
            val keys = u32(8); val data = u32(12); val count = u32(16)
            for (i in 0 until count) {
                val e = 20 + i * 16
                if (e + 16 > b.size) break
                val keyOff = u16(e); val fmt = u16(e + 2); val len = u32(e + 4); val dataOff = u32(e + 12)
                val keyStart = keys + keyOff
                var keyEnd = keyStart
                while (keyEnd < b.size && b[keyEnd] != 0.toByte()) keyEnd++
                val key = String(b, keyStart, keyEnd - keyStart, Charsets.US_ASCII)
                if (fmt == 0x0204 || fmt == 0x0004) {   // utf8 string (null-terminated or not)
                    val start = data + dataOff
                    val end = minOf(b.size, start + len)
                    var stop = start
                    while (stop < end && b[stop] != 0.toByte()) stop++
                    out[key] = String(b, start, stop - start, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            // unreadable metadata: the folder name stands in
        }
        return out
    }

    /** How the emulator is told which game to boot, on its command line. */
    fun launchArgs(emulatorId: String, guestPath: String): List<String> = when (emulatorId) {
        "rpcs3" -> listOf("--no-gui", guestPath)
        "dolphin" -> listOf("-e", guestPath)
        "cemu" -> listOf("-g", guestPath)
        else -> listOf(guestPath)
    }
}
