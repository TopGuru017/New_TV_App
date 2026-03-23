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

    /** All live streams (no category filter). */
    suspend fun fetchAllLiveStreams(): Result<List<LiveStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_live_streams")
            parseStreams(parseArray(json))
        }
    }

    /**
     * All live streams for search: tries `get_live_streams` without category, then aggregates categories if empty.
     */
    suspend fun fetchAllLiveStreamsForSearch(): Result<List<LiveStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val direct = runCatching {
                parseStreams(parseArray(get("get_live_streams")))
            }.getOrElse { emptyList() }
            if (direct.isNotEmpty()) return@runCatching direct
            val cats = fetchLiveCategories().getOrThrow()
            val seen = LinkedHashSet<String>()
            val merged = ArrayList<LiveStream>()
            for (c in cats) {
                val list = fetchLiveStreams(c.id).getOrElse { emptyList() }
                for (s in list) {
                    if (seen.add(s.streamId)) merged.add(s)
                }
            }
            merged
        }
    }

    /** Live streams with Xtream `tv_archive` enabled (catch-up / recordings). */
    suspend fun fetchTvArchiveStreams(): Result<List<LiveStream>> =
        fetchAllLiveStreamsForSearch().map { streams -> streams.filter { it.tvArchive } }

    suspend fun fetchShortEpg(streamId: String): Result<List<EpgListing>> = withContext(Dispatchers.IO) {
        runCatching {
            parseEpgListings(
                get("get_short_epg", "stream_id" to streamId, "limit" to "8")
            )
        }
    }

    /** Full EPG for TV guide (higher limit). */
    suspend fun fetchFullEpg(streamId: String, limit: Int = 50): Result<List<EpgListing>> = withContext(Dispatchers.IO) {
        runCatching {
            parseEpgListings(
                get("get_short_epg", "stream_id" to streamId, "limit" to limit.toString())
            )
        }
    }

    /**
     * Long EPG / archive table for a stream. Tries `get_simple_data_table`, then falls back to a large `get_short_epg`.
     */
    suspend fun fetchArchiveEpgTable(streamId: String): Result<List<EpgListing>> = withContext(Dispatchers.IO) {
        runCatching {
            val table = runCatching {
                parseEpgListingsFlexible(get("get_simple_data_table", "stream_id" to streamId))
            }.getOrElse { emptyList() }
            if (table.isNotEmpty()) return@runCatching table
            parseEpgListings(
                get("get_short_epg", "stream_id" to streamId, "limit" to "500"),
            )
        }
    }

    private fun get(action: String, vararg extra: Pair<String, String>): String {
        val u = URLEncoder.encode(IptvCredentials.usernameRaw(), StandardCharsets.UTF_8.name())
        val p = URLEncoder.encode(IptvCredentials.passwordRaw(), StandardCharsets.UTF_8.name())
        val query = StringBuilder("player_api.php?username=$u&password=$p")
        query.append("&action=").append(URLEncoder.encode(action, StandardCharsets.UTF_8.name()))
        for ((k, v) in extra) {
            query.append("&").append(URLEncoder.encode(k, StandardCharsets.UTF_8.name()))
                .append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8.name()))
        }
        var lastErr: Throwable? = null
        for (base in IptvCredentials.candidateBaseUrls()) {
            val fullUrl = "$base/${query}"
            try {
                val body = httpGet(fullUrl)
                IptvCredentials.markWorkingBaseUrl(base)
                return body
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw (lastErr ?: IllegalStateException("No base URL candidates available"))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
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
            val archive = when {
                o.optInt("tv_archive", 0) == 1 -> true
                o.optString("tv_archive").equals("1", ignoreCase = true) -> true
                else -> false
            }
            out.add(
                LiveStream(
                    streamId = id,
                    name = name,
                    iconUrl = icon,
                    categoryId = cat,
                    epgChannelId = epg,
                    tvArchive = archive,
                )
            )
        }
        return out
    }

    private fun parseEpgListingsFlexible(json: String): List<EpgListing> {
        val t = json.trim()
        if (t.startsWith("[")) {
            return parseEpgListingsFromArray(JSONArray(t))
        }
        if (!t.startsWith("{")) return emptyList()
        val root = JSONObject(t)
        root.optJSONArray("epg_listings")?.let { return parseEpgListingsFromArray(it) }
        root.optJSONArray("listings")?.let { return parseEpgListingsFromArray(it) }
        root.optJSONArray("programs")?.let { return parseEpgListingsFromArray(it) }
        root.optJSONArray("data")?.let { return parseEpgListingsFromArray(it) }
        return emptyList()
    }

    private fun parseEpgListingsFromArray(arr: JSONArray): List<EpgListing> {
        val out = ArrayList<EpgListing>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isBlank()) continue
            val desc = o.optString("description").trim()
            val cat = o.optString("category_name").trim().takeIf { it.isNotEmpty() }
                ?: o.optString("category").trim().takeIf { it.isNotEmpty() }
            val start = readUnix(o, "start_timestamp", "start", "start_time")
            val end = readUnix(o, "stop_timestamp", "end_timestamp", "stop", "end", "end_time")
            if (start <= 0L || end <= 0L || end <= start) continue
            val img = o.optString("cover").trim().takeIf { it.isNotEmpty() }
                ?: o.optString("image").trim().takeIf { it.isNotEmpty() }
                ?: o.optString("icon").trim().takeIf { it.isNotEmpty() }
            out.add(
                EpgListing(
                    title = title,
                    description = desc,
                    category = cat,
                    startUnix = start,
                    endUnix = end,
                    imageUrl = img,
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
        return parseEpgListingsFromArray(arr)
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
