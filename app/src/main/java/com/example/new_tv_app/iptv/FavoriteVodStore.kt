package com.example.new_tv_app.iptv

import android.content.Context
import android.net.Uri
import com.example.new_tv_app.Movie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists favorite VOD items (movies / series episodes) keyed by Xtream stream id from the playback URL.
 */
object FavoriteVodStore {

    private const val PREFS = "iptv_favorite_vod"
    private const val KEY_ENTRIES = "entries_json"

    fun readAll(context: Context): List<Movie> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val movieObj = o.optJSONObject("movie") ?: continue
                    add(parseMovie(movieObj))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun isFavorite(context: Context, streamId: String): Boolean {
        val id = streamId.trim()
        if (id.isEmpty()) return false
        return readAll(context).any { streamIdFromMovieUrl(it.videoUrl) == id }
    }

    fun add(context: Context, movie: Movie) {
        val sid = streamIdFromMovieUrl(movie.videoUrl) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = readAll(context).toMutableList()
        current.removeAll { streamIdFromMovieUrl(it.videoUrl) == sid }
        current.add(0, movie)
        prefs.edit().putString(KEY_ENTRIES, toJsonArray(current).toString()).apply()
    }

    fun remove(context: Context, streamId: String) {
        val sid = streamId.trim()
        if (sid.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = readAll(context).filter { streamIdFromMovieUrl(it.videoUrl) != sid }
        prefs.edit().putString(KEY_ENTRIES, toJsonArray(current).toString()).apply()
    }

    fun removeMovie(context: Context, movie: Movie) {
        val sid = streamIdFromMovieUrl(movie.videoUrl) ?: return
        remove(context, sid)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }

    /** Last path segment without extension — matches [IptvStreamUrls] movie/series URLs. */
    fun streamIdFromMovieUrl(videoUrl: String?): String? {
        if (videoUrl.isNullOrBlank()) return null
        val seg = Uri.parse(videoUrl.trim()).lastPathSegment ?: return null
        val dot = seg.lastIndexOf('.')
        return if (dot > 0) seg.substring(0, dot) else seg
    }

    private fun parseMovie(movieObj: JSONObject): Movie = Movie(
        id = movieObj.optLong("id", 0L),
        title = movieObj.optString("title").takeIf { it.isNotEmpty() },
        description = movieObj.optString("description").takeIf { it.isNotEmpty() },
        backgroundImageUrl = movieObj.optString("backgroundImageUrl").takeIf { it.isNotEmpty() },
        cardImageUrl = movieObj.optString("cardImageUrl").takeIf { it.isNotEmpty() },
        videoUrl = movieObj.optString("videoUrl").takeIf { it.isNotEmpty() },
        studio = movieObj.optString("studio").takeIf { it.isNotEmpty() },
    )

    private fun toJsonArray(movies: List<Movie>): JSONArray {
        val arr = JSONArray()
        for (m in movies) {
            val o = JSONObject()
            o.put("movie", movieToJson(m))
            arr.put(o)
        }
        return arr
    }

    private fun movieToJson(m: Movie): JSONObject {
        val j = JSONObject()
        j.put("id", m.id)
        j.put("title", m.title ?: JSONObject.NULL)
        j.put("description", m.description ?: JSONObject.NULL)
        j.put("backgroundImageUrl", m.backgroundImageUrl ?: JSONObject.NULL)
        j.put("cardImageUrl", m.cardImageUrl ?: JSONObject.NULL)
        j.put("videoUrl", m.videoUrl ?: JSONObject.NULL)
        j.put("studio", m.studio ?: JSONObject.NULL)
        return j
    }
}
