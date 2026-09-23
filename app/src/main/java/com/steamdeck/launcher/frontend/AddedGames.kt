package com.steamdeck.launcher.frontend

import android.content.Context
import android.os.Environment
import android.util.Log
import com.steamdeck.launcher.session.GameStorage
import com.steamdeck.launcher.session.SessionPrefs
import java.io.File
import java.util.zip.CRC32

/**
 * The user's own Windows games, added to the Steam client's library.
 *
 * A Games folder is scanned one level deep: each subfolder is one game, and the program to launch
 * is the .exe in it that looks most like the game - the one named after the folder, else the
 * largest, never an installer, redistributable or crash reporter - unless the user picked one
 * for that game. Each game becomes a non-Steam shortcut in the client, under the ARM64 Proton,
 * with the appid the client itself would derive, so launching from the rail and from the
 * client's own library are the same thing. The folder has to be somewhere the session can see:
 * internal storage (/root/Storage), the ROMs folder, or the SD-card game library.
 */
object AddedGames {
    private const val TAG = "AddedGames"

    class Game(
        val folder: File, val name: String, val exe: File, val guestExe: String, val guestDir: String,
        /** The client's 32-bit appid for this shortcut, as an unsigned value. */
        val appId: Long,
        /** What steam://rungameid/ takes for a shortcut. */
        val gameId: Long,
        val candidates: List<File>,
    ) {
        fun folderName(): String = folder.name
    }

    private val SKIP = Regex(
        "(?i)^(unins.*|setup.*|.*redist.*|vcredist.*|dxsetup.*|dxwebsetup.*|.*crash.*|.*report.*|dotnet.*|directx.*|.*prereq.*" +
            "|.*installer.*|.*uninstall.*|.*updater?.*|.*config(ur.*)?|.*settings.*|.*editor.*|.*server.*|.*benchmark.*|.*helper.*|.*eac.*|.*easyanticheat.*|.*battleye.*)\\.exe$",
    )

    /** Where a host path appears inside the session, or null when the session cannot see it. */
    /** Where the chosen Games folder itself is bound inside the session, wherever it is on the device. */
    const val GUEST_DIR = "/root/Games"

    fun guestPath(context: Context, host: File): String? {
        val path = host.absolutePath
        // The Games folder is bound on its own, so a folder anywhere - an SD card, a USB drive -
        // works without being inside one of the other binds.
        SessionPrefs.addedGamesDir(context).takeIf { it.isNotEmpty() }?.let { dir ->
            if (path == dir) return GUEST_DIR
            if (path.startsWith("$dir/")) return "$GUEST_DIR/" + path.removePrefix("$dir/")
        }
        val storage = Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith("$storage/")) return "/root/Storage/" + path.removePrefix("$storage/")
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { roms ->
            if (path.startsWith("$roms/")) return "/root/ROMs/" + path.removePrefix("$roms/")
        }
        GameStorage.effective(context)?.let { lib ->
            if (path.startsWith("${lib.path}/")) return "/mnt/bannerlator-sd/" + path.removePrefix("${lib.path}/")
        }
        return null
    }

    /** The .exe files a game folder offers, best first. */
    fun candidates(folder: File): List<File> {
        val exes = ArrayList<File>()
        val roots = listOf(folder) + (folder.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
        for (dir in roots) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !SKIP.matches(f.name) }
                ?.let { exes.addAll(it) }
        }
        val key = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return exes.sortedWith(
            compareByDescending<File> { it.parentFile == folder }
                .thenByDescending { it.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == key || key.startsWith(n) || n.startsWith(key) } }
                .thenByDescending { it.length() },
        )
    }

    fun scan(context: Context): List<Game> {
        val dir = SessionPrefs.addedGamesDir(context).takeIf { it.isNotEmpty() }?.let { File(it) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<Game>()
        for (folder in dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            val candidates = candidates(folder)
            val chosen = SessionPrefs.addedGameExe(context, folder.name).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
            val exe = chosen ?: candidates.firstOrNull() ?: continue
            val guestExe = guestPath(context, exe)
            if (guestExe == null) { Log.w(TAG, "${folder.name}: the session cannot see ${exe.path}"); continue }
            val guestDir = guestPath(context, exe.parentFile ?: folder) ?: continue
            val name = folder.name
            val crc = CRC32().apply { update(("\"$guestExe\"" + name).toByteArray()) }.value
            val appId = crc or 0x80000000L
            out.add(Game(folder, name, exe, guestExe, guestDir, appId, (appId shl 32) or 0x02000000L, candidates))
        }
        return out
    }

    /** The list the session hands the runtime's shortcuts writer; one file per session start. */
    fun writeListing(context: Context, games: List<Game>): File {
        val file = File(context.filesDir, "session/added-games.json").apply { parentFile?.mkdirs() }
        val json = StringBuilder("[")
        games.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(quote(g.name)).append(",\"exe\":").append(quote(g.guestExe))
                .append(",\"dir\":").append(quote(g.guestDir)).append(",\"appid\":").append(g.appId).append('}')
        }
        file.writeText(json.append(']').toString())
        return file
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
