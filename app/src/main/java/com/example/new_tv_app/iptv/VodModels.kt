package com.example.new_tv_app.iptv

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
    /** Xtream `added` (Unix seconds), when present — used for “new” badge. */
    val addedUnixSeconds: Long? = null,
)

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
