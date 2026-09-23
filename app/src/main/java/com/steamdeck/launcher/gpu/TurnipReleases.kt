package com.steamdeck.launcher.gpu

import android.content.Context
import com.steamdeck.launcher.core.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The latest Banners-Turnip release, offered in both driver menus as downloads. Checked only when
 * the user taps refresh - the result is remembered, so the menu shows the last release found
 * without going online. A download goes through the same importer as a zip picked by hand, so it
 * gets the same checks and lands as an ordinary imported driver.
 *
 * Asset names are `Turnip-<tag>[-variant][-Linux|-Wayland].zip`: the plain ones are AdrenoTools
 * drivers for the display driver menu, `-Linux` ones are glibc drivers for the runtime driver menu,
 * and `-Wayland` ones are for a path this app does not have, so they are never offered.
 */
object TurnipReleases {
    private const val API = "https://api.github.com/repos/The412Banner/Banners-Turnip/releases/latest"
    private const val PREFS = "turnip_releases"
    private const val KEY_RELEASE = "latest"
    private const val KEY_DOWNLOADS = "downloads"

    class Asset(val name: String, val url: String, val size: Long, val linux: Boolean, val gpus: String)
    class Release(val tag: String, val assets: List<Asset>, val checkedAt: Long)

    /** The release found by the last check, or null when the user has never checked. */
    fun cached(context: Context): Release? {
        val raw = prefs(context).getString(KEY_RELEASE, null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    /** Ask GitHub for the latest release now and remember it. Throws on network or API errors. */
    fun refresh(context: Context): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SteamDeck-app")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub's rate limit was hit - try again later")
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            val assets = JSONArray()
            val list = json.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until list.length()) {
                val a = list.getJSONObject(i)
                assets.put(JSONObject().put("name", a.getString("name"))
                    .put("url", a.getString("browser_download_url")).put("size", a.optLong("size")))
            }
            val stored = JSONObject().put("tag", tag).put("assets", assets).put("checkedAt", System.currentTimeMillis())
            prefs(context).edit().putString(KEY_RELEASE, stored.toString()).apply()
            return parse(stored)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: JSONObject): Release {
        val tag = json.getString("tag")
        val out = ArrayList<Asset>()
        val list = json.getJSONArray("assets")
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            classify(a.getString("name"), tag)?.let { (linux, gpus) ->
                out.add(Asset(a.getString("name"), a.getString("url"), a.optLong("size"), linux, gpus))
            }
        }
        return Release(tag, out, json.optLong("checkedAt"))
    }

    /** (is it a Linux driver, which GPUs it is for), or null for anything this app cannot use. */
    internal fun classify(name: String, tag: String): Pair<Boolean, String>? {
        val prefix = "Turnip-$tag"
        if (!name.startsWith(prefix) || !name.endsWith(".zip")) return null
        var variant = name.removePrefix(prefix).removeSuffix(".zip")
        if (variant.endsWith("-Wayland")) return null
        val linux = variant.endsWith("-Linux")
        variant = variant.removeSuffix("-Linux")
        val gpus = when {
            variant.isEmpty() -> "Adreno 6xx/7xx"
            variant == "-A8xx" -> "Adreno 8xx"
            variant.startsWith("-710-720") -> "Adreno 710/720" + if (variant.contains("Test")) " (test)" else ""
            else -> variant.trimStart('-')
        }
        return linux to gpus
    }

    /** The id an asset was installed as, when it was downloaded before and is still installed. */
    fun installedId(context: Context, asset: Asset, isInstalled: (String) -> Boolean): String? =
        downloads(context).optString(asset.name, "").takeIf { it.isNotEmpty() && isInstalled(it) }

    fun recordDownload(context: Context, asset: Asset, id: String) {
        prefs(context).edit().putString(KEY_DOWNLOADS, downloads(context).put(asset.name, id).toString()).apply()
    }

    /** Forget a deleted driver, so its release offers the download again. */
    fun forget(context: Context, id: String) {
        val d = downloads(context)
        val keys = d.keys().asSequence().filter { d.optString(it) == id }.toList()
        if (keys.isEmpty()) return
        keys.forEach { d.remove(it) }
        prefs(context).edit().putString(KEY_DOWNLOADS, d.toString()).apply()
    }

    /** Download an asset into the cache; the caller imports it and deletes the file. */
    fun download(context: Context, asset: Asset, progress: (Int) -> Unit): File {
        val target = File(context.cacheDir, asset.name)
        val c = URL(asset.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "SteamDeck-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("download answered HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            return target
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun downloads(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_DOWNLOADS, "{}")!!) }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
