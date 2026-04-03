package com.example.new_tv_app.iptv

import android.content.Context
import android.net.Uri
import com.example.new_tv_app.Movie
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Simple local "last watch" history persisted in SharedPreferences.
 *
 * We store enough data to render the UI quickly and to replay via [Movie.videoUrl].
 * Entries are de-duplicated so each logical item (same VOD stream, same record slot, etc.)
 * appears at most once; the newest play time wins.
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

    fun readAll(context: Context): List<LastWatchEntry> =
        loadEntries(context, persistIfDeduped = true)

    fun readRecords(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.RECORDS }

    fun readVodSeries(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.VOD_SERIES }

    fun readVodMovies(context: Context): List<LastWatchEntry> =
        readAll(context).filter { it.kind == Kind.VOD_MOVIES }

    fun add(entry: LastWatchEntry, context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = loadEntries(context, persistIfDeduped = false)
        val key = canonicalDedupeKey(entry)
        current.removeAll { canonicalDedupeKey(it) == key }
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

    fun removeEntry(context: Context, entry: LastWatchEntry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = canonicalDedupeKey(entry)
        val current = loadEntries(context, persistIfDeduped = false)
        current.removeAll { canonicalDedupeKey(it) == key }
        prefs.edit().putString(KEY_ENTRIES, toJsonArray(current).toString()).apply()
    }

    /**
     * Parse, de-dupe (newest first wins), optionally persist if we dropped legacy duplicates.
     */
    private fun loadEntries(context: Context, persistIfDeduped: Boolean): MutableList<LastWatchEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableListOf()
        val parsed = parseEntriesJson(raw)
        val deduped = dedupeLatestFirst(parsed)
        if (persistIfDeduped && deduped.size != parsed.size) {
            prefs.edit().putString(KEY_ENTRIES, toJsonArray(deduped).toString()).apply()
        }
        return deduped.toMutableList()
    }

    private fun parseEntriesJson(raw: String): List<LastWatchEntry> =
        runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.optString("kind").let {
                        runCatching { Kind.valueOf(it) }.getOrElse { continue }
                    }
                    val played = o.optLong("played_unix_s", 0L)
                    val channel = o.optString("channel_name").takeIf { it.isNotEmpty() }
                    val tag = o.optString("tag").takeIf { it.isNotEmpty() }
                    val timeRange = o.optString("time_range").takeIf { it.isNotEmpty() }
                    val image = o.optString("image_url").takeIf { it.isNotEmpty() }

                    val movieObj = o.optJSONObject("movie") ?: continue
                    val movie = Movie(
                        id = movieObj.optLong("id", 0L),
                        title = movieObj.optString("title").takeIf { it.isNotEmpty() },
                        description = movieObj.optString("description").takeIf { it.isNotEmpty() },
                        backgroundImageUrl = movieObj.optString("backgroundImageUrl").takeIf { it.isNotEmpty() },
                        cardImageUrl = movieObj.optString("cardImageUrl").takeIf { it.isNotEmpty() },
                        videoUrl = movieObj.optString("videoUrl").takeIf { it.isNotEmpty() },
                        studio = movieObj.optString("studio").takeIf { it.isNotEmpty() },
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

    /** Keeps first occurrence per key; list is stored newest-first, so that is the latest play. */
    private fun dedupeLatestFirst(entries: List<LastWatchEntry>): List<LastWatchEntry> =
        entries.distinctBy { canonicalDedupeKey(it) }

    /**
     * Stable identity for de-duplication:
     * - Records: channel + programme id ([Movie.id] is listing xor for that slot).
     * - VOD movie / series: Xtream stream id from the last path segment (same id across .mp4/.m3u8/http/https).
     */
    /**
     * Stable key for resume position cache (matches [canonicalDedupeKey] for VOD rows).
     * Null for recordings or non-VOD URLs.
     */
    fun resumeCacheKey(entry: LastWatchEntry): String? =
        when (entry.kind) {
            Kind.VOD_MOVIES, Kind.VOD_SERIES -> canonicalDedupeKey(entry)
            else -> null
        }

    /**
     * Key for persisting playback position while a VOD movie/series URL is playing.
     * Series vs movie is inferred from the URL path.
     */
    fun resumeCacheKeyForPlayback(movie: Movie): String? {
        val url = movie.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase(Locale.US).orEmpty()
        val kind = when {
            path.contains("/series/") -> Kind.VOD_SERIES
            path.contains("/movie/") -> Kind.VOD_MOVIES
            else -> return null
        }
        return canonicalDedupeKey(
            LastWatchEntry(
                kind = kind,
                playedUnixSeconds = 0L,
                channelName = null,
                tag = null,
                timeRange = null,
                imageUrl = null,
                movie = movie,
            ),
        )
    }

    private fun canonicalDedupeKey(e: LastWatchEntry): String {
        val url = e.movie.videoUrl?.trim().orEmpty()
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase(Locale.US).orEmpty()
        val lastId = lastPathStreamId(url).orEmpty().lowercase(Locale.US)

        return when (e.kind) {
            Kind.RECORDS -> {
                val ch = e.channelName?.trim()?.lowercase(Locale.US).orEmpty()
                "R|$ch|${e.movie.id}"
            }
            Kind.VOD_MOVIES ->
                if (path.contains("/movie/") && lastId.isNotEmpty()) {
                    "VM|$lastId"
                } else {
                    "VM|fb|${normalizeUrlFallback(url)}"
                }
            Kind.VOD_SERIES ->
                if (path.contains("/series/") && lastId.isNotEmpty()) {
                    "VS|$lastId"
                } else {
                    "VS|fb|${normalizeUrlFallback(url)}"
                }
        }
    }

    private fun lastPathStreamId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val seg = Uri.parse(url.trim()).lastPathSegment ?: return null
        val dot = seg.lastIndexOf('.')
        return if (dot > 0) seg.substring(0, dot) else seg
    }

    private fun normalizeUrlFallback(url: String): String {
        if (url.isBlank()) return ""
        val u = Uri.parse(url.trim())
        val host = u.host?.lowercase(Locale.US).orEmpty()
        val path = u.path?.lowercase(Locale.US).orEmpty()
        return "$host|$path"
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
