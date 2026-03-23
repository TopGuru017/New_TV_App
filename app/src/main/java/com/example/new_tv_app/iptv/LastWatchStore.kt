package com.example.new_tv_app.iptv

import android.content.Context
import com.example.new_tv_app.Movie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple local "last watch" history persisted in SharedPreferences.
 *
 * We store enough data to render the UI quickly and to replay via [Movie.videoUrl].
 */
object LastWatchStore {

    enum class Kind { RECORDS, VOD_SERIES, VOD_MOVIES }

    data class LastWatchEntry(
        val kind: Kind,
        val playedUnixSeconds: Long,
        val channelName: String?,
        val tag: String?,
        val timeRange: String?,
        val imageUrl: String?,
        val movie: Movie,
    )

    private const val PREFS = "iptv_last_watch"
    private const val KEY_ENTRIES = "entries_json"
    private const val MAX_ENTRIES = 24

    fun readAll(context: Context): List<LastWatchEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.optString("kind").let {
                        runCatching { Kind.valueOf(it) }.getOrElse { continue }
                    }
                    val played = o.optLong("played_unix_s", 0L)
                    val channel = o.optString("channel_name", null)
                    val tag = o.optString("tag", null)
                    val timeRange = o.optString("time_range", null)
                    val image = o.optString("image_url", null)

                    val movieObj = o.optJSONObject("movie") ?: continue
                    val movie = Movie(
                        id = movieObj.optLong("id", 0L),
                        title = movieObj.optString("title", null),
                        description = movieObj.optString("description", null),
                        backgroundImageUrl = movieObj.optString("backgroundImageUrl", null),
                        cardImageUrl = movieObj.optString("cardImageUrl", null),
                        videoUrl = movieObj.optString("videoUrl", null),
                        studio = movieObj.optString("studio", null),
                    )
                    add(
                        LastWatchEntry(
                            kind = kind,
                            playedUnixSeconds = played,
                            channelName = channel,
                            tag = tag,
                            timeRange = timeRange,
                            imageUrl = image,
                            movie = movie,
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readRecords(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.RECORDS }

    fun readVodSeries(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.VOD_SERIES }

    fun readVodMovies(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.VOD_MOVIES }

    fun add(entry: LastWatchEntry, context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = readAll(context).toMutableList()
        // De-dupe by (kind + video url + channel name).
        current.removeAll {
            it.kind == entry.kind &&
                it.movie.videoUrl == entry.movie.videoUrl &&
                it.channelName == entry.channelName
        }
        current.add(0, entry)
        while (current.size > MAX_ENTRIES) current.removeAt(current.lastIndex)
        prefs.edit().putString(KEY_ENTRIES, toJsonArray(current).toString()).apply()
    }

    fun addRecords(
        context: Context,
        playedUnixSeconds: Long,
        channelName: String?,
        tag: String?,
        timeRange: String?,
        imageUrl: String?,
        movie: Movie,
    ) {
        add(
            LastWatchEntry(
                kind = Kind.RECORDS,
                playedUnixSeconds = playedUnixSeconds,
                channelName = channelName,
                tag = tag,
                timeRange = timeRange,
                imageUrl = imageUrl,
                movie = movie,
            ),
            context,
        )
    }

    fun addVodSeries(context: Context, playedUnixSeconds: Long, movie: Movie, imageUrl: String?) {
        add(
            LastWatchEntry(
                kind = Kind.VOD_SERIES,
                playedUnixSeconds = playedUnixSeconds,
                channelName = null,
                tag = null,
                timeRange = null,
                imageUrl = imageUrl,
                movie = movie,
            ),
            context,
        )
    }

    fun addVodMovies(context: Context, playedUnixSeconds: Long, movie: Movie, imageUrl: String?) {
        add(
            LastWatchEntry(
                kind = Kind.VOD_MOVIES,
                playedUnixSeconds = playedUnixSeconds,
                channelName = null,
                tag = null,
                timeRange = null,
                imageUrl = imageUrl,
                movie = movie,
            ),
            context,
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }

    private fun toJsonArray(entries: List<LastWatchEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
            o.put("kind", e.kind.name)
            o.put("played_unix_s", e.playedUnixSeconds)
            e.channelName?.let { o.put("channel_name", it) } ?: run { o.put("channel_name", JSONObject.NULL) }
            e.tag?.let { o.put("tag", it) } ?: run { o.put("tag", JSONObject.NULL) }
            e.timeRange?.let { o.put("time_range", it) } ?: run { o.put("time_range", JSONObject.NULL) }
            e.imageUrl?.let { o.put("image_url", it) } ?: run { o.put("image_url", JSONObject.NULL) }

            val m = JSONObject()
            m.put("id", e.movie.id)
            m.put("title", e.movie.title ?: JSONObject.NULL)
            m.put("description", e.movie.description ?: JSONObject.NULL)
            m.put("backgroundImageUrl", e.movie.backgroundImageUrl ?: JSONObject.NULL)
            m.put("cardImageUrl", e.movie.cardImageUrl ?: JSONObject.NULL)
            m.put("videoUrl", e.movie.videoUrl ?: JSONObject.NULL)
            m.put("studio", e.movie.studio ?: JSONObject.NULL)
            o.put("movie", m)
            arr.put(o)
        }
        return arr
    }
}

