package com.example.new_tv_app.iptv

import android.content.Context
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.example.new_tv_app.R
import java.util.Locale

data class VodCategory(
    val id: String,
    val name: String,
)

data class VodMovieItem(
    val streamId: String,
    val name: String,
    val coverUrl: String?,
    val plot: String?,
    val categoryId: String?,
    val containerExtension: String,
    /** Xtream `rating` / `rating_5based` when present (panel-sourced, often TMDB 0–10). */
    val tmdbRating: Float? = null,
    /** Xtream `added` (Unix seconds), when present — used for “new” badge. */
    val addedUnixSeconds: Long? = null,
)

/** Title line for UI: `TMDB 6.8 Name` when [VodMovieItem.tmdbRating] is set, else plain [VodMovieItem.name]. */
fun VodMovieItem.displayTitleWithTmdbRating(): String {
    val r = tmdbRating ?: return name
    return String.format(Locale.US, "TMDB %.1f %s", r, name)
}

/** Same as [displayTitleWithTmdbRating] but colors the `TMDB x.x ` prefix with [R.color.vod_tmdb_rating]. */
fun VodMovieItem.displayTitleWithTmdbRatingStyled(context: Context): CharSequence {
    val r = tmdbRating ?: return name
    val prefix = String.format(Locale.US, "TMDB %.1f ", r)
    val full = prefix + name
    val sp = SpannableString(full)
    sp.setSpan(
        ForegroundColorSpan(ContextCompat.getColor(context, R.color.vod_tmdb_rating)),
        0,
        prefix.length,
        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return sp
}

data class SeriesCategory(
    val id: String,
    val name: String,
)

data class SeriesShow(
    val seriesId: String,
    val name: String,
    val coverUrl: String?,
    val plot: String?,
    val categoryId: String?,
    /** Xtream `added` / `last_modified` (Unix seconds), when present. */
    val addedUnixSeconds: Long? = null,
)

data class SeriesSeason(
    val seasonNumber: Int,
    val title: String,
)

data class SeriesEpisode(
    val episodeId: String,
    val episodeNumber: Int,
    val title: String,
    val plot: String?,
    val coverUrl: String?,
    val containerExtension: String,
    val seasonNumber: Int,
)

data class SeriesDetails(
    val seriesId: String,
    val name: String,
    val plot: String?,
    val coverUrl: String?,
    val seasons: List<SeriesSeason>,
    val episodesBySeason: Map<Int, List<SeriesEpisode>>,
)

/** True when [addedUnixSeconds] is from the server and falls in the last 24 hours (device clock). */
fun isVodNewWithin24Hours(addedUnixSeconds: Long?): Boolean {
    if (addedUnixSeconds == null || addedUnixSeconds <= 0L) return false
    val nowSec = System.currentTimeMillis() / 1000L
    val ageSec = nowSec - addedUnixSeconds
    return ageSec >= 0L && ageSec < 24L * 3600L
}
