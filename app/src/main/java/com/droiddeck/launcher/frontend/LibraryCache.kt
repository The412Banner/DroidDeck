package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LibraryCache {
    private const val TAG = "LibraryCache"
    private fun file(context: Context) = File(context.filesDir, "library-cache.json")
    private fun clientFile(context: Context) = File(context.filesDir, "library-cache-client")

    /** Whether the cached Steam games are the x86-64 client's; caches written before it existed are arm64's. */
    fun listedX64(context: Context): Boolean = runCatching { clientFile(context).readText().trim() == "x86_64" }.getOrDefault(false)

    fun load(context: Context): List<Library.SteamGame> = runCatching {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val array = JSONArray(f.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Library.SteamGame(
                appId = o.getInt("appId"), name = o.getString("name"), art = o.file("art"),
                library = o.getString("library"), gameId = o.getLong("gameId"), hero = o.file("hero"),
                lastPlayed = o.optLong("lastPlayed"), gameFiles = o.file("gameFiles"), protonPrefix = o.file("protonPrefix"),
                icon = o.file("icon"),
                source = o.optString("source").ifEmpty { if (o.getString("library") == Library.ADDED) Library.ADDED else Library.SOURCE_STEAM },
                storeId = o.optString("storeId").ifEmpty { null },
            )
        }
    }.getOrElse { e -> Log.w(TAG, "unreadable, ignored: ${e.message}"); emptyList() }

    fun save(context: Context, games: List<Library.SteamGame>, x64: Boolean? = null) {
        runCatching {
            val array = JSONArray()
            for (g in games) array.put(
                JSONObject().put("appId", g.appId).put("name", g.name).put("library", g.library).put("gameId", g.gameId)
                    .put("lastPlayed", g.lastPlayed).putFile("art", g.art).putFile("hero", g.hero)
                    .putFile("gameFiles", g.gameFiles).putFile("protonPrefix", g.protonPrefix).putFile("icon", g.icon)
                    .put("source", g.source).apply { g.storeId?.let { put("storeId", it) } },
            )
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(f)
            x64?.let { clientFile(context).writeText(if (it) "x86_64" else "arm64") }
        }.onFailure { e -> Log.w(TAG, "not saved: ${e.message}") }
    }

    private fun JSONObject.file(key: String): File? = optString(key).takeIf { it.isNotEmpty() }?.let(::File)
    private fun JSONObject.putFile(key: String, f: File?): JSONObject = if (f == null) this else put(key, f.path)
}
