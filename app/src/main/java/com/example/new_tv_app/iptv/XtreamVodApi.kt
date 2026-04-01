package com.example.new_tv_app.iptv

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

    /**
     * All VOD movies for search: tries `get_vod_streams` without category, then aggregates categories if empty.
     */
    suspend fun fetchAllVodStreamsForSearch(): Result<List<VodMovieItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val direct = runCatching {
                parseVodStreams(parseArray(get("get_vod_streams")))
            }.getOrElse { emptyList() }
            if (direct.isNotEmpty()) return@runCatching direct
            val cats = fetchVodCategories().getOrThrow()
            val seen = LinkedHashSet<String>()
            val merged = ArrayList<VodMovieItem>()
            for (c in cats) {
                val list = fetchVodStreams(c.id).getOrElse { emptyList() }
                for (m in list) {
                    if (seen.add(m.streamId)) merged.add(m)
                }
            }
            merged
        }
    }

    /**
     * All series for search: tries `get_series` without category, then aggregates categories if empty.
     */
    suspend fun fetchAllSeriesForSearch(): Result<List<SeriesShow>> = withContext(Dispatchers.IO) {
        runCatching {
            val direct = runCatching {
                parseSeriesList(parseArray(get("get_series")))
            }.getOrElse { emptyList() }
            if (direct.isNotEmpty()) return@runCatching direct
            val cats = fetchSeriesCategories().getOrThrow()
            val seen = LinkedHashSet<String>()
            val merged = ArrayList<SeriesShow>()
            for (c in cats) {
                val list = fetchSeries(c.id).getOrElse { emptyList() }
                for (s in list) {
                    if (seen.add(s.seriesId)) merged.add(s)
                }
            }
            merged
        }
    }

    /** First playable episode id + extension for [IptvStreamUrls.seriesEpisodeUrl]. */
    suspend fun fetchFirstSeriesEpisode(seriesId: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_series_info", "series_id" to seriesId)
            parseFirstEpisodeFromInfo(json) ?: error("No episodes in series")
        }
    }

    suspend fun fetchSeriesDetails(seriesId: String): Result<SeriesDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("get_series_info", "series_id" to seriesId)
            parseSeriesDetailsFromInfo(seriesId, json)
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

    /**
     * Reads a Unix timestamp (seconds) from common Xtream fields (`added`, `last_modified`, …).
     */
    private fun readAddedUnixSeconds(o: JSONObject, vararg preferKeys: String): Long? {
        for (k in preferKeys) {
            if (!o.has(k) || o.isNull(k)) continue
            val direct = o.optLong(k, 0L)
            if (direct > 0L) return direct
            val s = o.optString(k).trim()
            val parsed = s.toLongOrNull() ?: continue
            if (parsed > 0L) return parsed
        }
        return null
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
                    addedUnixSeconds = readAddedUnixSeconds(o, "added", "last_modified", "created"),
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
                    addedUnixSeconds = readAddedUnixSeconds(o, "last_modified", "added", "created"),
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

    /** Xtream panels vary: Hebrew/local text is often in `description` or `overview` while `plot` is empty or English. */
    private fun readSeriesPlotFromInfo(info: JSONObject?, root: JSONObject): String? {
        val candidates = ArrayList<String>()
        fun addFrom(o: JSONObject?, vararg keys: String) {
            if (o == null) return
            for (k in keys) {
                val s = o.optString(k).trim()
                if (s.isNotEmpty()) candidates.add(s)
            }
        }
        addFrom(info, "plot", "description", "overview", "storyline")
        addFrom(root, "plot", "description", "overview")
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.length }?.takeIf { it.isNotEmpty() }
    }

    private fun parseSeriesDetailsFromInfo(seriesId: String, json: String): SeriesDetails {
        val t = json.trim()
        if (!t.startsWith("{")) error("Invalid series info")
        val root = JSONObject(t)
        val info = root.optJSONObject("info")
        val seriesName = info?.optString("name").orEmpty().ifBlank { seriesId }
        val seriesPlot = readSeriesPlotFromInfo(info, root)
        val seriesCover = info?.optString("cover")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.optString("cover_big")?.trim()?.takeIf { it.isNotEmpty() }

        val seasonsArr = root.optJSONArray("seasons")
        val seasonTitles = LinkedHashMap<Int, String>()
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val o = seasonsArr.optJSONObject(i) ?: continue
                val number = o.optInt("season_number", -1)
                if (number < 0) continue
                val title = o.optString("name").ifBlank { "Season $number" }
                seasonTitles[number] = title
            }
        }

        val episodesBySeason = LinkedHashMap<Int, MutableList<SeriesEpisode>>()
        val episodesObj = root.optJSONObject("episodes")
        if (episodesObj != null) {
            val keys = episodesObj.keys().asSequence().toList()
            for (seasonKey in keys) {
                val seasonNum = seasonKey.toIntOrNull() ?: continue
                val arr = episodesObj.optJSONArray(seasonKey) ?: continue
                val bucket = episodesBySeason.getOrPut(seasonNum) { ArrayList() }
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val id = ep.optString("id").ifBlank { ep.optString("stream_id") }.trim()
                    if (id.isEmpty()) continue
                    val episodeNum = ep.optInt("episode_num", i + 1).coerceAtLeast(1)
                    val title = ep.optString("title").ifBlank {
                        ep.optString("name").ifBlank { "Episode $episodeNum" }
                    }
                    val plot = ep.optString("plot").trim().takeIf { it.isNotEmpty() }
                    val cover = ep.optString("movie_image").trim().takeIf { it.isNotEmpty() }
                        ?: ep.optString("cover").trim().takeIf { it.isNotEmpty() }
                    val ext = ep.optString("container_extension").trim().removePrefix(".").ifBlank { "mp4" }
                    bucket.add(
                        SeriesEpisode(
                            episodeId = id,
                            episodeNumber = episodeNum,
                            title = title,
                            plot = plot,
                            coverUrl = cover,
                            containerExtension = ext,
                            seasonNumber = seasonNum,
                        ),
                    )
                }
            }
        }

        for ((_, list) in episodesBySeason) {
            list.sortBy { it.episodeNumber }
        }

        val seasonNumbers = linkedSetOf<Int>()
        seasonNumbers.addAll(seasonTitles.keys)
        seasonNumbers.addAll(episodesBySeason.keys)
        val seasons = seasonNumbers.sorted().map { num ->
            SeriesSeason(
                seasonNumber = num,
                title = seasonTitles[num] ?: "Season $num",
            )
        }

        return SeriesDetails(
            seriesId = seriesId,
            name = seriesName,
            plot = seriesPlot,
            coverUrl = seriesCover,
            seasons = seasons,
            episodesBySeason = episodesBySeason,
        )
    }
}
