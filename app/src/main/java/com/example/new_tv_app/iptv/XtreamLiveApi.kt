package com.example.new_tv_app.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Xtream Codes / XUI live TV endpoints on [player_api.php](https://github.com/tellytv/xtream-api).
 */
object XtreamLiveApi {

    suspend fun fetchLiveCategories(): Result<List<LiveCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_live_categories")
            parseCategories(parseArray(json))
        }
    }

    suspend fun fetchLiveStreams(categoryId: String): Result<List<LiveStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_live_streams", "category_id" to categoryId)
            parseStreams(parseArray(json))
        }
    }

    suspend fun fetchShortEpg(streamId: String): Result<List<EpgListing>> = withContext(Dispatchers.IO) {
        runCatching {
            parseEpgListings(
                get("get_short_epg", "stream_id" to streamId, "limit" to "8")
            )
        }
    }

    private fun get(action: String, vararg extra: Pair<String, String>): String {
        val base = IptvCredentials.baseUrl()
        val u = URLEncoder.encode(IptvCredentials.usernameRaw(), StandardCharsets.UTF_8.name())
        val p = URLEncoder.encode(IptvCredentials.passwordRaw(), StandardCharsets.UTF_8.name())
        val sb = StringBuilder("$base/player_api.php?username=$u&password=$p")
        sb.append("&action=").append(URLEncoder.encode(action, StandardCharsets.UTF_8.name()))
        for ((k, v) in extra) {
            sb.append("&").append(URLEncoder.encode(k, StandardCharsets.UTF_8.name()))
                .append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8.name()))
        }
        val conn = URL(sb.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 25_000
        conn.readTimeout = 25_000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
        return text
    }

    private fun parseArray(json: String): JSONArray {
        val t = json.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val obj = JSONObject(t)
        val user = obj.optJSONObject("user_info")
        if (user != null && user.optInt("auth", 0) != 1) {
            error(user.optString("message").ifBlank { "Unauthorized" })
        }
        if (obj.has("data")) return obj.getJSONArray("data")
        return JSONArray()
    }

    private fun parseCategories(arr: JSONArray): List<LiveCategory> {
        val out = ArrayList<LiveCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("category_id")
            if (id.isBlank()) continue
            val name = o.optString("category_name").ifBlank { id }
            out.add(LiveCategory(id = id, name = name))
        }
        return out
    }

    private fun parseStreams(arr: JSONArray): List<LiveStream> {
        val out = ArrayList<LiveStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            val name = o.optString("name").ifBlank { id }
            val icon = o.optString("stream_icon").trim().takeIf { it.isNotEmpty() }
            val cat = o.optString("category_id").trim().takeIf { it.isNotEmpty() }
            val epg = o.optString("epg_channel_id").trim().takeIf { it.isNotEmpty() }
            out.add(
                LiveStream(
                    streamId = id,
                    name = name,
                    iconUrl = icon,
                    categoryId = cat,
                    epgChannelId = epg,
                )
            )
        }
        return out
    }

    private fun parseEpgListings(json: String): List<EpgListing> {
        val t = json.trim()
        if (!t.startsWith("{")) return emptyList()
        val root = JSONObject(t)
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgListing>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { continue }
            val desc = o.optString("description").trim()
            val cat = o.optString("category_name").trim().takeIf { it.isNotEmpty() }
                ?: o.optString("category").trim().takeIf { it.isNotEmpty() }
            val start = readUnix(o, "start_timestamp", "start")
            val end = readUnix(o, "stop_timestamp", "end_timestamp", "stop", "end")
            if (start <= 0L || end <= 0L || end <= start) continue
            out.add(
                EpgListing(
                    title = title,
                    description = desc,
                    category = cat,
                    startUnix = start,
                    endUnix = end,
                )
            )
        }
        return out
    }

    private fun readUnix(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val asLong = o.optLong(k, 0L)
            if (asLong > 0L) return asLong
            val parsed = o.optString(k).toLongOrNull() ?: continue
            if (parsed > 0L) return parsed
        }
        return 0L
    }
}
