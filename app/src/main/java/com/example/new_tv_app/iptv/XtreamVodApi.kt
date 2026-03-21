package com.example.new_tv_app.iptv

import com.example.new_tv_app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Xtream VOD + series listing (same auth as [XtreamLiveApi]). */
object XtreamVodApi {

    suspend fun fetchVodCategories(): Result<List<VodCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_vod_categories")
            parseVodCategories(parseArray(json))
        }
    }

    suspend fun fetchVodStreams(categoryId: String): Result<List<VodMovieItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_vod_streams", "category_id" to categoryId)
            parseVodStreams(parseArray(json))
        }
    }

    suspend fun fetchSeriesCategories(): Result<List<SeriesCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_series_categories")
            parseSeriesCategories(parseArray(json))
        }
    }

    suspend fun fetchSeries(categoryId: String): Result<List<SeriesShow>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_series", "category_id" to categoryId)
            parseSeriesList(parseArray(json))
        }
    }

    /** First playable episode id + extension for [IptvStreamUrls.seriesEpisodeUrl]. */
    suspend fun fetchFirstSeriesEpisode(seriesId: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_series_info", "series_id" to seriesId)
            parseFirstEpisodeFromInfo(json) ?: error("No episodes in series")
        }
    }

    private fun get(action: String, vararg extra: Pair<String, String>): String {
        val base = BuildConfig.IPTV_BASE_URL.trimEnd('/')
        val u = URLEncoder.encode(BuildConfig.IPTV_USERNAME, StandardCharsets.UTF_8.name())
        val p = URLEncoder.encode(BuildConfig.IPTV_PASSWORD, StandardCharsets.UTF_8.name())
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

    private fun parseVodCategories(arr: JSONArray): List<VodCategory> {
        val out = ArrayList<VodCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("category_id")
            if (id.isBlank()) continue
            val name = o.optString("category_name").ifBlank { id }
            out.add(VodCategory(id = id, name = name))
        }
        return out
    }

    private fun parseVodStreams(arr: JSONArray): List<VodMovieItem> {
        val out = ArrayList<VodMovieItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            val name = o.optString("name").ifBlank { id }
            val icon = o.optString("stream_icon").trim().takeIf { it.isNotEmpty() }
            val plot = o.optString("plot").trim().takeIf { it.isNotEmpty() }
            val cat = o.optString("category_id").trim().takeIf { it.isNotEmpty() }
            val ext = o.optString("container_extension").trim().removePrefix(".").ifBlank { "mp4" }
            out.add(
                VodMovieItem(
                    streamId = id,
                    name = name,
                    coverUrl = icon,
                    plot = plot,
                    categoryId = cat,
                    containerExtension = ext,
                )
            )
        }
        return out
    }

    private fun parseSeriesCategories(arr: JSONArray): List<SeriesCategory> {
        val out = ArrayList<SeriesCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("category_id")
            if (id.isBlank()) continue
            val name = o.optString("category_name").ifBlank { id }
            out.add(SeriesCategory(id = id, name = name))
        }
        return out
    }

    private fun parseSeriesList(arr: JSONArray): List<SeriesShow> {
        val out = ArrayList<SeriesShow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("series_id")
            if (id.isBlank()) continue
            val name = o.optString("name").ifBlank { id }
            val cover = o.optString("cover").trim().takeIf { it.isNotEmpty() }
                ?: o.optString("cover_big").trim().takeIf { it.isNotEmpty() }
            val plot = o.optString("plot").trim().takeIf { it.isNotEmpty() }
            val cat = o.optString("category_id").trim().takeIf { it.isNotEmpty() }
            out.add(
                SeriesShow(
                    seriesId = id,
                    name = name,
                    coverUrl = cover,
                    plot = plot,
                    categoryId = cat,
                )
            )
        }
        return out
    }

    private fun parseFirstEpisodeFromInfo(json: String): Pair<String, String>? {
        val t = json.trim()
        if (!t.startsWith("{")) return null
        val root = JSONObject(t)
        val episodes = root.optJSONObject("episodes") ?: return null
        val seasonKeys = episodes.keys().asSequence().sorted()
        for (sk in seasonKeys) {
            val arr = episodes.optJSONArray(sk) ?: continue
            for (i in 0 until arr.length()) {
                val ep = arr.optJSONObject(i) ?: continue
                val id = ep.optString("id").ifBlank { ep.optString("stream_id") }
                if (id.isBlank()) continue
                val ext = ep.optString("container_extension").trim().removePrefix(".").ifBlank { "mp4" }
                return id to ext
            }
        }
        return null
    }
}
