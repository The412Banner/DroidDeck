package com.steamdeck.launcher.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** Installs the upstream Decky Loader into the persistent guest home and leaves plugin data alone. */
object DeckyManager {
    private const val TAG = "DeckyManager"
    private const val API = "https://api.github.com/repos/SteamDeckHomebrew/decky-loader"
    private const val DOWNLOAD_PREFIX = "https://github.com/SteamDeckHomebrew/decky-loader/releases/download/"
    private const val FEX_ROOTFS_MANIFEST = "https://raw.githubusercontent.com/FEX-Emu/RootFS/main/RootFS_links.json"
    private const val MAX_ROOTFS_BYTES = 1024L * 1024 * 1024
    private const val ROOTFS_NAME = "Ubuntu_24_04.sqsh"
    private const val ASSET_NAME = "PluginLoader"
    private const val MAX_ASSET_BYTES = 96L * 1024 * 1024
    const val STABLE = "stable"
    const val PRERELEASE = "prerelease"

    data class Release(val channel: String, val version: String, val url: String, val sha256: String, val size: Long)
    data class Status(val installed: Boolean, val version: String?, val channel: String?, val checksum: String?)
    fun interface Progress { fun onProgress(stage: String, percent: Int) }

    private fun guestRoot(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun homebrew(context: Context) = File(guestRoot(context), "homebrew")
    private fun services(context: Context) = File(homebrew(context), "services")
    private fun loader(context: Context) = File(services(context), ASSET_NAME)
    private fun metadata(context: Context) = File(services(context), ".steamdeck-decky.json")
    private fun cefMarker(context: Context) = File(guestRoot(context), ".local/share/Steam/.cef-enable-remote-debugging")
    private fun rootfsDir(context: Context) = File(guestRoot(context), ".fex-emu/RootFS")
    private fun rootfs(context: Context) = File(rootfsDir(context), ROOTFS_NAME)
    private fun rootfsMetadata(context: Context) = File(rootfsDir(context), ".bannerlator-rootfs.json")

    fun fexRootfsReady(context: Context): Boolean {
        val image = rootfs(context)
        val expected = runCatching { rootfsMetadata(context).readText().let(::JSONObject).optString("xxh64") }.getOrNull()
        return image.isFile && image.length() > 0 && expected?.matches(Regex("[0-9a-f]{16}")) == true
    }

    fun status(context: Context): Status {
        val binary = loader(context)
        val marker = readMetadata(context)
        return Status(
            installed = binary.isFile && binary.length() > 0,
            version = marker?.optString("version")?.takeIf { it.isNotBlank() },
            channel = marker?.optString("channel")?.takeIf { it == STABLE || it == PRERELEASE },
            checksum = marker?.optString("sha256")?.takeIf { it.isNotBlank() },
        )
    }

    /** One releases request supplies both channels and avoids spending the API quota twice. */
    fun fetchReleases(): Pair<Release, Release> {
        val releases = org.json.JSONArray(readHttps("$API/releases?per_page=100", "application/vnd.github+json"))
        val published = (0 until releases.length()).asSequence()
            .map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .toList()
        val stableRelease = published.firstOrNull { !it.optBoolean("prerelease") }
            ?: error("Decky has no published stable release")
        val prereleaseRelease = published.firstOrNull { it.optBoolean("prerelease") }
            ?: error("Decky has no published prerelease")
        return release(stableRelease, STABLE) to release(prereleaseRelease, PRERELEASE)
    }

    private fun release(release: JSONObject, channel: String): Release {
        val tag = release.optString("tag_name")
        require(tag.isNotBlank() && !release.optBoolean("draft")) { "Decky release metadata is invalid" }
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == ASSET_NAME }
            ?: error("The Decky $tag release has no PluginLoader asset")
        val url = asset.optString("browser_download_url")
        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
        val size = asset.optLong("size", 0L)
        require(url.startsWith(DOWNLOAD_PREFIX) && digest.matches(Regex("[0-9a-f]{64}")) && size in 1..MAX_ASSET_BYTES) {
            "Decky release metadata is missing a trusted download URL, SHA-256, or valid size"
        }
        return Release(channel, tag, url, digest, size)
    }

    /** Download, verify, and atomically replace only PluginLoader and this manager's metadata. */
    fun install(context: Context, release: Release, progress: Progress? = null): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return "Install the Linux runtime first"
        if (release.channel != STABLE && release.channel != PRERELEASE) return "Unknown Decky channel"
        if (!release.url.startsWith(DOWNLOAD_PREFIX) || !release.sha256.matches(Regex("[0-9a-fA-F]{64}")) ||
            release.size !in 1..MAX_ASSET_BYTES) return "Decky release metadata is invalid"

        val download = File(context.cacheDir, "decky-PluginLoader.download")
        var stagingDir: File? = null
        try {
            ensureFexRootfs(context, progress)?.let { return it }
            progress?.onProgress("Downloading Decky ${release.version}", 0)
            downloadRelease(release, download, Progress { name, percent ->
                progress?.onProgress(name, if (percent < 0) percent else 80 + percent / 5)
            })
            progress?.onProgress("Verifying release", -1)
            val actual = sha256(download)
            if (!actual.equals(release.sha256, ignoreCase = true)) return "SHA-256 did not match; the installed loader was left unchanged"
            if (!isX86_64Elf(download)) return "The Decky asset is not an x86_64 Linux executable"

            val serviceDir = services(context)
            stagingDir = serviceDir
            if (!serviceDir.isDirectory && !serviceDir.mkdirs()) return "Could not create the Decky services directory"
            val target = loader(context)
            val staged = File(serviceDir, ".$ASSET_NAME.new")
            val backup = File(serviceDir, ".$ASSET_NAME.backup")
            val marker = metadata(context)
            val markerStage = File(serviceDir, ".steamdeck-decky.json.new")
            val markerBackup = File(serviceDir, ".steamdeck-decky.json.backup")
            staged.delete(); backup.delete(); markerStage.delete(); markerBackup.delete()

            copyAndSync(download, staged)
            if (!staged.setExecutable(true, false)) return "Could not make PluginLoader executable"
            val oldMarker = readMetadata(context)
            val cef = cefMarker(context)
            val madeCefMarker = oldMarker?.optBoolean("cefMarkerCreated") ?: !cef.exists()
            val nextMarker = JSONObject()
                .put("version", release.version)
                .put("channel", release.channel)
                .put("sha256", actual)
                .put("cefMarkerCreated", madeCefMarker)
            writeAndSync(markerStage, nextMarker.toString())

            if (target.exists() && !target.renameTo(backup)) return "Could not stage the installed Decky loader"
            if (marker.exists() && !marker.renameTo(markerBackup)) {
                if (backup.exists()) backup.renameTo(target)
                return "Could not stage Decky install metadata"
            }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                if (markerBackup.exists()) markerBackup.renameTo(marker)
                return "Could not activate the downloaded Decky loader"
            }
            if (!markerStage.renameTo(marker)) {
                target.delete()
                if (backup.exists()) backup.renameTo(target)
                if (markerBackup.exists()) markerBackup.renameTo(marker)
                return "Could not write Decky install metadata; the previous loader was restored"
            }
            if (madeCefMarker && !cef.exists()) {
                cef.parentFile?.mkdirs()
                if (!cef.createNewFile() && !cef.exists()) {
                    target.delete(); marker.delete()
                    if (backup.exists()) backup.renameTo(target)
                    if (markerBackup.exists()) markerBackup.renameTo(marker)
                    return "Could not enable Steam's remote debugging marker; the previous loader was restored"
                }
            }
            backup.delete(); markerBackup.delete()
            progress?.onProgress("Decky ${release.version} installed", 100)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Decky install failed", e)
            return e.message ?: "Decky install failed"
        } finally {
            download.delete()
            stagingDir?.let { dir ->
                File(dir, ".$ASSET_NAME.new").delete()
                File(dir, ".steamdeck-decky.json.new").delete()
                File(dir, ".$ASSET_NAME.backup").takeIf { !loader(context).exists() }?.renameTo(loader(context))
                File(dir, ".steamdeck-decky.json.backup").takeIf { !metadata(context).exists() }?.renameTo(metadata(context))
            }
        }
    }

    /** Preserve mode removes only the loader and manager metadata. Wipe mode removes ~/homebrew. */
    fun uninstall(context: Context, wipeData: Boolean): String? {
        return try {
            val marker = readMetadata(context)
            val cef = cefMarker(context)
            if (wipeData) {
                if (!deleteTree(homebrew(context))) return "Could not remove all Decky data"
            } else {
                if (!deleteTree(loader(context)) || !deleteTree(metadata(context))) return "Could not remove the Decky loader"
                val serviceDir = services(context)
                if (serviceDir.isDirectory && serviceDir.list()?.isEmpty() == true) serviceDir.delete()
                val homebrew = homebrew(context)
                if (homebrew.isDirectory && homebrew.list()?.isEmpty() == true) homebrew.delete()
            }
            if (marker?.optBoolean("cefMarkerCreated") == true) cef.delete()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Decky uninstall failed", e)
            e.message ?: "Decky uninstall failed"
        }
    }

    private fun readMetadata(context: Context): JSONObject? = try {
        metadata(context).takeIf { it.isFile }?.readText()?.let(::JSONObject)
    } catch (_: Exception) { null }

    private fun readHttps(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SteamDeck-Decky-Manager")
            setRequestProperty("Accept", accept)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("GitHub returned HTTP $code${if (detail.isBlank()) "" else ": ${detail.take(180)}"}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun downloadRelease(release: Release, target: File, progress: Progress?) {
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SteamDeck-Decky-Manager")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Decky download returned HTTP $code")
            val reportedLength = connection.contentLengthLong
            if (reportedLength > MAX_ASSET_BYTES || (reportedLength > 0 && reportedLength != release.size)) {
                error("Decky asset size did not match release metadata")
            }
            var copied = 0L
            connection.inputStream.use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_ASSET_BYTES || copied > release.size) error("Decky asset exceeded its published size")
                    output.write(buffer, 0, count)
                    if (release.size > 0) progress?.onProgress("Downloading Decky ${release.version}", (copied * 100 / release.size).toInt())
                }
                output.fd.sync()
            } }
            if (copied != release.size) error("Decky download was incomplete (${copied} of ${release.size} bytes)")
        } finally { connection.disconnect() }
    }

    /** Get FEX's official x86-64 Ubuntu rootfs; without it the upstream Linux loader cannot run. */
    private fun ensureFexRootfs(context: Context, progress: Progress?): String? {
        val manifest: JSONObject
        val entry: JSONObject
        try {
            progress?.onProgress("Checking FEX root filesystem", -1)
            manifest = JSONObject(readHttps(FEX_ROOTFS_MANIFEST, "application/json"))
            entry = manifest.getJSONObject("v1").getJSONObject("Ubuntu 24.04 (SquashFS)")
        } catch (e: Exception) {
            return "Could not check the official FEX root filesystem: ${e.message ?: "network error"}"
        }
        val url = entry.optString("URL")
        val expected = entry.optString("Hash").lowercase()
        if (!url.startsWith("https://rootfs.fex-emu.gg/") || !expected.matches(Regex("[0-9a-f]{16}")) ||
            entry.optString("Type") != "squashfs" || entry.optString("DistroVersion") != "24.04") {
            return "The official FEX root filesystem metadata was invalid"
        }

        val directory = rootfsDir(context)
        if (!directory.isDirectory && !directory.mkdirs()) return "Could not create the FEX root filesystem directory"
        val image = rootfs(context)
        val marker = rootfsMetadata(context)
        val stage = File(directory, ".$ROOTFS_NAME.part")
        val backup = File(directory, ".$ROOTFS_NAME.backup")
        val markerStage = File(directory, ".bannerlator-rootfs.json.new")
        val markerBackup = File(directory, ".bannerlator-rootfs.json.backup")

        if (image.isFile && runCatching { xxHash64(image) == expected }.getOrDefault(false)) {
            if (marker.isFile) {
                val recorded = runCatching { JSONObject(marker.readText()).optString("xxh64") }.getOrNull()
                if (recorded == expected) {
                    progress?.onProgress("FEX root filesystem ready", 80)
                    return null
                }
            }
            writeAndSync(markerStage, JSONObject().put("distro", "Ubuntu 24.04").put("xxh64", expected).put("url", url).toString())
            if (marker.exists() && !marker.renameTo(markerBackup)) return "Could not update FEX root filesystem metadata"
            if (!markerStage.renameTo(marker)) {
                if (markerBackup.exists()) markerBackup.renameTo(marker)
                return "Could not save FEX root filesystem metadata"
            }
            markerBackup.delete()
            progress?.onProgress("FEX root filesystem ready", 80)
            return null
        }

        stage.delete(); backup.delete(); markerStage.delete(); markerBackup.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SteamDeck-Decky-Manager")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            progress?.onProgress("Downloading FEX root filesystem (about 500 MB)", 0)
            val code = connection.responseCode
            if (code !in 200..299) return "FEX root filesystem download returned HTTP $code"
            val total = connection.contentLengthLong
            if (total > MAX_ROOTFS_BYTES) return "FEX root filesystem exceeded the 1 GB safety limit"
            val required = if (total > 0) total else MAX_ROOTFS_BYTES
            val free = StatFs(directory.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
            if (free < required + 16L * 1024 * 1024) return "Not enough free space to download the FEX root filesystem"
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input -> FileOutputStream(stage).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_ROOTFS_BYTES || (total > 0 && copied > total)) error("FEX root filesystem exceeded its published size")
                    output.write(buffer, 0, count)
                    val percent = if (total > 0) (copied * 80 / total).toInt().coerceAtMost(79) else -1
                    if (percent >= 0 && percent != lastPercent) {
                        progress?.onProgress("Downloading FEX root filesystem (about 500 MB)", percent)
                        lastPercent = percent
                    }
                }
                output.fd.sync()
            } }
            if (copied == 0L || (total > 0 && copied != total)) error("FEX root filesystem download was incomplete ($copied of $total bytes)")
            progress?.onProgress("Verifying FEX root filesystem", -1)
            if (xxHash64(stage) != expected) error("FEX root filesystem checksum did not match; the existing image was kept")

            writeAndSync(markerStage, JSONObject().put("distro", "Ubuntu 24.04").put("xxh64", expected).put("url", url).toString())
            if (image.exists() && !image.renameTo(backup)) error("Could not stage the current FEX root filesystem")
            if (marker.exists() && !marker.renameTo(markerBackup)) {
                backup.takeIf { it.exists() }?.renameTo(image)
                error("Could not stage FEX root filesystem metadata")
            }
            if (!stage.renameTo(image)) {
                backup.takeIf { it.exists() }?.renameTo(image)
                markerBackup.takeIf { it.exists() }?.renameTo(marker)
                error("Could not activate the FEX root filesystem")
            }
            if (!markerStage.renameTo(marker)) {
                image.delete()
                backup.takeIf { it.exists() }?.renameTo(image)
                markerBackup.takeIf { it.exists() }?.renameTo(marker)
                error("Could not save FEX root filesystem metadata")
            }
            backup.delete(); markerBackup.delete()
            progress?.onProgress("FEX root filesystem ready", 80)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "FEX root filesystem setup failed", e)
            return e.message ?: "FEX root filesystem setup failed"
        } finally {
            connection.disconnect()
            stage.delete(); markerStage.delete()
            backup.takeIf { !image.exists() }?.renameTo(image)
            markerBackup.takeIf { !marker.exists() }?.renameTo(marker)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Streaming xxHash64, matching the checksum format published by FEX's RootFS manifest. */
    private fun xxHash64(file: File): String {
        val p1 = -7046029288634856825L
        val p2 = -4417276706812531889L
        val p3 = 1609587929392839161L
        val p4 = -8796714831421723037L
        val p5 = 2870177450012600261L
        fun round(acc: Long, value: Long) = java.lang.Long.rotateLeft(acc + value * p2, 31) * p1
        fun readLong(data: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0..7) value = value or ((data[offset + i].toLong() and 0xff) shl (8 * i))
            return value
        }
        fun readInt(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xff) or ((data[offset + 1].toLong() and 0xff) shl 8) or
                ((data[offset + 2].toLong() and 0xff) shl 16) or ((data[offset + 3].toLong() and 0xff) shl 24)

        var v1 = p1 + p2
        var v2 = p2
        var v3 = 0L
        var v4 = -p1
        var length = 0L
        val data = ByteArray(64 * 1024 + 32)
        var carry = 0
        BufferedInputStream(FileInputStream(file)).use { input ->
            while (true) {
                val count = input.read(data, carry, data.size - carry - 32)
                if (count < 0) break
                length += count
                val limit = carry + count
                var offset = 0
                while (offset + 32 <= limit) {
                    v1 = round(v1, readLong(data, offset)); offset += 8
                    v2 = round(v2, readLong(data, offset)); offset += 8
                    v3 = round(v3, readLong(data, offset)); offset += 8
                    v4 = round(v4, readLong(data, offset)); offset += 8
                }
                carry = limit - offset
                if (carry > 0) System.arraycopy(data, offset, data, 0, carry)
            }
        }
        var hash = if (length >= 32) {
            var result = java.lang.Long.rotateLeft(v1, 1) + java.lang.Long.rotateLeft(v2, 7) +
                java.lang.Long.rotateLeft(v3, 12) + java.lang.Long.rotateLeft(v4, 18)
            for (value in longArrayOf(v1, v2, v3, v4)) result = (result xor round(0, value)) * p1 + p4
            result
        } else p5
        hash += length
        var offset = 0
        while (offset + 8 <= carry) {
            hash = java.lang.Long.rotateLeft(hash xor round(0, readLong(data, offset)), 27) * p1 + p4
            offset += 8
        }
        if (offset + 4 <= carry) {
            hash = java.lang.Long.rotateLeft(hash xor (readInt(data, offset) * p1), 23) * p2 + p3
            offset += 4
        }
        while (offset < carry) {
            hash = java.lang.Long.rotateLeft(hash xor ((data[offset].toLong() and 0xff) * p5), 11) * p1
            offset++
        }
        hash = hash xor (hash ushr 33)
        hash *= p2
        hash = hash xor (hash ushr 29)
        hash *= p3
        hash = hash xor (hash ushr 32)
        return "%016x".format(hash)
    }

    private fun isX86_64Elf(file: File): Boolean = FileInputStream(file).use { input ->
        val header = ByteArray(20)
        if (input.read(header) != header.size) return@use false
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() && header[4] == 2.toByte() && header[5] == 1.toByte() &&
            header[18] == 62.toByte() && header[19] == 0.toByte()
    }

    private fun copyAndSync(source: File, target: File) {
        FileInputStream(source).use { input -> FileOutputStream(target).use { output ->
            input.copyTo(output, 64 * 1024)
            output.fd.sync()
        } }
    }

    private fun writeAndSync(target: File, content: String) {
        FileOutputStream(target).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    /** Delete links as links so a plugin-created symlink can never make a wipe leave ~/homebrew. */
    private fun deleteTree(path: File): Boolean {
        if (!Files.exists(path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(path.toPath()) || !path.isDirectory) return path.delete()
        val children = path.listFiles() ?: return false
        for (child in children) if (!deleteTree(child)) return false
        return path.delete()
    }
}
